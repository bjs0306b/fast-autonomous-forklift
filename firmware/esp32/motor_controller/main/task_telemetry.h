#ifndef TASK_TELEMETRY_H
#define TASK_TELEMETRY_H

#include <stdbool.h>
#include <stdint.h>

#include "esp_err.h"

#include "config.h"

/* One gyro sample, already bias-corrected, as produced by the IMU task */
typedef struct {
    uint32_t sequence;
    int64_t timestamp_us;
    int32_t gyro_z_mdps;
    int32_t temperature_cdeg;
} imu_sample_t;

/* One 8x8 ranging frame from a single ToF sensor */
typedef struct {
    uint8_t sensor_id;
    uint32_t sequence;
    int64_t timestamp_us;
    uint16_t distance_mm[TOF_ZONE_COUNT];
    uint8_t status[TOF_ZONE_COUNT];
} tof_sample_t;

/*
 * One wheel-encoder reading.
 *
 * The count is cumulative and signed rather than a per-tick delta, so a frame
 * lost on the way to the host costs timing resolution but never distance --
 * the next reading still carries everything the wheel has done.
 */
typedef struct {
    uint32_t sequence;
    int64_t timestamp_us;
    int32_t count;
    uint32_t read_errors;
} encoder_sample_t;

/* Low-rate health record; emitted once per TELEMETRY_STATUS_PERIOD_MS */
typedef struct {
    uint8_t who_am_i;
    int32_t bias_mdps;
    bool idle;
    uint32_t sequence;
} imu_status_t;

/*
 * Per-sensor ToF health, also once per TELEMETRY_STATUS_PERIOD_MS.
 *
 * The error counts are what make wiring quality arguable. Zero while the
 * vehicle is still and climbing once the stepper or drive motor runs is EMI;
 * without a number that shows up only as data that is occasionally wrong.
 */
typedef struct {
    uint8_t sensor_id;
    bool present;
    uint32_t read_errors;
    uint32_t data_ready_errors;
    uint32_t interrupts;
    uint32_t published;
    uint32_t polled;
} tof_status_t;

esp_err_t telemetry_task_start(void);

/*
 * Both submit functions are non-blocking. A full queue drops the sample and
 * increments the drop counter reported in the status frame; the control loop
 * must never wait on telemetry.
 */
esp_err_t telemetry_submit_imu(const imu_sample_t *sample);
esp_err_t telemetry_submit_tof(const tof_sample_t *sample);
esp_err_t telemetry_submit_encoder(const encoder_sample_t *sample);
void telemetry_publish_imu_status(const imu_status_t *status);
void telemetry_publish_tof_status(const tof_status_t *status);

#endif
