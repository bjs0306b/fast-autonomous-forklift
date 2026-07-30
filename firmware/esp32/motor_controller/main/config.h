#ifndef CONFIG_H
#define CONFIG_H

#include "driver/gpio.h"

/* FreeRTOS */
#define MOTOR_TASK_STACK_SIZE           4096
#define MOTOR_TASK_PRIORITY             5

#define COMM_TASK_STACK_SIZE            3072
#define COMM_TASK_PRIORITY              4

/*
 * Sampling must not be delayed by telemetry, so the IMU task runs at a high
 * priority on core 0 while the USB uplink drains its queue on core 1.
 */
#define IMU_TASK_STACK_SIZE             4096
#define IMU_TASK_PRIORITY               10
#define IMU_TASK_CORE_ID                0

#define TELEMETRY_TASK_STACK_SIZE       4096
#define TELEMETRY_TASK_PRIORITY         4
#define TELEMETRY_TASK_CORE_ID          1

/* Fork lift stepper motor (BIGTREETECH TMC2209 V1.1, STEP/DIR mode) */
#define STEPPER_MOTOR_STEP_GPIO           GPIO_NUM_4
#define STEPPER_MOTOR_DIR_GPIO            GPIO_NUM_5
#define STEPPER_MOTOR_ENABLE_GPIO         GPIO_NUM_6
#define STEPPER_MOTOR_LOWER_LIMIT_GPIO    GPIO_NUM_7
#define STEPPER_MOTOR_ENABLE_ACTIVE_LEVEL 0
#define STEPPER_MOTOR_LOWER_LIMIT_ACTIVE_LEVEL 1
#define STEPPER_MOTOR_EXTEND_DIR_LEVEL    1

#define STEPPER_MOTOR_TIMER_RESOLUTION_HZ 1000000U
#define STEPPER_MOTOR_STEP_PULSE_HIGH_US  5U
#define STEPPER_MOTOR_PROFILE_PERIOD_MS   10U
#define STEPPER_MOTOR_TASK_STACK_SIZE     3072U
#define STEPPER_MOTOR_TASK_PRIORITY       5U

#define STEPPER_MOTOR_START_RATE_SPS      100U
#define STEPPER_MOTOR_DEFAULT_RATE_SPS    500U
#define STEPPER_MOTOR_DEFAULT_ACCEL_SPS2  250U
#define STEPPER_MOTOR_DEFAULT_MOVE_STEPS  1600U
#define STEPPER_MOTOR_HOMING_RATE_SPS     1000U
#define STEPPER_MOTOR_HOMING_ACCEL_SPS2   300U
#define STEPPER_MOTOR_HOMING_MAX_STEPS    300000U
#define STEPPER_MOTOR_HOME_BACKOFF_DELAY_MS 500U
#define STEPPER_MOTOR_HOME_BACKOFF_STEPS  1600U
#define STEPPER_MOTOR_HOME_BACKOFF_RATE_SPS 500U
#define STEPPER_MOTOR_HOME_BACKOFF_ACCEL_SPS2 300U
#define STEPPER_MOTOR_LIMIT_DEBOUNCE_MS   20U

#define STEPPER_MOTOR_MIN_RATE_SPS        20U
#define STEPPER_MOTOR_MAX_RATE_SPS        5000U
#define STEPPER_MOTOR_MAX_ACCEL_SPS2      20000U

/*
 * Keep disabled by default. Enable only for a wheel-off/load-free bench test.
 * The normal firmware never moves the lift automatically at boot.
 */
#define STEPPER_MOTOR_STARTUP_TEST_ENABLED 0
#define STEPPER_MOTOR_STARTUP_HOME_ENABLED 1
#define STEPPER_MOTOR_STARTUP_TEST_STEPS   500U
#define STEPPER_MOTOR_STARTUP_TEST_RATE_SPS 100U

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

/*
 * IMU (MPU6500 / MPU9250) on SPI2.
 *
 * Board is a Geekble nano ESP32-S3 (Arduino Nano ESP32 footprint), so the pin
 * numbers below are the board's labelled SPI header. GPIO15/16 do not exist on
 * this footprint. A4/A5 (GPIO11/12) are deliberately left free for the future
 * VL53L8CX ToF pair on I2C1.
 *
 *   SCK  D13 / GPIO48   (shared with the on-board LED; drop the data clock to
 *                        4 MHz if WHO_AM_I reads become intermittent)
 *   MOSI D11 / GPIO38
 *   MISO D12 / GPIO47
 *   NCS  D10 / GPIO21
 *   INT  D7  / GPIO10
 */
#define IMU_SPI_HOST                    SPI2_HOST
#define IMU_SPI_SCLK_GPIO               GPIO_NUM_48
#define IMU_SPI_MOSI_GPIO               GPIO_NUM_38
#define IMU_SPI_MISO_GPIO               GPIO_NUM_47
#define IMU_SPI_CS_GPIO                 GPIO_NUM_21
#define IMU_INT_GPIO                    GPIO_NUM_10

/* Register access is limited to 1 MHz by the datasheet; bursts may go faster */
#define IMU_SPI_CONFIG_CLOCK_HZ         1000000
#define IMU_SPI_DATA_CLOCK_HZ           8000000

/* 1 kHz internal rate / (1 + SMPLRT_DIV) */
#define IMU_SAMPLE_RATE_HZ              100U
#define IMU_SAMPLE_RATE_DIVIDER         9U
#define IMU_DLPF_CONFIG                 0x03U   /* 41 Hz */
#define IMU_GYRO_FULL_SCALE_CONFIG      0x00U   /* +-250 dps */
#define IMU_GYRO_SENSITIVITY_LSB_DPS    83.4f

/* Gyro bias tracking; see the idle-detection note in task_imu.c */
#define IMU_BIAS_CALIBRATION_SAMPLES    300U
#define IMU_BIAS_CALIBRATION_TIMEOUT_MS 5000U
#define IMU_BIAS_IDLE_HOLD_MS           1000U
#define IMU_BIAS_UPDATE_ALPHA           0.001f

/* Telemetry uplink: native USB CDC (USB Serial/JTAG) */
#define TELEMETRY_QUEUE_LENGTH          32U
#define TELEMETRY_USB_TX_BUFFER_SIZE    1024U
#define TELEMETRY_USB_RX_BUFFER_SIZE    256U
#define TELEMETRY_FRAME_MAX_LENGTH      96U
#define TELEMETRY_STATUS_PERIOD_MS      1000U

#endif
