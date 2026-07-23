#ifndef TASK_MOTOR_H
#define TASK_MOTOR_H

#include <stdint.h>

#include "esp_err.h"

typedef struct {
    int8_t drive_percent;
    uint16_t steering_cdeg;
    uint32_t sequence;
} motor_command_t;

esp_err_t motor_task_start(void);
esp_err_t motor_task_submit_command(const motor_command_t *command);

#endif
