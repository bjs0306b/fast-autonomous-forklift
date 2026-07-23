#ifndef MOTOR_CONTROLLER_H
#define MOTOR_CONTROLLER_H

#include "esp_err.h"

esp_err_t motor_controller_init(void);
esp_err_t motor_controller_stop_all(void);

#endif