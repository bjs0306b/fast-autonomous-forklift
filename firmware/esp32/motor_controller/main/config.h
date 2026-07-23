#ifndef CONFIG_H
#define CONFIG_H

#include "driver/gpio.h"

/* FreeRTOS */
#define MOTOR_TASK_STACK_SIZE           4096
#define MOTOR_TASK_PRIORITY             5

#define COMM_TASK_STACK_SIZE            3072
#define COMM_TASK_PRIORITY              4
#define COMM_TASK_PERIOD_MS             1500

/* I2C */
#define I2C_SDA_GPIO                    GPIO_NUM_8
#define I2C_SCL_GPIO                    GPIO_NUM_9
#define I2C_FREQUENCY_HZ                100000

/* Servo PCA9685 */
#define SERVO_PCA9685_ADDRESS           0x60
#define SERVO_PCA9685_FREQUENCY_HZ      50.0f
#define SERVO_PCA9685_CHANNEL           0

/* MG996R */
#define SERVO_MIN_ANGLE_DEG             30.0f
#define SERVO_MAX_ANGLE_DEG             150.0f

#define SERVO_MIN_PULSE_US              1000U
#define SERVO_CENTER_PULSE_US           1500U
#define SERVO_MAX_PULSE_US              2000U

/* Waveshare Motor Driver HAT */
#define MOTOR_HAT_PCA9685_ADDRESS       0x40
#define MOTOR_HAT_PWM_FREQUENCY_HZ      1000.0f

/* Motor B: MB1 / MB2 */
#define MOTOR_B_PWMB_CHANNEL            5
#define MOTOR_B_BIN1_CHANNEL            3
#define MOTOR_B_BIN2_CHANNEL            4

/* Driving test */
#define DRIVE_REAR_STEER_CENTER_ANGLE_DEG       100.0f
#define DRIVE_REAR_STEER_RIGHT_TURN_ANGLE_DEG   70.0f
#define DRIVE_REAR_STEER_LEFT_TURN_ANGLE_DEG    130.0f

#define DRIVE_STRAIGHT_SPEED_PERCENT    80U
#define DRIVE_TURN_SPEED_PERCENT        60U
#define DRIVE_SPEED_STEP_PERCENT        5U
#define DRIVE_SPEED_STEP_DELAY_MS       100U

#define DRIVE_PHASE_DURATION_MS         2000U
#define DRIVE_LOOP_PAUSE_MS             2000U

#endif
