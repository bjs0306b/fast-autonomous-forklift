#ifndef TASK_MOTOR_H
#define TASK_MOTOR_H

#include <stdbool.h>
#include <stdint.h>

#include "esp_err.h"

typedef struct {
    int8_t drive_percent;
    uint16_t steering_cdeg;
    uint32_t sequence;
} motor_command_t;

esp_err_t motor_task_start(void);
esp_err_t motor_task_submit_command(const motor_command_t *command);

/*
 * True while the drive motor is commanded to a full stop. This vehicle is
 * car-like, so a zero drive command means zero yaw rate whatever the steering
 * angle is; the IMU task uses this to gate gyro bias re-estimation.
 */
bool motor_task_drive_is_idle(void);

#endif
