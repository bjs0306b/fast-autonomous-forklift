#ifndef TASK_IMU_H
#define TASK_IMU_H

#include "esp_err.h"

/*
 * Start gyro sampling. Requires telemetry_task_start() to have run first,
 * because samples are handed to the telemetry queue.
 *
 * A failure here must not be fatal: driving and the fork must keep working
 * without a gyro.
 */
esp_err_t imu_task_start(void);

#endif
