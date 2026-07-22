#ifndef PCA9685_H
#define PCA9685_H

#include <stdbool.h>
#include <stdint.h>

#include "driver/i2c_master.h"
#include "esp_err.h"

typedef struct {
    i2c_master_dev_handle_t device_handle;
    uint8_t address;
    float frequency_hz;
    bool initialized;
} pca9685_device_t;

esp_err_t pca9685_device_init(
    pca9685_device_t *device,
    uint8_t address,
    float frequency_hz
);

esp_err_t pca9685_set_pwm(
    pca9685_device_t *device,
    uint8_t channel,
    uint16_t on_count,
    uint16_t off_count
);

esp_err_t pca9685_set_duty_percent(
    pca9685_device_t *device,
    uint8_t channel,
    uint8_t duty_percent
);

esp_err_t pca9685_set_level(
    pca9685_device_t *device,
    uint8_t channel,
    bool high
);

#endif