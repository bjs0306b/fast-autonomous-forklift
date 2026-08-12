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

/*
 * ToF(VL53L8CX) 초기화 재시도. `vl53l8cx_pair.c` 가 쓴다.
 *
 * ⚠️ 이 두 값은 원래 **젯슨의 로컬 config.h 에만** 있었고, 2026-08-07 에 노트북
 * 사본을 젯슨으로 복사하면서 덮어써 사라졌다(빌드가 undeclared 로 깨졌다). 여기
 * 값은 모터 쪽 재시도(5회/500ms)를 따라 복원한 것이라 **원래 값과 다를 수 있다.**
 * 담당자가 확인해 고칠 것.
 */
#define TOF_INIT_RETRY_COUNT            5U
#define TOF_INIT_RETRY_DELAY_MS         500U
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
/*
 * 동작 후 드라이버를 켜둘 것인가(유지 토크). 1 이면 켜둔다.
 *
 * 0 이면 포크가 중력으로 내려앉는다 — 2026-08-07 에 높이를 맞춰도 몇 분 뒤 달라졌다.
 * 1 은 대기 전류를 쓰므로 드라이버가 따뜻해진다.
 */
#define STEPPER_MOTOR_HOLD_AFTER_MOTION   1

/*
 * 유지 토크를 **얼마나 오래** 붙잡을 것인가. 0 이면 무기한(종전 동작).
 *
 * ⚠️ 2026-08-09 에 무기한 유지로 **TMC2209 가 만질 수 없을 만큼 뜨거워졌다.**
 * 열보호가 걸리면 포크가 안 내려가고, 그러면 부팅 호밍이 하한 스위치에 영영
 * 닿지 못해 150 초를 갈다 실패한다 — 그 사이 모든 포크 명령이
 * `ESP_ERR_INVALID_STATE` 로 거부된다.
 *
 * 30 초면 "정렬 직전에 높이를 맞춰 두는" 용도는 그대로 살고, 방치했을 때의
 * 상시 통전은 사라진다. 정렬 한 회차가 30 초를 넘으면 늘릴 것.
 *
 * ⚠️ 이건 **증상 완화지 근본 해결이 아니다.** 근본은 드라이버 Vref(전류 제한)가
 * 미니어처 포크에 견줘 과하다는 것이다. Vref 를 낮추면 유지 중에도 안 뜨겁다.
 */
#define STEPPER_MOTOR_HOLD_TIMEOUT_MS     30000U

#define STEPPER_MOTOR_DEFAULT_RATE_SPS    5000U
#define STEPPER_MOTOR_DEFAULT_ACCEL_SPS2  10000U
#define STEPPER_MOTOR_DEFAULT_MOVE_STEPS  19200U
#define STEPPER_MOTOR_HOMING_RATE_SPS     2000U
#define STEPPER_MOTOR_HOMING_ACCEL_SPS2   600U
/*
 * 호밍이 하한 스위치를 못 찾았을 때 포기하는 지점.
 *
 * 300000 스텝은 2000 sps 에서 **150 초**다. 스위치가 안 눌리는 상황(배선 끊김·
 * 드라이버 열보호·포크 걸림)에서 그 150 초를 통째로 갈아넣고서야 실패를 알았다.
 * 그동안 포크 명령은 전부 거부되고 모터는 계속 통전된다 — 과열 상황에서는
 * 이 시간이 그대로 손해다.
 *
 * 60000 = 30 초. 실제 스트로크보다 넉넉하되 실패를 빨리 알리는 값으로 잡았다.
 * ⚠️ 실제 최대 스트로크를 재본 값이 아니다 — 정상 호밍이 이 값에 가깝게
 * 걸리면 늘릴 것.
 */
#define STEPPER_MOTOR_HOMING_MAX_STEPS    60000U
#define STEPPER_MOTOR_HOME_BACKOFF_DELAY_MS 500U
/*
 * 호밍 뒤 하한에서 올라오는 양 = **포크의 기준 높이**다. 미니어처 파렛트는 총높이
 * 14mm(상판 2 + 구멍 10 + 하판 2)라 여기서 몇 mm만 어긋나도 포크가 구멍이 아니라
 * 상판이나 하판을 민다.
 *
 * 1600 -> 1200 -> 7500 -> **6500** (2026-08-07). 앞의 값들은 눈대중이었고, 6500 은 하한에서
 * 스텝을 실어 올려가며 **실물로 맞춘 값**이다(`/fork/command` 에 "UP 7500" 뒤
 * "DOWN 1000").
 *
 * ⚠️ **이 상수는 부팅 호밍에서만 쓰인다.** `/fork/command` 의 `HOME` 은 하한까지만
 * 내려가고 거기서 멈춘다(백오프 없음) — 그래서 손으로 맞출 때는 `HOME` 뒤에
 * "UP 6500" 을 따로 보내야 같은 높이가 된다. 2026-08-07 에 이걸 모르고 HOME 만
 * 걸어놓고 "높이가 맞다" 고 판단했다.
 *
 * ⚠️ 이 상수를 고치기 전까지는 몇 mm 를 옮기려고 매번 재플래시했다. 지금은 프레임에
 * 스텝을 실을 수 있으므로, 다시 맞출 때는 하한(HOME 직후 DOWN)에서 "UP <스텝>" 으로
 * 찾은 뒤 그 숫자를 여기 적는다.
 */
#define STEPPER_MOTOR_HOME_BACKOFF_STEPS  6500U
#define STEPPER_MOTOR_HOME_BACKOFF_RATE_SPS 500U
#define STEPPER_MOTOR_HOME_BACKOFF_ACCEL_SPS2 300U
#define STEPPER_MOTOR_LIMIT_DEBOUNCE_MS   20U

#define STEPPER_MOTOR_MIN_RATE_SPS        20U
#define STEPPER_MOTOR_MAX_RATE_SPS        6000U
#define STEPPER_MOTOR_MAX_ACCEL_SPS2      20000U

/*
 * !! **부팅 시 포크는 움직인다.** (2026-08-06 정정)
 *
 *    종전 이 자리에 "The normal firmware never moves the lift automatically at
 *    boot" 라고 적혀 있었는데 **사실이 아니다** — 바로 아래 STARTUP_HOME_ENABLED 가
 *    1 이고, main.c 가 부팅 때 호밍을 실행한다:
 *
 *      전원 인가 -> 10초 카운트다운 경고 로그 -> 하한 리밋까지 하강
 *                -> 리밋 감지 -> 500ms 대기 -> 1600스텝 상승(백오프)
 *
 *    즉 **전원을 넣으면 10초 뒤 포크가 내려간다.** 포크 아래에 손·화물·파렛트가
 *    있으면 안 된다. 10초 카운트다운은 그걸 치우라고 있는 것이고, 로그에
 *    "keep power cutoff ready" 가 같이 찍힌다.
 *
 * !! 호밍이 실패해도 **재부팅하지 않는다.** 2026-08-09 에 ESP_ERROR_CHECK 를 걷어냈다
 *    (main.c 참조). 드라이버 열보호로 호밍이 실패하자 패닉 -> 리셋 -> 10초 뒤 재호밍이
 *    반복되며 과열을 오히려 키웠기 때문이다. 지금은 드라이버를 끄고 시끄럽게 로그를
 *    남긴 뒤 계속 부팅한다. 원인이 풀리면 /fork/command 의 HOME 으로 다시 건다.
 *
 *    그래도 리밋 스위치가 눌린 채 고장나 부팅 때마다 갈아대는 게 싫으면
 *    STARTUP_HOME_ENABLED 를 0 으로 두고 플래시해 원인을 먼저 본다.
 *
 * STARTUP_TEST_ENABLED 는 호밍 대신 도는 무부하 벤치 테스트다(#elif 라 둘이 동시에
 * 안 돈다). 바퀴를 띄우고 부하를 뗀 상태에서만 켤 것.
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
/*
 * !! 60 -> 100 (2026-08-05, S15P11A304-152). **여기는 허용 봉투이지 운전값이
 *    아니다.** 실제로 쓰는 값은 teleop.yaml 의 max_drive_percent(전진 60 유지) 와
 *    max_drive_percent_reverse(후진 100) 이고, 그쪽은 재플래시 없이 바꾼다.
 *
 *    종전 60 은 "Tele-operation safety limits" 아래에 근거 없이 박혀 있던
 *    보수값이었다. 실측으로 후진이 전진의 19% 밖에 안 나온다는 것이 드러나
 *    (60% 3초에 100mm = 0.033 m/s, 전진은 같은 60% 로 0.178 m/s) 후진만
 *    올릴 수 있게 봉투를 넓혔다.
 *
 *    뒷바퀴 조향차는 후진할 때 뒷바퀴가 **앞장서서**(leading) 바닥을 파고들어
 *    저항이 크다. 전진에서는 끌려오므로(trailing) 훨씬 가볍다. 바닥을 바꿔도
 *    같은 값이 나와, 바닥이 아니라 구조에서 오는 차이로 확인됐다.
 *
 * !! 전진 상한은 **올리지 않는다.** 오늘 실측한 INSERT_SPEED_ACTUAL · 진입 깊이 ·
 *    조향 중립이 전부 전진 60% 기준이라, 여기를 건드리면 그 값들이 무효가 된다.
 */
#define TELEOP_MAX_DRIVE_PERCENT        100
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
/*
 * !! 2026-08-04 오후 재실측: 9600 -> 9400 (S15P11A304-198).
 *
 *    197 의 9600 은 차를 세워두고 **눈으로** 바퀴가 곧은지 본 값이었다. 실제로
 *    주행시켜 재보니 3.0초(535mm)에 **왼쪽으로 40mm** 밀렸다 - 뒷바퀴가 약
 *    2.31도 틀어져 있었다는 뜻이다. 눈대중으로는 2도를 못 본다.
 *
 *      중립   전진거리   좌우편차
 *      9600    535mm      40mm 왼쪽
 *      9400    565mm       9mm 왼쪽
 *      9350    525mm       7mm 왼쪽   (9400 과 사실상 같음 - 노이즈 바닥)
 *
 *    !! 편차는 거리의 **제곱**으로 커진다. 짧게 재면 안 보인다 - 같은 날 315mm
 *       런에서는 8mm 오른쪽이 나와 "계통 편향 없음" 으로 오판했다.
 *
 *    여기 값은 **부팅 직후와 워치독 정지 시의 중립 자세**에만 쓰인다. 주행 중
 *    조향각은 브리지가 매 프레임 명시적으로 보내므로, 재플래시 전에도 주행
 *    자체는 teleop.yaml 값(9400)으로 이미 맞게 돈다.
 */
/*
 * !! 2026-08-05 (S15P11A304-152). 두 가지가 같이 바뀌었다.
 *
 * 1) 중립 9400 -> 9000. SERVO_MIN/MAX_PULSE_US 를 고치면서 같은 cdeg 가 다른 서보
 *    위치를 뜻하게 됐다. 실주행 재실측:
 *
 *      중립   전진거리   좌우편차   뒷바퀴각
 *      9400    580mm      50mm 왼쪽   2.45도   <- 옛 매핑의 값
 *      9200    562mm      18mm 왼쪽   0.94도
 *      9080    605mm      12mm 왼쪽   0.54도
 *      9000    630mm       8mm 왼쪽   0.33도   <- 지금
 *      8960    635mm      28mm 왼쪽   1.15도   <- 지나쳤다
 *
 * 2) MIN/MAX 를 6600~12600 -> 4000~14000 으로 넓혔다. **여기는 안전 봉투이지
 *    운전 범위가 아니다.** 실제로 쓰는 범위는 teleop.yaml 의
 *    steering_min/max_cdeg 이고, 그쪽은 재플래시 없이 바꿀 수 있다. 종전에는 두
 *    값이 붙어 있어 조향 범위를 넓힐 때마다 플래시가 필요했다.
 *
 *    4000~14000 = 서보 40~140도 = 중립 9000 기준 +-50도. 펄스로는 944~2056us 라
 *    MG996R 정격(500~2500) 안이고, SERVO_MIN/MAX_ANGLE_DEG(30~150) 안이다.
 *
 * !! **링키지가 +-50도 를 실제로 가는지는 아직 안 쟀다.** 못 가면 서보가 스톱에
 *    박혀 스톨한다(MG996R 스톨 전류 약 2.5A - 발열·기어 손상). yaml 범위를
 *    **한 단계씩** 넓히며 양 끝에서 소리·떨림을 확인할 것. 봉투를 넓힌 것은
 *    "여기까지 허용" 이지 "여기까지 쓰라" 가 아니다.
 */
#define TELEOP_STEERING_CENTER_CDEG     9000U
#define TELEOP_STEERING_MIN_CDEG        4000U
#define TELEOP_STEERING_MAX_CDEG        14000U

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

/*
 * ⚠️ 1000~2000 → 500~2500 (2026-08-05, S15P11A304-152).
 *
 * servo_set_angle() 은 각도를 180 으로 정규화해 이 두 값 사이에 매핑한다:
 *
 *     pulse = MIN + (angle / 180) * (MAX - MIN)
 *
 * 즉 이 범위가 **180도에 해당하는 펄스 폭**이어야 식이 성립한다. MG996R 은
 * 500~2500us 가 180도이고, 1000~2000us 로는 90~120도밖에 안 돈다. 그래서 종전
 * 값에서는 코드가 믿는 각도의 **절반만 실제로 돌았다.**
 *
 * 실측 (2026-08-05):
 *
 *     중립 9400cdeg = 94도  →  1522us
 *     최대 12200    = 122도 →  1678us      차이 156us
 *
 *   156us 는 MG996R 기준 실제 **14도** 다. 코드는 28도로 알고 있었다.
 *   그 결과가 뒷바퀴 실측 8도, 실효 회전반경 1200~1450mm(설계값 537mm 의 2~3배),
 *   명령 대비 실제 회전 21~26% 다. tan(8)/tan(28)=25% 로 세 숫자가 맞물린다.
 *
 * 고친 뒤 같은 명령이 1544~1856us(312us)가 되어 가동폭이 두 배가 된다.
 * ⚠️ **극단값(500·2500)은 쓰지 않는다** — 명령 구간이 1544~1856 이라 종전 창
 *    (1000~2000) 안에 그대로 들어온다. 스톱에 박을 위험이 없다.
 *
 * ⚠️ **재플래시 후 직진 중립을 다시 잡아야 한다.** 9400 은 옛 매핑에서 실측한
 *    값이고, 새 매핑에서 같은 펄스(1522us)를 내는 것은 **9200** 근처다.
 */
#define SERVO_MIN_PULSE_US              500U
#define SERVO_CENTER_PULSE_US           1500U
#define SERVO_MAX_PULSE_US              2500U

/* Waveshare Motor Driver HAT */
#define MOTOR_HAT_PCA9685_ADDRESS       0x40
#define MOTOR_HAT_PWM_FREQUENCY_HZ      1000.0f

/* Motor B: MB1 / MB2 */
#define MOTOR_B_PWMB_CHANNEL            5
#define MOTOR_B_BIN1_CHANNEL            3
#define MOTOR_B_BIN2_CHANNEL            4

/*
 * Mechanical steering limits
 *
 * !! 2026-08-06: CENTER 를 TELEOP_STEERING_CENTER_CDEG 에서 **유도**하도록 바꿨다.
 *    100.0f 가 그대로 박혀 있었는데, 조향 중립은 그 뒤 10000 -> 9600 -> 9400 ->
 *    9000 으로 세 번 옮겨졌다(197 · 198 · 152). 즉 **부팅·워치독 정지 때 서보가
 *    100도로 가는데, 코드가 믿는 중립은 90도** 인 상태였다.
 *
 *    조용히 틀리는 경로는 이렇다:
 *      1) 워치독 만료 -> motor_apply_safe_stop() 이 servo_set_angle(100도) 실행
 *      2) 같은 함수 뒤에서 applied_command.steering_cdeg = 9000 으로 기록
 *      3) 브리지가 복귀해 중립(9000)을 보내면, task_motor 는 "이미 9000 이다" 로
 *         보고 **servo_set_angle 을 호출하지 않는다**(중복 회피 최적화)
 *      4) 결과: 서보는 100도(좌 10도)에 있는데 양쪽 다 중립이라고 믿는다
 *
 *    08-04 실측에서 뒷바퀴 2.31도 틀어짐이 3초에 40mm 편차였다 — 10도면 그보다
 *    훨씬 크게 휜다. 정렬 중 브리지가 한 번 끊기면 그 뒤 주행이 계속 편향된다.
 *
 * !! **재플래시해야 반영된다.** 주행 중 조향각은 브리지가 매 프레임 보내므로
 *    정상 주행은 지금도 맞게 돌지만, **부팅 직후와 워치독 정지 자세**는 펌웨어
 *    값이 정한다.
 */
#define DRIVE_REAR_STEER_CENTER_ANGLE_DEG       ((float)TELEOP_STEERING_CENTER_CDEG / 100.0f)
/*
 * !! 아래 둘은 **현재 아무 데서도 쓰지 않는다**(2026-08-06 확인 — 참조처 0).
 *    옛 중립 100도 기준의 +-30도 값이라 지금 중립(90도)과 짝이 맞지 않는다.
 *    되살릴 일이 있으면 TELEOP_STEERING_MIN/MAX_CDEG 와 teleop.yaml 의
 *    steering_min/max_cdeg 에서 유도할 것 — 여기에 숫자를 다시 박지 말 것.
 */
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
 *   LPn        A6 / GPIO13    right - gates the I2C comms block
 *   LPn        A7 / GPIO14    left
 *   INT        A0 / GPIO1     right
 *   INT        A1 / GPIO2     left
 *
 * The harness crosses: the left module lands on A7/A1, so the defines below
 * are crossed relative to the pin names. Confirmed 2026-08-06 by covering each
 * module in turn with tools/tof_identify.py --serial, against a boot log that
 * named the image being tested.
 *
 * Nothing in a frame says where a sensor sits -- the index comes from whichever
 * LPn is raised first -- so a crossed harness mirrors the topic, frame, static
 * TF and mask list together, and a mirrored pair cannot be put right by a
 * transform. Two things make this checkable: the mapping line the firmware
 * logs at boot, and covering BOTH modules in turn. Neither alone is enough --
 * without the log a wrong mapping looks like a flash that never happened, and
 * a single-direction cover test was misread three times before both were
 * used together.
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
#define TOF_LEFT_LPN_GPIO               GPIO_NUM_14
#define TOF_RIGHT_LPN_GPIO              GPIO_NUM_13
#define TOF_LEFT_INT_GPIO               GPIO_NUM_2
#define TOF_RIGHT_INT_GPIO              GPIO_NUM_1
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

/*
 * Wheel encoder on the drive motor (JGA25-370 with quadrature hall sensors).
 *
 *   Signal 1   D1 / GPIO43     channel A
 *   Signal 2   D0 / GPIO44     channel B
 *
 * D0 and D1 carry UART0 on a stock Arduino Nano ESP32, but the console here is
 * on USB Serial/JTAG (CONFIG_ESP_CONSOLE_UART_NUM=-1) and the Jetson link is
 * UART1 on GPIO17/18, so both pins are free. Neither is a strapping pin.
 *
 * Counts per wheel revolution is NOT taken from the datasheet. The gear ratio
 * varies across JGA25-370 variants sold under the same name, and a wrong ratio
 * scales every distance the encoder reports without ever looking wrong. Turn
 * the wheel a whole number of revolutions and read the count instead --
 * tools/encoder_scale_check.py does this -- and put the answer in the ROS
 * bridge, which is where counts become metres.
 */
#define ENCODER_A_GPIO                  GPIO_NUM_43     /* D1 */
#define ENCODER_B_GPIO                  GPIO_NUM_44     /* D0 */

/*
 * Half the 16-bit counter range. Wraps are folded into a 32-bit accumulator,
 * so this only sets how often that fold happens, not how far the count can go.
 */
#define ENCODER_PCNT_LIMIT              16000

/*
 * Motor brushes and the stepper put fast spikes on nearby wiring. 1 us is far
 * longer than any of those and far shorter than the ~140 us between edges at
 * full speed, so it cannot swallow a real transition.
 */
#define ENCODER_GLITCH_FILTER_NS        1000

#define ENCODER_PUBLISH_RATE_HZ         50U
#define ENCODER_TASK_STACK_SIZE         3072
#define ENCODER_TASK_PRIORITY           9
#define ENCODER_ERROR_LOG_INTERVAL      100U

/* Four samples of slack at 50 Hz; the count is cumulative so depth is cheap */
#define TELEMETRY_ENCODER_QUEUE_LENGTH  4

#endif
