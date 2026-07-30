#include "mpu6500.h"

#include <stddef.h>
#include <string.h>

#include "config.h"

#include "driver/spi_master.h"

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "esp_log.h"

#define MPU6500_REG_SMPLRT_DIV      0x19U
#define MPU6500_REG_CONFIG          0x1AU
#define MPU6500_REG_GYRO_CONFIG     0x1BU
#define MPU6500_REG_INT_PIN_CFG     0x37U
#define MPU6500_REG_INT_ENABLE      0x38U
#define MPU6500_REG_TEMP_OUT_H      0x41U
#define MPU6500_REG_USER_CTRL       0x6AU
#define MPU6500_REG_PWR_MGMT_1      0x6BU
#define MPU6500_REG_WHO_AM_I        0x75U

#define MPU6500_PWR_DEVICE_RESET    0x80U
#define MPU6500_PWR_CLOCK_GYRO_PLL  0x01U
#define MPU6500_USER_CTRL_I2C_IF_DIS 0x10U
#define MPU6500_INT_RAW_RDY_EN      0x01U

#define MPU6500_READ_FLAG           0x80U
#define MPU6500_BURST_MAX_LENGTH    8U

/* TEMP_OUT to degrees Celsius: raw / 333.87 + 21.0 */
#define MPU6500_TEMP_SENSITIVITY    333.87f
#define MPU6500_TEMP_OFFSET_CDEG    2100

static const char *TAG = "MPU6500";

static spi_device_handle_t s_device = NULL;
static bool s_bus_initialized = false;

static esp_err_t mpu6500_add_device(int clock_speed_hz)
{
    spi_device_interface_config_t device_config = {
        .clock_speed_hz = clock_speed_hz,
        .mode = 0,
        .spics_io_num = IMU_SPI_CS_GPIO,
        .queue_size = 1,
        .command_bits = 0,
        .address_bits = 0,
        .dummy_bits = 0
    };

    return spi_bus_add_device(
        IMU_SPI_HOST,
        &device_config,
        &s_device
    );
}

static esp_err_t mpu6500_set_clock(int clock_speed_hz)
{
    if (s_device != NULL) {
        esp_err_t result = spi_bus_remove_device(s_device);

        if (result != ESP_OK) {
            return result;
        }

        s_device = NULL;
    }

    return mpu6500_add_device(clock_speed_hz);
}

static esp_err_t mpu6500_write_reg(uint8_t reg, uint8_t value)
{
    uint8_t tx_buffer[2] = { (uint8_t)(reg & 0x7FU), value };
    spi_transaction_t transaction = {
        .length = 8U * sizeof(tx_buffer),
        .tx_buffer = tx_buffer
    };

    return spi_device_polling_transmit(s_device, &transaction);
}

static esp_err_t mpu6500_read_regs(
    uint8_t reg,
    uint8_t *out,
    size_t length
)
{
    if (out == NULL || length == 0U ||
        length > MPU6500_BURST_MAX_LENGTH) {
        return ESP_ERR_INVALID_ARG;
    }

    uint8_t tx_buffer[1U + MPU6500_BURST_MAX_LENGTH] = { 0 };
    uint8_t rx_buffer[1U + MPU6500_BURST_MAX_LENGTH] = { 0 };

    tx_buffer[0] = (uint8_t)(reg | MPU6500_READ_FLAG);

    spi_transaction_t transaction = {
        .length = 8U * (1U + length),
        .rxlength = 8U * (1U + length),
        .tx_buffer = tx_buffer,
        .rx_buffer = rx_buffer
    };

    esp_err_t result = spi_device_polling_transmit(
        s_device,
        &transaction
    );

    if (result != ESP_OK) {
        return result;
    }

    memcpy(out, &rx_buffer[1], length);
    return ESP_OK;
}

static esp_err_t mpu6500_bus_init(void)
{
    if (s_bus_initialized) {
        return ESP_OK;
    }

    spi_bus_config_t bus_config = {
        .sclk_io_num = IMU_SPI_SCLK_GPIO,
        .mosi_io_num = IMU_SPI_MOSI_GPIO,
        .miso_io_num = IMU_SPI_MISO_GPIO,
        .quadwp_io_num = -1,
        .quadhd_io_num = -1,
        .max_transfer_sz = 1 + MPU6500_BURST_MAX_LENGTH
    };

    esp_err_t result = spi_bus_initialize(
        IMU_SPI_HOST,
        &bus_config,
        SPI_DMA_DISABLED
    );

    if (result != ESP_OK) {
        ESP_LOGE(TAG, "SPI bus initialization failed: %s",
                 esp_err_to_name(result));
        return result;
    }

    s_bus_initialized = true;
    return ESP_OK;
}

static bool mpu6500_who_am_i_is_known(uint8_t who_am_i)
{
    return who_am_i == MPU6500_WHO_AM_I_MPU6500 ||
           who_am_i == MPU6500_WHO_AM_I_MPU9250 ||
           who_am_i == MPU6500_WHO_AM_I_MPU9255;
}

esp_err_t mpu6500_init(uint8_t *who_am_i_out)
{
    esp_err_t result = mpu6500_bus_init();

    if (result != ESP_OK) {
        return result;
    }

    result = mpu6500_set_clock(IMU_SPI_CONFIG_CLOCK_HZ);

    if (result != ESP_OK) {
        ESP_LOGE(TAG, "SPI device add failed: %s",
                 esp_err_to_name(result));
        return result;
    }

    result = mpu6500_write_reg(
        MPU6500_REG_PWR_MGMT_1,
        MPU6500_PWR_DEVICE_RESET
    );

    if (result != ESP_OK) {
        return result;
    }

    vTaskDelay(pdMS_TO_TICKS(100));

    /* Disable the I2C slave interface so SPI cannot be misinterpreted */
    result = mpu6500_write_reg(
        MPU6500_REG_USER_CTRL,
        MPU6500_USER_CTRL_I2C_IF_DIS
    );

    if (result != ESP_OK) {
        return result;
    }

    /* Gyro PLL is more stable than the internal oscillator */
    result = mpu6500_write_reg(
        MPU6500_REG_PWR_MGMT_1,
        MPU6500_PWR_CLOCK_GYRO_PLL
    );

    if (result != ESP_OK) {
        return result;
    }

    vTaskDelay(pdMS_TO_TICKS(10));

    uint8_t who_am_i = 0U;
    result = mpu6500_read_regs(MPU6500_REG_WHO_AM_I, &who_am_i, 1U);

    if (result != ESP_OK) {
        return result;
    }

    if (who_am_i_out != NULL) {
        *who_am_i_out = who_am_i;
    }

    if (!mpu6500_who_am_i_is_known(who_am_i)) {
        ESP_LOGE(TAG, "Unexpected WHO_AM_I 0x%02X; check wiring, CS polarity "
                      "and SPI clock",
                 who_am_i);
        return ESP_ERR_NOT_FOUND;
    }

    ESP_LOGI(TAG, "WHO_AM_I 0x%02X", who_am_i);

    /* DLPF 41 Hz keeps motor and stepper vibration from aliasing at 100 Hz */
    result = mpu6500_write_reg(MPU6500_REG_CONFIG, IMU_DLPF_CONFIG);

    if (result != ESP_OK) {
        return result;
    }

    result = mpu6500_write_reg(
        MPU6500_REG_GYRO_CONFIG,
        IMU_GYRO_FULL_SCALE_CONFIG
    );

    if (result != ESP_OK) {
        return result;
    }

    result = mpu6500_write_reg(
        MPU6500_REG_SMPLRT_DIV,
        (uint8_t)IMU_SAMPLE_RATE_DIVIDER
    );

    if (result != ESP_OK) {
        return result;
    }

    /* Active high, 50 us pulse */
    result = mpu6500_write_reg(MPU6500_REG_INT_PIN_CFG, 0x00U);

    if (result != ESP_OK) {
        return result;
    }

    result = mpu6500_write_reg(
        MPU6500_REG_INT_ENABLE,
        MPU6500_INT_RAW_RDY_EN
    );

    if (result != ESP_OK) {
        return result;
    }

    /*
     * Register access is limited to 1 MHz, sensor reads are not. Re-register
     * the device at the faster clock now that configuration is complete.
     */
    result = mpu6500_set_clock(IMU_SPI_DATA_CLOCK_HZ);

    if (result != ESP_OK) {
        ESP_LOGE(TAG, "SPI clock switch failed: %s",
                 esp_err_to_name(result));
        return result;
    }

    ESP_LOGI(TAG,
             "Configured: DLPF 41 Hz, +-250 dps, %u Hz, data clock %d Hz",
             (unsigned)IMU_SAMPLE_RATE_HZ,
             IMU_SPI_DATA_CLOCK_HZ);
    return ESP_OK;
}

esp_err_t mpu6500_read_gyro_temp(
    int16_t gyro_raw[3],
    int16_t *temp_raw
)
{
    if (gyro_raw == NULL || s_device == NULL) {
        return ESP_ERR_INVALID_STATE;
    }

    /* TEMP_OUT_H..GYRO_ZOUT_L is one contiguous burst */
    uint8_t buffer[MPU6500_BURST_MAX_LENGTH] = { 0 };
    esp_err_t result = mpu6500_read_regs(
        MPU6500_REG_TEMP_OUT_H,
        buffer,
        sizeof(buffer)
    );

    if (result != ESP_OK) {
        return result;
    }

    if (temp_raw != NULL) {
        *temp_raw = (int16_t)(((uint16_t)buffer[0] << 8) | buffer[1]);
    }

    for (size_t axis = 0; axis < 3U; axis++) {
        size_t offset = 2U + (axis * 2U);
        gyro_raw[axis] = (int16_t)(
            ((uint16_t)buffer[offset] << 8) | buffer[offset + 1U]
        );
    }

    return ESP_OK;
}

int32_t mpu6500_temperature_cdeg(int16_t temp_raw)
{
    float centi_degrees =
        ((float)temp_raw * 100.0f) / MPU6500_TEMP_SENSITIVITY;

    return (int32_t)centi_degrees + MPU6500_TEMP_OFFSET_CDEG;
}
