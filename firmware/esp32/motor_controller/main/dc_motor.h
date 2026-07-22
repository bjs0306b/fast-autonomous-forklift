#ifndef DC_MOTOR_H
#define DC_MOTOR_H

#include <stdint.h>

#include "esp_err.h"

typedef enum {
    DC_MOTOR_DIRECTION_FORWARD = 0,
    DC_MOTOR_DIRECTION_REVERSE
} dc_motor_direction_t;

esp_err_t dc_motor_init(void);

esp_err_t dc_motor_run(
    dc_motor_direction_t direction,
    uint8_t speed_percent
);

esp_err_t dc_motor_stop(void);

esp_err_t dc_motor_brake(void);

#endif