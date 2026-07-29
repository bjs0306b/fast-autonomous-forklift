#ifndef STEPPER_MOTOR_H
#define STEPPER_MOTOR_H

#include <stdbool.h>
#include <stdint.h>

#include "esp_err.h"

typedef enum {
    STEPPER_MOTOR_DIRECTION_RETRACT = 0,
    STEPPER_MOTOR_DIRECTION_EXTEND = 1
} stepper_motor_direction_t;

typedef struct {
    bool busy;
    bool stop_requested;
    bool homing;
    bool homed;
    bool lower_limit_active;
    stepper_motor_direction_t direction;
    uint32_t total_steps;
    uint32_t completed_steps;
    uint32_t current_rate_sps;
    uint32_t target_rate_sps;
    uint32_t acceleration_sps2;
} stepper_motor_status_t;

esp_err_t stepper_motor_init(void);
esp_err_t stepper_motor_move_steps(
    stepper_motor_direction_t direction,
    uint32_t steps,
    uint32_t target_rate_sps,
    uint32_t acceleration_sps2
);
esp_err_t stepper_motor_extend(void);
esp_err_t stepper_motor_retract(void);
esp_err_t stepper_motor_home(void);
esp_err_t stepper_motor_stop(void);
bool stepper_motor_is_lower_limit_active(void);
esp_err_t stepper_motor_get_status(stepper_motor_status_t *status);

#endif
