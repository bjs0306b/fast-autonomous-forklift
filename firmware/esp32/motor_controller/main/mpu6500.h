#ifndef MPU6500_H
#define MPU6500_H

#include <stdint.h>

#include "esp_err.h"

/* WHO_AM_I values accepted as a genuine MPU6500-family part */
#define MPU6500_WHO_AM_I_MPU6500    0x70U
#define MPU6500_WHO_AM_I_MPU9250    0x71U
#define MPU6500_WHO_AM_I_MPU9255    0x73U

/*
 * Bring up SPI2, verify WHO_AM_I and apply the gyro-only configuration
 * (DLPF 41 Hz, +-250 dps, 100 Hz sample rate, data-ready interrupt).
 * The magnetometer and accelerometer are intentionally left unused.
 *
 * Returns ESP_ERR_NOT_FOUND when WHO_AM_I is not a known value.
 */
esp_err_t mpu6500_init(uint8_t *who_am_i_out);

/*
 * One burst read of TEMP_OUT and the three gyro axes (registers 0x41..0x48).
 * Values are raw sensor counts.
 */
esp_err_t mpu6500_read_gyro_temp(
    int16_t gyro_raw[3],
    int16_t *temp_raw
);

/* Chip temperature in centi-degrees Celsius, from a raw TEMP_OUT reading */
int32_t mpu6500_temperature_cdeg(int16_t temp_raw);

#endif
