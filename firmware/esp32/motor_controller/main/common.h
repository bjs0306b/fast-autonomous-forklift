#ifndef COMMON_H
#define COMMON_H

#include <stdbool.h>
#include <stdint.h>

typedef enum {
    MOTOR_STATE_UNINITIALIZED = 0,
    MOTOR_STATE_IDLE,
    MOTOR_STATE_RUNNING,
    MOTOR_STATE_STOPPED,
    MOTOR_STATE_ERROR
} motor_state_t;

#endif