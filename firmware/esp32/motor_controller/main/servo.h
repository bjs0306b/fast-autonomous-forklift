#ifndef SERVO_H
#define SERVO_H

#include "esp_err.h"

esp_err_t servo_init(void);
esp_err_t servo_set_angle(float angle_deg);
esp_err_t servo_stop(void);

#endif