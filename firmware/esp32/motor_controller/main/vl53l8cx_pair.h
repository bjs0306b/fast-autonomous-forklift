#ifndef VL53L8CX_PAIR_H
#define VL53L8CX_PAIR_H

#include <stdbool.h>
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
 * Bus health per sensor.
 *
 * This exists to turn wiring quality into a number. A count that sits at zero
 * while the vehicle is still and climbs the moment the stepper or drive motor
 * runs is EMI, which is otherwise only visible as data that is "sometimes
 * wrong" and cannot be argued about.
 */
typedef struct {
    uint32_t read_errors;        /* vl53l8cx_get_ranging_data failures */
    uint32_t data_ready_errors;  /* vl53l8cx_check_data_ready failures */
} tof_bus_stats_t;

/*
 * Bring up I2C1, move each sensor onto its own address, upload firmware and
 * start 8x8 ranging.
 *
 * Sensors are taken individually: one that does not answer is dropped off the
 * bus and the rest carry on, so a single wiring fault does not cost the whole
 * front view. Returns ESP_ERR_NOT_FOUND only when neither sensor works.
 */
esp_err_t tof_pair_init(void);

/* False for a sensor that failed to start; it publishes nothing */
bool tof_pair_is_present(tof_sensor_id_t sensor);

/* True once a sensor has a fresh frame waiting */
esp_err_t tof_pair_data_ready(tof_sensor_id_t sensor, bool *ready);

esp_err_t tof_pair_read(tof_sensor_id_t sensor, tof_zone_data_t *out);

/* Cumulative since boot; never reset, so rates are taken by differencing */
void tof_pair_get_stats(tof_sensor_id_t sensor, tof_bus_stats_t *out);

#endif
