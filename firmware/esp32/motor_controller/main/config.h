#ifndef CONFIG_H
#define CONFIG_H

#include "driver/gpio.h"

/* FreeRTOS */
#define MOTOR_TASK_STACK_SIZE           4096
#define MOTOR_TASK_PRIORITY             5

#define COMM_TASK_STACK_SIZE            3072
#define COMM_TASK_PRIORITY              4

/*
 * A marginal I2C connector must not cost the whole vehicle. Actuator bring-up
 * is retried at boot, and keeps being retried afterwards, so a cable that is
 * reseated recovers without a reboot.
 */
#define MOTOR_INIT_RETRY_COUNT          5U
#define MOTOR_INIT_RETRY_DELAY_MS       500U
#define MOTOR_RECOVERY_PERIOD_MS        5000U

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

#define STEPPER_MOTOR_START_RATE_SPS      200U
#define STEPPER_MOTOR_DEFAULT_RATE_SPS    5000U
#define STEPPER_MOTOR_DEFAULT_ACCEL_SPS2  10000U
#define STEPPER_MOTOR_DEFAULT_MOVE_STEPS  19200U
#define STEPPER_MOTOR_HOMING_RATE_SPS     2000U
#define STEPPER_MOTOR_HOMING_ACCEL_SPS2   600U
#define STEPPER_MOTOR_HOMING_MAX_STEPS    300000U
#define STEPPER_MOTOR_HOME_BACKOFF_DELAY_MS 500U
#define STEPPER_MOTOR_HOME_BACKOFF_STEPS  1600U
#define STEPPER_MOTOR_HOME_BACKOFF_RATE_SPS 500U
#define STEPPER_MOTOR_HOME_BACKOFF_ACCEL_SPS2 300U
#define STEPPER_MOTOR_LIMIT_DEBOUNCE_MS   20U

#define STEPPER_MOTOR_MIN_RATE_SPS        20U
#define STEPPER_MOTOR_MAX_RATE_SPS        6000U
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
/*
 * 조향 원점·범위 — 2026-08-04 실측으로 갱신 (S15P11A304-197).
 *
 * CENTER 는 **서보 원점(100도)이 아니라 기구 직진(96도)** 이다. 서보 혼이
 * 스플라인에 약 4도 틀어져 끼워져 있다. 종전 10000 으로 두면 정지 상태에서
 * 바퀴가 좌 4도 로 꺾인 채 있고, 상한(11500)이 직진 근처라 **오른쪽으로 꺾을
 * 여유가 거의 없었다.**
 *
 * MIN/MAX 는 종전 8500~11500 (중립 +-15도) 이었는데, 이는 기구 한계를 재서 정한
 * 값이 아니라 "서보 원점이 곧 직진" 이라는 가정 위의 보수적 초기값이었다
 * (README: "초기 안전 범위"). 원점이 96도 로 바뀌면서 대칭 가용 범위가 +-11도 로
 * 줄어 정렬 제어에 부족해 +-30도 로 넓힌다.
 *
 * 서보 물리 한계는 SERVO_MIN/MAX_ANGLE_DEG (30~150도) 이므로 66~126 은 그 안이다.
 *
 * !! 링키지가 실제로 +-30도 를 못 가면 서보가 스톨한다. 전류·발열이 오르고 기어가
 *    상할 수 있다. 첫 시험은 반드시 지게차를 들고, 양 끝에서 소리·떨림이 있으면
 *    즉시 멈추고 이 값을 줄일 것.
 */
#define TELEOP_STEERING_CENTER_CDEG     9600U
#define TELEOP_STEERING_MIN_CDEG        6600U
#define TELEOP_STEERING_MAX_CDEG        12600U

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
#define IMU_GYRO_SENSITIVITY_LSB_DPS    131.0f

/* Gyro bias tracking; see the idle-detection note in task_imu.c */
#define IMU_BIAS_CALIBRATION_SAMPLES    300U
#define IMU_BIAS_CALIBRATION_TIMEOUT_MS 5000U
#define IMU_BIAS_IDLE_HOLD_MS           1000U
#define IMU_BIAS_UPDATE_ALPHA           0.001f

/*
 * The gyro must also *read* still, not merely be commanded still. 2 dps sits
 * far above the ~0.02 rad/s (1.1 dps) noise floor and far below any real
 * rotation, so it separates the two without pausing on noise.
 */
#define IMU_BIAS_STILL_THRESHOLD_DPS    2.0f
#define IMU_BIAS_STILL_THRESHOLD_LSB \
    (IMU_BIAS_STILL_THRESHOLD_DPS * IMU_GYRO_SENSITIVITY_LSB_DPS)

/*
 * Front obstacle ToF (VL53L8CX x2) on I2C1.
 *
 * A4/A5 were reserved for this when the IMU went on SPI. The Motor HAT and
 * servo keep I2C0 to themselves: a 8x8 ToF readout is a long block transfer and
 * would otherwise queue up behind servo updates.
 *
 *   MOSI_SDA   A4 / GPIO11    pull-up to 3V3 (one pair for the whole bus)
 *   MCLK_SCL   A5 / GPIO12    pull-up to 3V3
 *   LPn        A6 / GPIO13    left  - gates the I2C comms block
 *   LPn        A7 / GPIO14    right
 *   INT        A0 / GPIO1     left
 *   INT        A1 / GPIO2     right
 *   SPI_I2C_N  GND            selects I2C mode
 *   NCS        3V3            SPI deselected
 *   MISO       unconnected    SPI only
 *
 * The SATEL-style breakout brings out no PWREN, so the sensors cannot be power
 * cycled from software. Addresses are resolved by probing instead; see
 * tof_assign_address().
 */
#define TOF_I2C_PORT                    I2C_NUM_1
#define TOF_I2C_SDA_GPIO                GPIO_NUM_11
#define TOF_I2C_SCL_GPIO                GPIO_NUM_12
#define TOF_LEFT_LPN_GPIO               GPIO_NUM_13
#define TOF_RIGHT_LPN_GPIO              GPIO_NUM_14
#define TOF_LEFT_INT_GPIO               GPIO_NUM_1
#define TOF_RIGHT_INT_GPIO              GPIO_NUM_2
#define TOF_PROBE_TIMEOUT_MS            50

/*
 * A sensor that was reset or browned out needs far longer than the 10 ms that
 * settles a plain LPn toggle. Probing too early reports it missing while it is
 * still coming back.
 */
#define TOF_RESET_SETTLE_MS             150

/*
 * The address register is volatile. A write can be acknowledged and then lost
 * again when the part finishes an internal reset, so the move is re-checked
 * after this delay instead of being trusted on the ACK alone.
 */
#define TOF_ADDRESS_SETTLE_MS           50

/*
 * 100 kHz cannot carry two 8x8 readouts at 15 Hz. The part is rated to 1 MHz;
 * 400 kHz leaves margin for the external pull-ups actually fitted.
 */
#define TOF_I2C_CLOCK_HZ                400000

/*
 * Both parts boot at the same address, so the left one is moved out of the way
 * while the right is held silent. Addresses are 8-bit, matching the ULD API.
 */
#define TOF_ADDRESS_DEFAULT_8BIT        0x52
#define TOF_ADDRESS_LEFT_8BIT           0x54

/*
 * Bit per sensor: bit 0 left, bit 1 right. Clearing a bit keeps that sensor
 * out of the bring-up sequence entirely, including its LPn line, which is the
 * way to isolate a sensor whose wiring disturbs the working one.
 */
#define TOF_ENABLED_MASK                0x3U

#define TOF_ZONE_COUNT                  64U
#define TOF_RANGING_FREQUENCY_HZ        15U     /* 8x8 maximum */
#define TOF_SENSOR_COUNT                2U

/* Ranging is valid at target_status 5, and 9 with reduced confidence */
#define TOF_STATUS_VALID                5U
#define TOF_STATUS_VALID_LOW_CONFIDENCE 9U

/*
 * How often the task checks for frames the interrupt did not deliver. A sensor
 * whose INT line is missing, or that asserted it before the handler was
 * installed, still gets read at close to its 15 Hz rate this way.
 */
#define TOF_POLL_PERIOD_MS              30U

#define TOF_TASK_STACK_SIZE             8192
#define TOF_TASK_PRIORITY               8
#define TOF_TASK_CORE_ID                0

/* Telemetry uplink: native USB CDC (USB Serial/JTAG) */
#define TELEMETRY_QUEUE_LENGTH          32U
#define TELEMETRY_TOF_QUEUE_LENGTH      4U      /* 2 sensors x 2 frames */
#define TELEMETRY_USB_TX_BUFFER_SIZE    2048U
#define TELEMETRY_USB_RX_BUFFER_SIZE    256U
#define TELEMETRY_FRAME_MAX_LENGTH      384U
#define TELEMETRY_STATUS_PERIOD_MS      1000U

#endif
