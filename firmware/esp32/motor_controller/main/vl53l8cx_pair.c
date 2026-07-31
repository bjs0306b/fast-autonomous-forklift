#include "vl53l8cx_pair.h"

#include <stdbool.h>
#include <string.h>

#include "config.h"

#include "driver/gpio.h"
#include "driver/i2c_master.h"

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "esp_log.h"

#include "vl53l8cx_api.h"

static const char *TAG = "TOF";

typedef struct {
    VL53L8CX_Configuration device;
    gpio_num_t lpn_gpio;
    uint16_t address_8bit;
    const char *name;
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
        s_sensors[index].device.platform.reset_gpio = TOF_PWREN_GPIO;
    }

    return ESP_OK;
}

static void tof_set_comms_enabled(tof_sensor_t *sensor, bool enabled)
{
    gpio_set_level(sensor->lpn_gpio, enabled ? 1 : 0);
}

/*
 * A bare ESP32 reboot leaves the sensors powered and still carrying the
 * addresses assigned by the previous run, so probing the default address would
 * miss the left one. Toggling PWREN puts both back to a known state first.
 */
static esp_err_t tof_hardware_reset(void)
{
    gpio_config_t control_config = {
        .pin_bit_mask = (1ULL << TOF_PWREN_GPIO) |
                        (1ULL << TOF_LEFT_LPN_GPIO) |
                        (1ULL << TOF_RIGHT_LPN_GPIO),
        .mode = GPIO_MODE_OUTPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE
    };

    esp_err_t result = gpio_config(&control_config);

    if (result != ESP_OK) {
        return result;
    }

    /* Both silent before power returns, so neither answers the default address */
    tof_set_comms_enabled(&s_sensors[TOF_SENSOR_LEFT], false);
    tof_set_comms_enabled(&s_sensors[TOF_SENSOR_RIGHT], false);

    VL53L8CX_Reset_Sensor(&s_sensors[TOF_SENSOR_LEFT].device.platform);
    vTaskDelay(pdMS_TO_TICKS(10));
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

    /* Uploads the sensor firmware; roughly a second at 400 kHz */
    status = vl53l8cx_init(&sensor->device);

    if (status != VL53L8CX_STATUS_OK) {
        ESP_LOGE(TAG, "%s sensor init failed: %u",
                 sensor->name, (unsigned)status);
        return ESP_FAIL;
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

    esp_err_t result = tof_bus_init();

    if (result != ESP_OK) {
        return result;
    }

    result = tof_hardware_reset();

    if (result != ESP_OK) {
        return result;
    }

    /*
     * Address dance: only one sensor may answer the shared default address at
     * a time, so the left is woken alone, moved, and only then is the right
     * allowed to speak.
     */
    tof_sensor_t *left = &s_sensors[TOF_SENSOR_LEFT];
    tof_sensor_t *right = &s_sensors[TOF_SENSOR_RIGHT];

    tof_set_comms_enabled(left, true);
    vTaskDelay(pdMS_TO_TICKS(10));

    result = tof_bind_handle(left, TOF_ADDRESS_DEFAULT_8BIT);

    if (result != ESP_OK) {
        return result;
    }

    uint8_t status = vl53l8cx_set_i2c_address(
        &left->device,
        TOF_ADDRESS_LEFT_8BIT
    );

    if (status != VL53L8CX_STATUS_OK) {
        ESP_LOGE(TAG, "Left sensor address change failed: %u",
                 (unsigned)status);
        return ESP_FAIL;
    }

    result = tof_bind_handle(left, TOF_ADDRESS_LEFT_8BIT);

    if (result != ESP_OK) {
        return result;
    }

    tof_set_comms_enabled(right, true);
    vTaskDelay(pdMS_TO_TICKS(10));

    result = tof_bind_handle(right, TOF_ADDRESS_DEFAULT_8BIT);

    if (result != ESP_OK) {
        return result;
    }

    result = tof_start_sensor(left);

    if (result != ESP_OK) {
        return result;
    }

    result = tof_start_sensor(right);

    if (result != ESP_OK) {
        return result;
    }

    s_initialized = true;
    return ESP_OK;
}

esp_err_t tof_pair_data_ready(tof_sensor_id_t sensor, bool *ready)
{
    if (!s_initialized || ready == NULL || sensor >= TOF_SENSOR_COUNT) {
        return ESP_ERR_INVALID_STATE;
    }

    uint8_t is_ready = 0;
    uint8_t status = vl53l8cx_check_data_ready(
        &s_sensors[sensor].device,
        &is_ready
    );

    if (status != VL53L8CX_STATUS_OK) {
        return ESP_FAIL;
    }

    *ready = is_ready != 0U;
    return ESP_OK;
}

esp_err_t tof_pair_read(tof_sensor_id_t sensor, tof_zone_data_t *out)
{
    if (!s_initialized || out == NULL || sensor >= TOF_SENSOR_COUNT) {
        return ESP_ERR_INVALID_STATE;
    }

    VL53L8CX_ResultsData results;
    uint8_t status = vl53l8cx_get_ranging_data(
        &s_sensors[sensor].device,
        &results
    );

    if (status != VL53L8CX_STATUS_OK) {
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
