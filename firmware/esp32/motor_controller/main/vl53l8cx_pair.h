#ifndef VL53L8CX_PAIR_H
#define VL53L8CX_PAIR_H

#include <stdint.h>

#include "esp_err.h"

#include "config.h"

typedef enum {
    TOF_SENSOR_LEFT = 0,
    TOF_SENSOR_RIGHT = 1
} tof_sensor_id_t;

typedef struct {
    uint16_t distance_mm[TOF_ZONE_COUNT];
    uint8_t status[TOF_ZONE_COUNT];
} tof_zone_data_t;

/*
 * Bring up I2C1, hardware-reset both sensors, move the left one off the shared
 * default address, upload firmware to both and start 8x8 ranging.
 *
 * Returns ESP_ERR_NOT_FOUND when a sensor does not answer.
 */
esp_err_t tof_pair_init(void);

/* True once a sensor has a fresh frame waiting */
esp_err_t tof_pair_data_ready(tof_sensor_id_t sensor, bool *ready);

esp_err_t tof_pair_read(tof_sensor_id_t sensor, tof_zone_data_t *out);

#endif
