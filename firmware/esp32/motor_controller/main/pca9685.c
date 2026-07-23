#include "pca9685.h"

#include <math.h>
#include <stddef.h>

#include "config.h"

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "esp_log.h"

#define PCA9685_MODE1_REG           0x00
#define PCA9685_LED0_ON_L_REG       0x06
#define PCA9685_PRE_SCALE_REG       0xFE

#define PCA9685_MODE1_RESTART       0x80
#define PCA9685_MODE1_SLEEP         0x10
#define PCA9685_MODE1_AI            0x20

#define PCA9685_FULL_BIT            0x1000U
#define PCA9685_MAX_COUNT           4095U

static const char *TAG = "PCA9685";

static i2c_master_bus_handle_t s_i2c_bus = NULL;
static bool s_i2c_bus_initialized = false;
static bool s_i2c_scan_completed = false;

static esp_err_t pca9685_bus_init(void)
{
    if (s_i2c_bus_initialized) {
        return ESP_OK;
    }

    i2c_master_bus_config_t bus_config = {
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .i2c_port = I2C_NUM_0,
        .scl_io_num = I2C_SCL_GPIO,
        .sda_io_num = I2C_SDA_GPIO,
        .glitch_ignore_cnt = 7,
        .flags.enable_internal_pullup = true
    };

    esp_err_t result = i2c_new_master_bus(
        &bus_config,
        &s_i2c_bus
    );

    if (result != ESP_OK) {
        ESP_LOGE(
            TAG,
            "Failed to initialize I2C bus: %s",
            esp_err_to_name(result)
        );
        return result;
    }

    s_i2c_bus_initialized = true;

    ESP_LOGI(
        TAG,
        "I2C bus initialized: SDA=%d, SCL=%d",
        (int)I2C_SDA_GPIO,
        (int)I2C_SCL_GPIO
    );

    return ESP_OK;
}

static void pca9685_scan_bus(void)
{
    if (s_i2c_scan_completed || !s_i2c_bus_initialized) {
        return;
    }

    ESP_LOGI(TAG, "Starting I2C scan");

    for (uint8_t address = 0x08; address <= 0x77; address++) {
        esp_err_t result = i2c_master_probe(
            s_i2c_bus,
            address,
            50
        );

        if (result == ESP_OK) {
            ESP_LOGI(
                TAG,
                "I2C device found at 0x%02X",
                address
            );
        }
    }

    ESP_LOGI(TAG, "I2C scan completed");

    s_i2c_scan_completed = true;
}

static esp_err_t pca9685_write_register(
    pca9685_device_t *device,
    uint8_t reg,
    uint8_t value
)
{
    if (device == NULL ||
        device->device_handle == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    uint8_t data[2] = {
        reg,
        value
    };

    return i2c_master_transmit(
        device->device_handle,
        data,
        sizeof(data),
        100
    );
}

static esp_err_t pca9685_read_register(
    pca9685_device_t *device,
    uint8_t reg,
    uint8_t *value
)
{
    if (device == NULL ||
        device->device_handle == NULL ||
        value == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    return i2c_master_transmit_receive(
        device->device_handle,
        &reg,
        1,
        value,
        1,
        100
    );
}

static esp_err_t pca9685_set_frequency(
    pca9685_device_t *device,
    float frequency_hz
)
{
    if (device == NULL || frequency_hz <= 0.0f) {
        return ESP_ERR_INVALID_ARG;
    }

    /*
     * prescale =
     * round(25 MHz / (4096 × frequency)) - 1
     */
    float prescale_float =
        (25000000.0f / (4096.0f * frequency_hz)) - 1.0f;

    if (prescale_float < 3.0f ||
        prescale_float > 255.0f) {
        return ESP_ERR_INVALID_ARG;
    }

    uint8_t prescale =
        (uint8_t)lroundf(prescale_float);

    uint8_t old_mode = 0;

    esp_err_t result = pca9685_read_register(
        device,
        PCA9685_MODE1_REG,
        &old_mode
    );

    if (result != ESP_OK) {
        return result;
    }

    uint8_t sleep_mode =
        (old_mode & (uint8_t)~PCA9685_MODE1_RESTART) |
        PCA9685_MODE1_SLEEP |
        PCA9685_MODE1_AI;

    result = pca9685_write_register(
        device,
        PCA9685_MODE1_REG,
        sleep_mode
    );

    if (result != ESP_OK) {
        return result;
    }

    result = pca9685_write_register(
        device,
        PCA9685_PRE_SCALE_REG,
        prescale
    );

    if (result != ESP_OK) {
        return result;
    }

    result = pca9685_write_register(
        device,
        PCA9685_MODE1_REG,
        PCA9685_MODE1_AI
    );

    if (result != ESP_OK) {
        return result;
    }

    vTaskDelay(pdMS_TO_TICKS(1));

    result = pca9685_write_register(
        device,
        PCA9685_MODE1_REG,
        PCA9685_MODE1_AI |
        PCA9685_MODE1_RESTART
    );

    if (result == ESP_OK) {
        device->frequency_hz = frequency_hz;

        ESP_LOGI(
            TAG,
            "Address 0x%02X frequency: %.1f Hz, prescale=%u",
            device->address,
            frequency_hz,
            prescale
        );
    }

    return result;
}

esp_err_t pca9685_device_init(
    pca9685_device_t *device,
    uint8_t address,
    float frequency_hz
)
{
    if (device == NULL ||
        address < 0x08 ||
        address > 0x77 ||
        frequency_hz <= 0.0f) {
        return ESP_ERR_INVALID_ARG;
    }

    esp_err_t result = pca9685_bus_init();

    if (result != ESP_OK) {
        return result;
    }

    pca9685_scan_bus();

    result = i2c_master_probe(
        s_i2c_bus,
        address,
        100
    );

    if (result != ESP_OK) {
        ESP_LOGE(
            TAG,
            "No response from address 0x%02X: %s",
            address,
            esp_err_to_name(result)
        );
        return result;
    }

    i2c_device_config_t device_config = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address = address,
        .scl_speed_hz = I2C_FREQUENCY_HZ
    };

    result = i2c_master_bus_add_device(
        s_i2c_bus,
        &device_config,
        &device->device_handle
    );

    if (result != ESP_OK) {
        ESP_LOGE(
            TAG,
            "Failed to add address 0x%02X: %s",
            address,
            esp_err_to_name(result)
        );
        return result;
    }

    device->address = address;
    device->frequency_hz = 0.0f;
    device->initialized = false;

    result = pca9685_write_register(
        device,
        PCA9685_MODE1_REG,
        PCA9685_MODE1_AI
    );

    if (result != ESP_OK) {
        return result;
    }

    result = pca9685_set_frequency(
        device,
        frequency_hz
    );

    if (result != ESP_OK) {
        return result;
    }

    device->initialized = true;

    ESP_LOGI(
        TAG,
        "PCA9685 initialized at 0x%02X",
        address
    );

    return ESP_OK;
}

esp_err_t pca9685_set_pwm(
    pca9685_device_t *device,
    uint8_t channel,
    uint16_t on_count,
    uint16_t off_count
)
{
    if (device == NULL ||
        !device->initialized ||
        channel >= 16 ||
        on_count > PCA9685_FULL_BIT ||
        off_count > PCA9685_FULL_BIT) {
        return ESP_ERR_INVALID_ARG;
    }

    uint8_t start_register =
        PCA9685_LED0_ON_L_REG + (4U * channel);

    uint8_t data[5] = {
        start_register,
        (uint8_t)(on_count & 0xFFU),
        (uint8_t)((on_count >> 8) & 0x1FU),
        (uint8_t)(off_count & 0xFFU),
        (uint8_t)((off_count >> 8) & 0x1FU)
    };

    return i2c_master_transmit(
        device->device_handle,
        data,
        sizeof(data),
        100
    );
}

esp_err_t pca9685_set_duty_percent(
    pca9685_device_t *device,
    uint8_t channel,
    uint8_t duty_percent
)
{
    if (duty_percent > 100U) {
        return ESP_ERR_INVALID_ARG;
    }

    if (duty_percent == 0U) {
        return pca9685_set_pwm(
            device,
            channel,
            0U,
            PCA9685_FULL_BIT
        );
    }

    if (duty_percent == 100U) {
        return pca9685_set_pwm(
            device,
            channel,
            PCA9685_FULL_BIT,
            0U
        );
    }

    uint16_t off_count =
        (uint16_t)(
            ((uint32_t)duty_percent * 4096U) / 100U
        );

    if (off_count > PCA9685_MAX_COUNT) {
        off_count = PCA9685_MAX_COUNT;
    }

    return pca9685_set_pwm(
        device,
        channel,
        0U,
        off_count
    );
}

esp_err_t pca9685_set_level(
    pca9685_device_t *device,
    uint8_t channel,
    bool high
)
{
    if (high) {
        return pca9685_set_pwm(
            device,
            channel,
            PCA9685_FULL_BIT,
            0U
        );
    }

    return pca9685_set_pwm(
        device,
        channel,
        0U,
        PCA9685_FULL_BIT
    );
}