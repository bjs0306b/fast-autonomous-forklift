#include "vl53l8cx_pair.h"

#include <stdbool.h>
#include <stdio.h>
#include <string.h>

#include "config.h"

#include "driver/gpio.h"
#include "driver/i2c_master.h"

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "esp_log.h"

#include "platform.h"
#include "vl53l8cx_api.h"

/* Page select, and the address register on page 0 */
#define VL53L8CX_REG_PAGE_SELECT    0x7FFFU
#define VL53L8CX_REG_I2C_ADDRESS    0x0004U
#define VL53L8CX_PAGE_ADDRESS_SETUP 0x00U
#define VL53L8CX_PAGE_DEFAULT       0x02U

static const char *TAG = "TOF";

typedef struct {
    VL53L8CX_Configuration device;
    gpio_num_t lpn_gpio;
    uint16_t address_8bit;
    const char *name;
    bool present;
    tof_bus_stats_t stats;
} tof_sensor_t;

static i2c_master_bus_handle_t s_bus = NULL;
static bool s_initialized = false;

static tof_sensor_t s_sensors[TOF_SENSOR_COUNT] = {
    [TOF_SENSOR_LEFT] = {
        .lpn_gpio = TOF_LEFT_LPN_GPIO,
        .address_8bit = TOF_ADDRESS_LEFT_8BIT,
        .name = "left"
    },
    [TOF_SENSOR_RIGHT] = {
        .lpn_gpio = TOF_RIGHT_LPN_GPIO,
        .address_8bit = TOF_ADDRESS_DEFAULT_8BIT,
        .name = "right"
    }
};

/*
 * The ULD writes through platform.handle, which i2c_master binds to a fixed
 * address at registration time. vl53l8cx_set_i2c_address() only updates
 * platform.address, so the handle has to be swapped by hand or every transfer
 * afterwards still goes to the old address.
 */
static esp_err_t tof_bind_handle(
    tof_sensor_t *sensor,
    uint16_t address_8bit
)
{
    if (sensor->device.platform.handle != NULL) {
        esp_err_t result =
            i2c_master_bus_rm_device(sensor->device.platform.handle);

        if (result != ESP_OK) {
            return result;
        }

        sensor->device.platform.handle = NULL;
    }

    i2c_device_config_t device_config = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address = (uint16_t)(address_8bit >> 1),
        .scl_speed_hz = TOF_I2C_CLOCK_HZ
    };

    esp_err_t result = i2c_master_bus_add_device(
        s_bus,
        &device_config,
        &sensor->device.platform.handle
    );

    if (result == ESP_OK) {
        sensor->device.platform.address = address_8bit;
    }

    return result;
}

static esp_err_t tof_bus_init(void)
{
    i2c_master_bus_config_t bus_config = {
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .i2c_port = TOF_I2C_PORT,
        .scl_io_num = TOF_I2C_SCL_GPIO,
        .sda_io_num = TOF_I2C_SDA_GPIO,
        .glitch_ignore_cnt = 7,
        .flags.enable_internal_pullup = true
    };

    esp_err_t result = i2c_new_master_bus(&bus_config, &s_bus);

    if (result != ESP_OK) {
        ESP_LOGE(TAG, "I2C1 bus initialization failed: %s",
                 esp_err_to_name(result));
        return result;
    }

    for (size_t index = 0; index < TOF_SENSOR_COUNT; index++) {
        s_sensors[index].device.platform.bus_config = bus_config;
        /* The breakout exposes no PWREN, so no software power cycle exists */
        s_sensors[index].device.platform.reset_gpio = GPIO_NUM_NC;
    }

    return ESP_OK;
}

static void tof_set_comms_enabled(tof_sensor_t *sensor, bool enabled)
{
    gpio_set_level(sensor->lpn_gpio, enabled ? 1 : 0);
}

/*
 * Log every address that answers on I2C1.
 *
 * Run at each isolation step, this answers the two questions that matter when
 * bring-up fails: is LPn actually gating the sensors, and what address is each
 * one currently carrying.
 */
static size_t tof_scan_bus(const char *label)
{
    char found[64];
    size_t offset = 0;
    size_t count = 0;

    found[0] = '\0';

    for (uint16_t address = 0x08; address <= 0x77; address++) {
        if (i2c_master_probe(s_bus, address, TOF_PROBE_TIMEOUT_MS) != ESP_OK) {
            continue;
        }

        count++;

        if (offset < sizeof(found) - 8) {
            offset += (size_t)snprintf(&found[offset], sizeof(found) - offset,
                                       "0x%02X ", (unsigned)address);
        }
    }

    ESP_LOGI(TAG, "I2C1 scan [%s]: %u device(s) %s",
             label, (unsigned)count, count ? found : "-");
    return count;
}

static bool tof_is_enabled(size_t sensor)
{
    return (TOF_ENABLED_MASK & (1U << sensor)) != 0U;
}

static esp_err_t tof_control_pins_init(void)
{
    uint64_t pins = 0;
    bool any_enabled = false;

    for (size_t index = 0; index < TOF_SENSOR_COUNT; index++) {
        /*
         * Every LPn is driven, including the disabled ones. Leaving a pin
         * floating lets its sensor decide for itself whether to answer, which
         * makes a disabled sensor turn up on the bus anyway.
         */
        pins |= 1ULL << s_sensors[index].lpn_gpio;
        any_enabled = any_enabled || tof_is_enabled(index);
    }

    if (!any_enabled) {
        ESP_LOGE(TAG, "TOF_ENABLED_MASK disables every sensor");
        return ESP_ERR_INVALID_STATE;
    }

    gpio_config_t control_config = {
        .pin_bit_mask = pins,
        .mode = GPIO_MODE_OUTPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE
    };

    esp_err_t result = gpio_config(&control_config);

    if (result != ESP_OK) {
        return result;
    }

    /* Silent until each one is addressed in turn; disabled ones stay silent */
    for (size_t index = 0; index < TOF_SENSOR_COUNT; index++) {
        tof_set_comms_enabled(&s_sensors[index], false);
    }

    vTaskDelay(pdMS_TO_TICKS(10));
    return ESP_OK;
}

/* True when every already-addressed sensor still answers where it should */
static bool tof_addressed_sensors_still_present(size_t upto)
{
    for (size_t index = 0; index < upto; index++) {
        if (!s_sensors[index].present) {
            continue;
        }

        if (i2c_master_probe(
                s_bus,
                (uint16_t)(s_sensors[index].address_8bit >> 1),
                TOF_PROBE_TIMEOUT_MS) != ESP_OK) {
            return false;
        }
    }

    return true;
}

/*
 * Move a sensor to a new address, rebinding the handle mid-sequence.
 *
 * vl53l8cx_set_i2c_address() cannot be used here. It writes the new address,
 * updates platform.address, and then writes the page register again -- but
 * that last write still goes through the old handle, which i2c_master bound to
 * the old address, so the sensor NACKs it and the call reports failure with
 * the page register left in the wrong state.
 *
 * The same three registers are written here, with the handle swapped over
 * right after the sensor moves.
 */
static esp_err_t tof_change_address(
    tof_sensor_t *sensor,
    uint16_t new_address_8bit
)
{
    VL53L8CX_Platform *platform = &sensor->device.platform;
    uint8_t status;

    /* The platform layer truncates esp_err_t into uint8_t, so the raw value is
     * logged as-is: 0x03 is ESP_ERR_INVALID_STATE, 0xFF is ESP_FAIL */
    status = VL53L8CX_WrByte(platform, VL53L8CX_REG_PAGE_SELECT,
                             VL53L8CX_PAGE_ADDRESS_SETUP);

    if (status != 0U) {
        ESP_LOGE(TAG, "%s: page select write failed (0x%02X)",
                 sensor->name, (unsigned)status);
        return ESP_FAIL;
    }

    status = VL53L8CX_WrByte(platform, VL53L8CX_REG_I2C_ADDRESS,
                             (uint8_t)(new_address_8bit >> 1));

    if (status != 0U) {
        ESP_LOGE(TAG, "%s: address register write failed (0x%02X)",
                 sensor->name, (unsigned)status);
        return ESP_FAIL;
    }

    /* The sensor answers the new address from here on */
    esp_err_t result = tof_bind_handle(sensor, new_address_8bit);

    if (result != ESP_OK) {
        return result;
    }

    status = VL53L8CX_WrByte(platform, VL53L8CX_REG_PAGE_SELECT,
                             VL53L8CX_PAGE_DEFAULT);

    if (status != 0U) {
        ESP_LOGE(TAG, "%s: page restore failed after the move (0x%02X)",
                 sensor->name, (unsigned)status);
        return ESP_FAIL;
    }

    /*
     * Do not trust the acknowledgement. The writes above are answered even by a
     * part that is midway through an internal reset, which then reloads its
     * defaults and drops straight back to the shared address.
     */
    vTaskDelay(pdMS_TO_TICKS(TOF_ADDRESS_SETTLE_MS));

    if (i2c_master_probe(s_bus, (uint16_t)(new_address_8bit >> 1),
                         TOF_PROBE_TIMEOUT_MS) == ESP_OK) {
        return ESP_OK;
    }

    bool back_at_default = i2c_master_probe(
        s_bus,
        (uint16_t)(TOF_ADDRESS_DEFAULT_8BIT >> 1),
        TOF_PROBE_TIMEOUT_MS) == ESP_OK;

    ESP_LOGE(TAG,
             "%s: address did not stick - writes were acknowledged but the "
             "part is %s after %u ms. Something is resetting it; check what "
             "else its control lines reach",
             sensor->name,
             back_at_default ? "back on the default address" : "silent",
             (unsigned)TOF_ADDRESS_SETTLE_MS);

    return ESP_ERR_INVALID_RESPONSE;
}

/*
 * Give one sensor the address it is supposed to have.
 *
 * A plain ESP32 reboot does not power cycle the sensors, so they come back
 * still carrying whatever address the previous run assigned. Without a PWREN
 * pin there is no way to force them back to the default, so instead the sensor
 * is isolated with LPn -- only one part can answer at a time -- and both
 * candidate addresses are probed. Whatever answers is then moved to the wanted
 * address, which makes the outcome independent of the previous state.
 *
 * The caller must have silenced the other sensor first.
 */
static esp_err_t tof_assign_address(tof_sensor_t *sensor)
{
    /*
     * Target address first. Sensors addressed earlier in the sequence are
     * still awake and holding their own addresses, so preferring the wanted
     * one keeps this from latching onto a neighbour.
     */
    const uint16_t candidates[] = {
        sensor->address_8bit,
        sensor->address_8bit == TOF_ADDRESS_DEFAULT_8BIT
            ? TOF_ADDRESS_LEFT_8BIT
            : TOF_ADDRESS_DEFAULT_8BIT
    };

    uint16_t found = 0;

    for (size_t index = 0; index < sizeof(candidates) / sizeof(*candidates);
         index++) {
        esp_err_t probe = i2c_master_probe(
            s_bus,
            (uint16_t)(candidates[index] >> 1),
            TOF_PROBE_TIMEOUT_MS
        );

        if (probe == ESP_OK) {
            found = candidates[index];
            break;
        }
    }

    if (found == 0) {
        ESP_LOGE(TAG,
                 "%s sensor answered neither 0x%02X nor 0x%02X; check wiring, "
                 "LPn and the bus pull-ups",
                 sensor->name,
                 (unsigned)(TOF_ADDRESS_DEFAULT_8BIT >> 1),
                 (unsigned)(TOF_ADDRESS_LEFT_8BIT >> 1));
        return ESP_ERR_NOT_FOUND;
    }

    esp_err_t result = tof_bind_handle(sensor, found);

    if (result != ESP_OK) {
        return result;
    }

    if (found == sensor->address_8bit) {
        ESP_LOGI(TAG, "%s sensor already at 0x%02X",
                 sensor->name, (unsigned)(found >> 1));
        return ESP_OK;
    }

    result = tof_change_address(sensor, sensor->address_8bit);

    if (result != ESP_OK) {
        ESP_LOGE(TAG, "%s sensor address change failed: %s",
                 sensor->name, esp_err_to_name(result));
        return result;
    }

    ESP_LOGI(TAG, "%s sensor moved 0x%02X -> 0x%02X",
             sensor->name,
             (unsigned)(found >> 1),
             (unsigned)(sensor->address_8bit >> 1));

    return ESP_OK;
}

static esp_err_t tof_start_sensor(tof_sensor_t *sensor)
{
    uint8_t alive = 0;
    uint8_t status = vl53l8cx_is_alive(&sensor->device, &alive);

    if (status != VL53L8CX_STATUS_OK || alive == 0U) {
        ESP_LOGE(TAG, "%s sensor not responding at 0x%02X",
                 sensor->name,
                 (unsigned)(sensor->address_8bit >> 1));
        return ESP_ERR_NOT_FOUND;
    }

    /*
     * Uploads 84 KB of sensor firmware over I2C -- about two seconds at
     * 400 kHz, and by far the longest transfer this driver ever makes.
     * A connector that is merely marginal passes every short transaction
     * and fails only here, which reads as a dead sensor rather than as
     * loose wiring. Retry rather than give up on the first failure.
     */
    for (uint32_t attempt = 1; ; attempt++) {
        status = vl53l8cx_init(&sensor->device);

        if (status == VL53L8CX_STATUS_OK) {
            if (attempt > 1U) {
                /* Loud on purpose: a retry that worked still means the
                 * link is marginal, and silence here would hide that. */
                ESP_LOGW(TAG,
                         "%s sensor init needed %lu attempts -- "
                         "check that sensor's wiring",
                         sensor->name, (unsigned long)attempt);
            }
            break;
        }

        if (attempt >= TOF_INIT_RETRY_COUNT) {
            ESP_LOGE(TAG, "%s sensor init failed after %lu attempts: %u",
                     sensor->name, (unsigned long)attempt,
                     (unsigned)status);
            return ESP_FAIL;
        }

        ESP_LOGW(TAG, "%s sensor init attempt %lu/%u failed: %u",
                 sensor->name, (unsigned long)attempt,
                 (unsigned)TOF_INIT_RETRY_COUNT, (unsigned)status);
        vTaskDelay(pdMS_TO_TICKS(TOF_INIT_RETRY_DELAY_MS));
    }

    status = vl53l8cx_set_resolution(
        &sensor->device,
        VL53L8CX_RESOLUTION_8X8
    );
    status |= vl53l8cx_set_ranging_frequency_hz(
        &sensor->device,
        TOF_RANGING_FREQUENCY_HZ
    );

    if (status != VL53L8CX_STATUS_OK) {
        ESP_LOGE(TAG, "%s sensor configuration failed: %u",
                 sensor->name, (unsigned)status);
        return ESP_FAIL;
    }

    status = vl53l8cx_start_ranging(&sensor->device);

    if (status != VL53L8CX_STATUS_OK) {
        ESP_LOGE(TAG, "%s sensor ranging start failed: %u",
                 sensor->name, (unsigned)status);
        return ESP_FAIL;
    }

    ESP_LOGI(TAG, "%s sensor ready at 0x%02X, 8x8 @ %u Hz",
             sensor->name,
             (unsigned)(sensor->address_8bit >> 1),
             (unsigned)TOF_RANGING_FREQUENCY_HZ);
    return ESP_OK;
}

esp_err_t tof_pair_init(void)
{
    if (s_initialized) {
        return ESP_ERR_INVALID_STATE;
    }

    /*
     * Which module ends up as "left" is decided by the harness, not by
     * anything in a frame, so a crossed pair looks identical to a correct one
     * from the outside. Printing the mapping the running image actually holds
     * is the only way to tell a flash that did not happen from a mapping that
     * is still wrong.
     */
    ESP_LOGI(TAG, "mapping: left LPn=GPIO%d INT=GPIO%d, "
                  "right LPn=GPIO%d INT=GPIO%d",
             TOF_LEFT_LPN_GPIO, TOF_LEFT_INT_GPIO,
             TOF_RIGHT_LPN_GPIO, TOF_RIGHT_INT_GPIO);

    esp_err_t result = tof_bus_init();

    if (result != ESP_OK) {
        return result;
    }

    result = tof_control_pins_init();

    if (result != ESP_OK) {
        return result;
    }

    /*
     * One at a time: with the other sensor silenced, whatever answers the bus
     * must be this one, whichever address it happens to be carrying.
     */
    /*
     * With every LPn held low nothing may answer. Anything that does is not
     * being gated, which means the addressing below cannot be trusted: two
     * parts can end up sharing an address, or one can be reset by the other.
     * Say so plainly here rather than letting it surface as confusing errors
     * further down.
     */
    if (tof_scan_bus("both silent") != 0) {
        ESP_LOGE(TAG,
                 "Devices answer while every LPn is low: the LPn wiring is "
                 "not gating them (A6 -> left, A7 -> right). Addresses below "
                 "may be left over from the previous run");
    }

    /*
     * Wake them one at a time and leave each one awake.
     *
     * The assigned address lives in the comms block, which LPn powers down, so
     * dropping LPn again wipes it straight back to the default. The sensors
     * therefore accumulate: each new one is the only fresh part on the bus
     * because everyone before it has already moved out of the default address.
     *
     * A sensor that fails is put back to sleep rather than left enabled. A part
     * that is unpowered or miswired can hold the bus low, which would take the
     * working sensor down with it.
     */
    size_t present_count = 0;

    for (size_t index = 0; index < TOF_SENSOR_COUNT; index++) {
        tof_sensor_t *sensor = &s_sensors[index];

        if (!tof_is_enabled(index)) {
            ESP_LOGW(TAG, "%s sensor disabled by TOF_ENABLED_MASK",
                     sensor->name);
            continue;
        }

        tof_set_comms_enabled(sensor, true);
        vTaskDelay(pdMS_TO_TICKS(10));
        tof_scan_bus(sensor->name);

        bool addressed = tof_assign_address(sensor) == ESP_OK;

        /*
         * Waking this one must not cost us the sensors already addressed. If
         * it did, its control line is reaching something it should not -- an
         * I2C_RST or a shared supply -- rather than only its own LPn. The
         * offender is put back to sleep and the victims are re-addressed,
         * since a reset sensor is back on the default address.
         */
        if (!tof_addressed_sensors_still_present(index)) {
            ESP_LOGE(TAG,
                     "Waking %s knocked an already-addressed sensor off the "
                     "bus: its control line is miswired (I2C_RST or supply, "
                     "not just LPn)",
                     sensor->name);
            tof_set_comms_enabled(sensor, false);
            vTaskDelay(pdMS_TO_TICKS(TOF_RESET_SETTLE_MS));

            for (size_t victim = 0; victim < index; victim++) {
                if (!s_sensors[victim].present) {
                    continue;
                }

                if (tof_assign_address(&s_sensors[victim]) != ESP_OK) {
                    s_sensors[victim].present = false;
                    present_count--;
                }
            }

            continue;
        }

        if (!addressed) {
            ESP_LOGW(TAG, "Dropping %s sensor off the bus and continuing",
                     sensor->name);
            tof_set_comms_enabled(sensor, false);
            vTaskDelay(pdMS_TO_TICKS(10));
            continue;
        }

        sensor->present = true;
        present_count++;
    }

    tof_scan_bus("after addressing");

    for (size_t index = 0; index < TOF_SENSOR_COUNT; index++) {
        tof_sensor_t *sensor = &s_sensors[index];

        if (!sensor->present) {
            continue;
        }

        if (tof_start_sensor(sensor) != ESP_OK) {
            sensor->present = false;
            present_count--;
            tof_set_comms_enabled(sensor, false);
        }
    }

    if (present_count == 0) {
        ESP_LOGE(TAG, "No ToF sensor came up");
        return ESP_ERR_NOT_FOUND;
    }

    if (present_count < TOF_SENSOR_COUNT) {
        ESP_LOGW(TAG, "Running with %u of %u ToF sensors",
                 (unsigned)present_count, (unsigned)TOF_SENSOR_COUNT);
    }

    s_initialized = true;
    return ESP_OK;
}

bool tof_pair_is_present(tof_sensor_id_t sensor)
{
    return sensor < TOF_SENSOR_COUNT && s_sensors[sensor].present;
}

esp_err_t tof_pair_data_ready(tof_sensor_id_t sensor, bool *ready)
{
    if (!s_initialized || ready == NULL ||
        !tof_pair_is_present(sensor)) {
        return ESP_ERR_INVALID_STATE;
    }

    uint8_t is_ready = 0;
    uint8_t status = vl53l8cx_check_data_ready(
        &s_sensors[sensor].device,
        &is_ready
    );

    if (status != VL53L8CX_STATUS_OK) {
        s_sensors[sensor].stats.data_ready_errors++;
        return ESP_FAIL;
    }

    *ready = is_ready != 0U;
    return ESP_OK;
}

esp_err_t tof_pair_read(tof_sensor_id_t sensor, tof_zone_data_t *out)
{
    if (!s_initialized || out == NULL || !tof_pair_is_present(sensor)) {
        return ESP_ERR_INVALID_STATE;
    }

    VL53L8CX_ResultsData results;
    uint8_t status = vl53l8cx_get_ranging_data(
        &s_sensors[sensor].device,
        &results
    );

    if (status != VL53L8CX_STATUS_OK) {
        s_sensors[sensor].stats.read_errors++;
        return ESP_FAIL;
    }

    for (size_t zone = 0; zone < TOF_ZONE_COUNT; zone++) {
        int16_t distance = results.distance_mm[zone];

        /* The wire format carries three hex digits, and a negative reading is
         * meaningless anyway */
        if (distance < 0) {
            distance = 0;
        } else if (distance > 4095) {
            distance = 4095;
        }

        out->distance_mm[zone] = (uint16_t)distance;
        out->status[zone] = results.target_status[zone] & 0x0FU;
    }

    return ESP_OK;
}

void tof_pair_get_stats(tof_sensor_id_t sensor, tof_bus_stats_t *out)
{
    if (out == NULL) {
        return;
    }

    if (sensor >= TOF_SENSOR_COUNT) {
        out->read_errors = 0U;
        out->data_ready_errors = 0U;
        return;
    }

    *out = s_sensors[sensor].stats;
}
