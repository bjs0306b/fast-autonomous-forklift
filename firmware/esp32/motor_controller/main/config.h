#ifndef CONFIG_H
#define CONFIG_H

#include "driver/gpio.h"

/* FreeRTOS */
#define MOTOR_TASK_STACK_SIZE           4096
#define MOTOR_TASK_PRIORITY             5

#define COMM_TASK_STACK_SIZE            3072
#define COMM_TASK_PRIORITY              4

/* Jetson UART */
#define JETSON_UART_PORT                UART_NUM_1
#define JETSON_UART_TX_GPIO             GPIO_NUM_17
#define JETSON_UART_RX_GPIO             GPIO_NUM_18
#define JETSON_UART_BAUD_RATE           115200
#define JETSON_UART_RX_BUFFER_SIZE      256
#define JETSON_UART_TX_BUFFER_SIZE      256
#define JETSON_UART_FRAME_MAX_LENGTH    96

/* Tele-operation safety limits */
#define TELEOP_WATCHDOG_TIMEOUT_MS      500U
#define TELEOP_MAX_DRIVE_PERCENT        60
#define TELEOP_STEERING_CENTER_CDEG     10000U
#define TELEOP_STEERING_MIN_CDEG        8500U
#define TELEOP_STEERING_MAX_CDEG        11500U

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

/* Mechanical steering limits */
#define DRIVE_REAR_STEER_CENTER_ANGLE_DEG       100.0f
#define DRIVE_REAR_STEER_RIGHT_TURN_ANGLE_DEG   70.0f
#define DRIVE_REAR_STEER_LEFT_TURN_ANGLE_DEG    130.0f

#endif
