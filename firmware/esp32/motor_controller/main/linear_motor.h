#ifndef LINEAR_MOTOR_H
#define LINEAR_MOTOR_H

#include "esp_err.h"

esp_err_t linear_motor_init(void);
esp_err_t linear_motor_extend(void);
esp_err_t linear_motor_retract(void);
esp_err_t linear_motor_stop(void);

#endif