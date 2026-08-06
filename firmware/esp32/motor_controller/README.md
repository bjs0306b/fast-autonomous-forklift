# ESP32 Forklift Motor Controller

ESP32-S3에서 전륜 DC 구동 모터, 후륜 서보 조향, 포크 리니어 스텝모터를 제어하고 MPU6500 자이로를 상행 발행하는 ESP-IDF 프로젝트입니다. Jetson의 ROS2 텔레옵 노드가 UART로 보낸 최신 명령만 실행하며, 통신이 끊기면 자동 정지합니다.

보드는 **Geekble nano ESP32-S3**(Arduino Nano ESP32 풋프린트)입니다. GPIO15·16은 헤더에 나오지 않으므로 배선 시 아래 표를 그대로 따릅니다.

## 링크 구조

방향에 따라 물리 링크가 다릅니다.

| 방향 | 링크 | 내용 |
|---|---|---|
| Jetson → ESP32 | UART1 (GPIO17/18, 115200 8N1) | `@CMD` 명령, 20Hz |
| ESP32 → Jetson | UART1 | `@ACK` 응답 |
| ESP32 → Jetson | **네이티브 USB CDC** (`/dev/ttyACM*`) | `@IMU` 100Hz, `@IMS` 1Hz, `@TOF` 센서당 15Hz, `@ENC` 50Hz |

센서 상행을 USB로 분리한 이유는 대역폭입니다. 나중에 VL53L8CX ToF 2개(8×8 멀티존)가 붙으면 115200 UART로는 들어가지 않습니다. 제어 경로는 UART1에 그대로 남겨 텔레메트리 폭주가 명령 지연을 유발할 여지를 없앱니다.

USB 커넥터가 하나뿐인 보드라 **콘솔도 같은 USB로 나갑니다.** `ESP_LOG` 줄과 프레임이 섞이지만, `usb_serial_jtag_write_bytes()`가 전부-아니면-전무로 기록하므로 프레임이 중간에 잘리지 않습니다. Jetson 파서는 `@`로 시작하지 않는 줄을 버립니다. `idf.py monitor`와 ROS2 노드는 같은 장치를 쓰므로 동시에 열 수 없습니다.

## 하드웨어 연결

- I2C0: SDA GPIO8(D5), SCL GPIO9(D6) — Motor HAT + 서보 PCA9685
- Jetson UART1: TX GPIO17(D8), RX GPIO18(D9), GND 공통
- UART 설정: 115200 bps, 8N1, flow control 없음
- 구동 모터: Waveshare Motor Driver HAT의 Motor B
- 조향 서보: 별도 PCA9685 채널 0
- 포크 스텝퍼(TMC2209): STEP GPIO4, DIR GPIO5, EN GPIO6, 하한 스위치 GPIO7

### IMU (MPU6500 / MPU9250) — SPI2

```text
MPU6500      보드 표기   GPIO
---------    ---------   ----
SCK      <--  D13         48
SDI/MOSI <--  D11         38
SDO/MISO -->  D12         47
NCS      <--  D10         21
INT      -->  D7          10
VCC      <--  3V3
GND      <->  GND
```

- 레지스터 접근 1MHz, 데이터 버스트 8MHz. D13(GPIO48)은 온보드 LED와 공유되므로 `WHO_AM_I`가 간헐적으로 실패하면 `IMU_SPI_DATA_CLOCK_HZ`를 4MHz로 낮춥니다
- A4·A5(GPIO11·12)는 나중 ToF용 I2C1로 비워 둡니다
- 자기계(AK8963)는 연결하지 않습니다. 모터 코일·스텝퍼·리니어 스크류가 자기장을 교란하고, 절대 방위는 AMCL이 더 정확하게 잡습니다
- **장착**: 회전 중심에 가깝게, 볼트 또는 강한 접착으로 고정합니다. 양면테이프는 진동이 노이즈로 들어옵니다. 스텝퍼 드라이버·DC-DC 컨버터에서 최대한 떼고, SPI 케이블을 스텝퍼 케이블과 나란히 배선하지 않습니다
- Z축이 위(+)를 향하게 장착합니다. 부호가 반대로 나오면 펌웨어를 고치지 말고 브리지의 `gyro_z_sign` 파라미터로 처리합니다

ESP32-S3를 USB-C로 공급할 때는 Jetson 및 모터 HAT과 GND만 공통으로 연결합니다. 서로 다른 5V 또는 3.3V 전원 출력을 직접 연결하지 않습니다.

## UART 명령

```text
@CMD,<seq>,<signed_pwm>,<servo_cdeg>*<CRC16>\n
```

- `signed_pwm`: **봉투는 `-100`~`100`**(`TELEOP_MAX_DRIVE_PERCENT`). 양수 전진, 음수 후진.
  운전값은 `teleop.yaml` 이 정한다 — **전진 60 · 후진 100**(꺾은 채 후진은 저항이 커
  60% 로는 못 움직인다, 2026-08-05).
- `servo_cdeg`: **봉투는 `4000`~`14000`**(`TELEOP_STEERING_MIN/MAX_CDEG`).
  운전값은 `teleop.yaml` — **중립 `9000` · 좌우 `5400`~`12600`**(±36°).

> ⚠️ **봉투(펌웨어·`protocol.py`)와 운전값(`teleop.yaml`)은 다르다.** 봉투는 "여기까지
> 허용", 운전값은 "실제로 쓰는 범위"다. 2026-08-05 이전에는 같은 한계가 네 곳에 서로
> 다른 값으로 박혀 있었고, `protocol.py` 하드코딩이 범위 밖 명령에 `ValueError` 를 던져
> **브리지 프로세스를 죽이고 있었다**(증상은 "조향이 아예 안 움직임"). 이제 분리돼
> 있어 운전 범위를 바꿔도 **재플래시가 필요 없다.**
>
> ⚠️ 종전 이 문서에 적혀 있던 `8500`~`11500` · 중앙 `10000` 은 **2026-08-04 이전 값**이다.
> 중립은 10000 → 9600 → 9400 → **9000** 으로 세 번 바뀌었다(197 · 198 · 152).
> 현재 값은 언제나 `ros2_ws/src/forklift_teleop/config/teleop.yaml` 을 본다.
- CRC: `CMD`부터 마지막 필드까지 CRC-16/CCITT-FALSE
- 응답은 **두 가지**입니다 (2026-08-06 확인):
  - `@ACK,<seq>,OK*<CRC16>\n` — 명령이 모터 태스크 큐에 들어갔다
  - `@ACK,<seq>,ERROR*<CRC16>\n` — **프레임은 멀쩡한데 실행을 거부했다**(큐 제출 실패 등)

⚠️ **프레임 자체가 깨졌으면 아무 응답도 없습니다.** 잘못된 CRC·잘린 프레임·과도하게 긴
프레임·범위 밖 값은 시퀀스를 읽을 수 없거나 신뢰할 수 없으므로 **조용히 버립니다.**
즉 "응답이 없다"와 "ERROR 응답"은 원인이 다릅니다 — 전자는 배선·전송 문제, 후자는
펌웨어가 명령을 받고도 못 한 것입니다.

⚠️ **`ERROR` 응답에 재전송은 없습니다.** 브리지(`uart_teleop_bridge`)는 ROS 로그에
`warning` 을 남기고 넘어갑니다.

- **구동·조향(`@CMD`)** 은 20Hz 로 계속 나가므로 한 번 거부돼도 다음 프레임이 덮습니다.
- **포크(`@LIFT`)** 는 **1회성**입니다. 거부되면 그 동작은 **그대로 사라지고** 경고 로그만
  남습니다 — 포크가 안 움직이면 `ros2 topic echo` 가 아니라 **브리지 로그**를 볼 것.

유효한 명령만 모터 태스크의 최신 명령 큐에 전달하고 watchdog을 갱신합니다.

### 포크 원점·초기화 명령

```text
@LIFT,<seq>,UP|DOWN|HOME|INITIALIZE|STOP*<CRC16>\n
```

- `HOME`: 포크를 **하한 홈 스위치가 눌릴 때까지** 아래로 이동한다. 최대
  `STEPPER_MOTOR_HOMING_MAX_STEPS` 안에 스위치가 눌리지 않으면 `ERROR`로 끝난다.
- `INITIALIZE`: 주행 모터를 정지하고 조향 서보를 **중앙**으로 돌린 뒤 `HOME`을 실행한다.
  중앙 각도는 `TELEOP_STEERING_CENTER_CDEG`를 그대로 쓴다(현재 **90°**) — 코드가 상수를
  참조하므로 중립을 옮기면 따라온다. 종전 이 줄의 "100°"는 옛 중립이다(2026-08-06 정정).
- 하한 스위치가 눌리면 즉시 정지한 뒤 500 ms 후 소폭 상승해 스위치를 해제하고 `DONE`을 보고한다.
- 상한 스위치는 사용하지 않는다. 상승 이동 범위는 명령 step 수와 기구적 안전 범위로 관리한다.

## USB 센서 상행 프레임

프레이밍과 CRC는 UART 명령과 동일합니다(`frame_codec.c` 공유). 모든 필드는 10진수입니다.

```text
@IMU,<seq>,<t_us>,<gz_mdps>,<temp_cdeg>*<CRC16>\n     100Hz
@IMS,<who_am_i>,<bias_mdps>,<idle>,<dropped>,<seq>*<CRC16>\n   1Hz
@TOF,<sensor_id>,<seq>,<t_us>,<256자리 16진수>*<CRC16>\n   센서당 15Hz
@ENC,<seq>,<t_us>,<count>,<read_errors>*<CRC16>\n     50Hz
```

| 필드 | 의미 |
|---|---|
| `seq` | uint32 샘플 카운터. 건너뛰면 드롭 |
| `t_us` | 데이터 준비 인터럽트 시점의 `esp_timer_get_time()` (µs). Jetson이 ROS 시각으로 변환 |
| `gz_mdps` | 바이어스 보정된 자이로 Z, milli-dps. ±250dps·131 LSB/dps → 분해능 약 7.6 mdps |
| `temp_cdeg` | 칩 온도 centi-°C. 바이어스 온도 드리프트 진단용 |
| `who_am_i` | 0x70=MPU6500, 0x71=MPU9250, 0x73=MPU9255 (10진수로 전송) |
| `bias_mdps` | 현재 자이로 Z 바이어스 추정값 |
| `idle` | 정지 감지 상태(1이면 바이어스 갱신 중) |
| `dropped` | 누적 드롭 수(큐 만원 또는 USB 링버퍼 만원) |
| `sensor_id` | 0=좌, 1=우 |
| 존 4자리 | 거리 3자리(mm, 0~4095) + 타깃상태 1자리, 구분자 없음. row-major, row 0 = 상단 |
| `count` | 휠 엔코더 PCNT 누적 카운트(부호 있음). 하드웨어 카운터가 ±16000에서 감기므로 **차분으로 쓴다** |
| `read_errors` | 엔코더 읽기 실패 누적. 100회마다 한 번만 로그를 남긴다 |

`@TOF`는 존당 고정폭이라 길이가 결정적이고 필드 분해가 필요 없습니다. 센서당 약
290바이트 × 15Hz × 2 = **8.7KB/s**로, UART 115200이었다면 불가능한 양입니다.

### 전면 ToF (VL53L8CX ×2)

I2C1(A4·A5)에 두 개를 답니다. **I2C0은 Motor HAT·서보 전용으로 남깁니다** — 8×8
블록 전송이 서보 갱신 뒤에 줄을 서면 샘플 간격이 무너집니다.

**배선** (SATEL 계열 브레이크아웃 기준)

```text
VL53L8CX      보드 표기   GPIO
MOSI_SDA  <->  A4          11    풀업 필요 (아래)
MCLK_SCL  <->  A5          12    풀업 필요
LPn       <--  A6 / A7     13/14 좌 / 우
INT       -->  A0 / A1     1/2   좌 / 우
SPI_I2C_N <--  GND         -     I2C 모드 선택
NCS       <--  3V3         -     SPI 비활성
MISO           미연결      -     SPI 전용
```

**`SPI_I2C_N`을 반드시 GND에 내리세요 — 센서마다 각각.** 이 핀이 플로팅이면 센서가
I2C와 SPI 모드를 오가면서 증상이 실행마다 바뀝니다: 쓰기는 ACK를 받는데 주소가 남지
않고, 스캔과 프로브가 서로 다른 답을 내고, 옆 센서를 깨우면 멀쩡하던 센서가 사라집니다.
브링업에서 가장 오래 걸린 함정이었습니다.

풀업은 현재 배선 길이에서 **불필요**합니다. 내부 풀업만으로 400kHz에서 84KB 펌웨어
업로드가 센서당 2.4초에 통과합니다. 케이블을 길게 뽑아 불안정해지면 그때
SDA→3.3V, SCL→3.3V에 2k를 **버스 전체에 한 쌍**만 답니다(센서마다 아님).
브레이크아웃에 이미 풀업이 있으면 추가하지 마세요 — 병렬로 1.1kΩ 아래가 되면
VL53L8CX의 싱크 전류 한계를 넘습니다.

**주소 분리** — 두 센서 모두 기본 0x29라 순차 초기화가 필요합니다. 이 보드에는
PWREN이 없어 소프트웨어로 전원을 끊을 수 없고, **ESP32만 재부팅되면 센서는 이전
실행의 주소를 그대로 갖고 있습니다.** 그래서 리셋 대신 프로브로 해결합니다.

1. 둘 다 LPn을 내려 침묵
2. 한쪽만 LPn을 올림 → 아직 기본 주소인 건 그 센서 하나뿐
3. 목표 주소부터 프로브 → 어느 쪽이 나오든 원하는 주소로 이설
4. **깨운 채로 두고** 다음 센서를 올려 반복
5. 전부 깨어난 뒤 초기화

**LPn을 다시 내리면 안 됩니다.** 배정한 주소는 통신 블록에 휘발성으로 저장되는데
LPn이 그 블록의 전원을 내리므로, 침묵시키는 순간 **주소가 0x29로 되돌아갑니다.**
그래서 센서를 하나씩 깨워 누적시킵니다 — 매번 기본 주소에 남아 있는 건 방금 깨운
센서 하나뿐이라 충돌이 없습니다.

부팅 시 두 센서 모두 기본 주소에서 시작하므로 재부팅을 반복해도 안전합니다.

**함정**: `vl53l8cx_set_i2c_address()`는 **쓰면 안 됩니다.** 이 함수는

```c
WrByte(0x7fff, 0x00);            // 구 주소 — 성공
WrByte(0x04, new_addr >> 1);     // 여기서 센서가 이사함
platform.address = i2c_address;  // 구조체만 갱신
WrByte(0x7fff, 0x02);            // 구 핸들로 전송 → NACK
```

세 번째 쓰기를 이미 이사한 센서의 옛 주소로 보냅니다. 전송은 `platform.address`가
아니라 `platform.handle`로 나가는데, i2c_master는 등록 시점 주소에 핸들을 고정하기
때문입니다. 실패할 뿐 아니라 **페이지 레지스터가 잘못된 상태로 남습니다.**

`vl53l8cx_pair.c`의 `tof_change_address()`가 같은 세 레지스터를 쓰되 **센서가
이사한 직후 핸들을 재등록**합니다. 컴포넌트 예제에서도 이 호출이 주석 처리돼
있어 미검증으로 보입니다.

부팅 시 센서당 펌웨어 블롭이 I2C로 올라가 400kHz에서 1~2초 걸립니다. 그래서 ToF는
호밍·IMU 뒤 **마지막에** 기동하고, 실패해도 주행은 계속됩니다.

자이로 X·Y와 가속도, 자기계는 발행하지 않습니다. `two_d_mode` EKF에서 쓰이지 않고, 모터·스텝퍼 진동이 가속도 신호를 크게 오염시키며 병진 속도는 rf2o가 더 잘 줍니다.

### 바이어스 처리

바이어스가 0.5 dps만 남아도 1분에 약 30도가 틀어집니다. 요(yaw)는 자이로 말고 대안이 없으므로 두 단계로 처리합니다.

> ⚠️ 종전 이 자리에 *"엔코더가 없어"* 라고 적혀 있었는데 **지금은 휠 엔코더가 있습니다**
> (`@ENC` 50Hz, S15P11A304-109 계열에서 추가). 다만 엔코더는 **주행 거리·속도**를 주고
> **요 각도는 못 줍니다** — 자이로 의존도가 높다는 결론 자체는 그대로입니다.

1. **부팅 시** — 포크 호밍이 끝난 뒤 300샘플(3초) 평균. 이 구간에 로봇이 움직이면 바이어스가 오염되어 한쪽으로 계속 틀어집니다
2. **주행 중** — 게이트 **두 개를 모두** 통과할 때만 `bias = 0.999*bias + 0.001*raw`
   - `drive_percent == 0`이 1초 이상 유지 (차량이 car-like이므로 구동 명령이 0이면 조향각과 무관하게 요레이트가 0)
   - **자이로 자체가 정지로 읽힘** — `|raw - bias| < 2 dps`

두 번째 게이트가 없으면 벤치에서 조용히 망가집니다. `s_drive_idle`은 `true`로 시작해 UART 명령이 와야 바뀌므로, 명령이 없는 벤치 리그에서는 영원히 "정지 중"이 되고 필터가 **실제 회전을 바이어스로 흡수**합니다. `alpha=0.001` @ 100Hz는 시정수 10초라, 손으로 90°씩 네 번 돌린 시험이 54/22/22/7도로 감쇠해 읽혔습니다. 계수를 낮게 잡은 것만으로는 부족합니다.

증상이 헷갈리는 이유는 **감쇠량이 회전에 걸린 시간에 비례**한다는 점입니다. 빨리 돌리면 덜 먹히므로 스케일 오차처럼 보이지만, 감도 상수로는 절대 고쳐지지 않습니다(모든 구간이 같은 비율로 바뀔 뿐입니다).

### 실패 시 동작

IMU나 USB 호스트가 없어도 주행과 포크는 그대로 동작합니다. `WHO_AM_I` 불일치나 USB 미연결은 로그를 남기고 지나갑니다. USB 링버퍼가 차면 프레임을 통째로 버리고 `dropped`만 올립니다 — 제어 루프는 절대 텔레메트리를 기다리지 않습니다.

## 안전 동작

- 부팅 시 DC 모터 정지 및 **서보 중앙 복귀**
  - 중앙 각도는 `TELEOP_STEERING_CENTER_CDEG`에서 유도한다(현재 **90°**). 종전 이 문서가
    "100도"라고 적고 있었는데, 조향 중립이 10000 → 9600 → 9400 → **9000**으로 세 번
    바뀌는 동안 펌웨어 상수만 안 따라온 상태였다(2026-08-06 수정, S15P11A304-203).
    **재플래시해야 반영된다.**
- 유효 명령이 500ms 동안 없으면 즉시 정지 및 중앙 복귀
- 모터 또는 서보 제어 오류 시 정지 및 중앙 복귀
- 지게차 전진은 실제 DC 전기 방향 `DC_MOTOR_DIRECTION_REVERSE`에 매핑

### 🔴 부팅 시 포크가 자동으로 움직인다

`STEPPER_MOTOR_STARTUP_HOME_ENABLED`가 **1**이라 전원을 넣으면 호밍이 돈다.

```
전원 인가 → 10초 카운트다운(경고 로그) → 하한 리밋까지 하강
         → 리밋 감지 → 500ms 대기 → 1600스텝 상승(백오프)
```

⚠️ **포크 아래에 손·화물·파렛트를 두지 말 것.** 10초 카운트다운은 치우라고 있는
시간이고, 로그에 `keep power cutoff ready`가 같이 찍힌다.

⚠️ **호밍이 실패하면 `ESP_ERROR_CHECK`가 MCU를 abort(재부팅)시킨다.** 리밋 스위치가
눌린 채 고장나면 **부팅 → 호밍 실패 → 재부팅 루프**가 된다. 그때는
`STEPPER_MOTOR_STARTUP_HOME_ENABLED`를 0으로 두고 플래시해 원인부터 본다.

## 빌드 및 플래시

```bash
. ~/.espressif/v5.5.5/esp-idf/export.sh
idf.py set-target esp32s3
idf.py build
idf.py -p /dev/ttyACM0 flash monitor
```

실제 포트에 맞게 변경합니다. 최초 실차 시험은 모터 전원을 분리한 UART 시험부터 시작한 뒤, 바퀴를 지면에서 띄워 방향과 안전 정지를 확인합니다.

## IMU 검증 순서

순서대로 진행하고 각 단계를 통과한 뒤 다음으로 갑니다. EKF를 먼저 붙이고 디버깅하면 원인을 찾을 수 없습니다.

| 단계 | 확인 | 통과 기준 |
|---|---|---|
| 0 SPI | `idf.py monitor` | `WHO_AM_I 0x70/0x71/0x73`. 실패 시 배선·CS 극성·데이터 클럭 4MHz |
| 0.5 링크 | `cat /dev/ttyACM0` | `@IMU,...*CRC` 줄이 흐름 (로그와 섞여도 프레임은 온전) |
| 1 부호 | `ros2 topic echo /imu/data --field angular_velocity.z` | 위에서 봤을 때 반시계 = 양수 (REP-103) |
| 2 바이어스 | `ros2 topic hz /imu/data` | 100Hz 근처, 정지 시 ±0.005 rad/s 이내 |
| 3 스케일 | 제자리 360° 회전 후 적분 | 6.283 rad ±5% (6.0~6.6) |

Jetson 측 절차는 [`ros2_ws/src/forklift_teleop/README.md`](../../../ros2_ws/src/forklift_teleop/README.md)를 참고합니다.

## 무부하 통합 시나리오 시연

S15P11A304-100 무인 이동·운반 MVP는 차량을 안정적인 받침대에 올려 구동 바퀴를 공중에 띄운 무부하 상태에서 검증한다.

검증 순서는 다음과 같다.

1. 전진 명령에 따라 DC 구동 바퀴가 전진 방향으로 회전한다.
2. 정지 명령 후 바퀴가 완전히 정지한다.
3. 차량 정지 후 포크가 상승한다.
4. 포크 상태가 `RUNNING`에서 `DONE`으로 전환된다.
5. 시연 후 포크를 하단 리밋까지 내리고 자동 백오프 상태로 복귀한다.

공중 부양 상태에서는 차체와 LiDAR가 이동하지 않으므로 RF2O `/odom`이 전진 거리를 생성하지 않는다. 따라서 이 시연에는 Nav2, AMCL 및 LiDAR odometry를 사용하지 않는다. 공중 부양 상태에서 Nav2를 실행하면 progress checker의 `Failed to make progress`로 중단되는 것이 정상이다.

### 안전 조건

- 차량을 흔들리지 않는 받침대에 고정한다.
- 구동 바퀴, 포크, 체인 및 스테퍼 모터 주변에서 사람을 물린다.
- 포크에는 적재물을 올리지 않는다.
- 비상 전원 차단과 `STOP` 명령을 준비한다.
- 모든 터미널에서 `ROS_DOMAIN_ID=100`을 사용한다.

### 터미널 1: UART 브리지

```bash
export ROS_DOMAIN_ID=100
source /opt/ros/humble/setup.bash
source ~/S15P11A304/ros2_ws/install/setup.bash

ros2 run forklift_teleop uart_teleop_bridge --ros-args \
  --params-file ~/S15P11A304/ros2_ws/src/forklift_teleop/config/teleop.yaml \
  --log-level uart_teleop_bridge:=debug
```

`ACK sequence=... status=OK`가 계속 수신되는지 확인한다.

### 터미널 2: 포크 상태 감시

```bash
export ROS_DOMAIN_ID=100
source /opt/ros/humble/setup.bash
source ~/S15P11A304/ros2_ws/install/setup.bash

ros2 topic echo /fork/status
```

### 터미널 3: 자동 시연

```bash
export ROS_DOMAIN_ID=100
source /opt/ros/humble/setup.bash
source ~/S15P11A304/ros2_ws/install/setup.bash

echo "[1/3] Simulated forward travel"

timeout 3 ros2 topic pub -r 20 \
  /cmd_vel geometry_msgs/msg/Twist \
  "{linear: {x: 0.10}, angular: {z: 0.0}}"

echo "[2/3] Drive stop"

ros2 topic pub --once \
  /cmd_vel geometry_msgs/msg/Twist \
  "{linear: {x: 0.0}, angular: {z: 0.0}}"

sleep 1

echo "[3/3] Fork lift"

ros2 topic pub --once \
  /fork/command std_msgs/msg/String \
  "{data: UP}"
```

### 비상 정지

```bash
ros2 topic pub --once \
  /cmd_vel geometry_msgs/msg/Twist \
  "{linear: {x: 0.0}, angular: {z: 0.0}}"

ros2 topic pub --once \
  /fork/command std_msgs/msg/String \
  "{data: STOP}"
```

### 시연 종료 후 포크 호밍·초기화

포크만 하한 원점으로 보낼 때는 `HOME`을 사용한다. 이 명령은 정해진 이동량이
아니라 하한 홈 스위치가 눌릴 때까지 계속 내려간다.

```bash
ros2 topic pub --once \
  /fork/command std_msgs/msg/String \
  "{data: HOME}"
```

운행 시작 전 조향까지 정면으로 맞추려면 `INITIALIZE`를 보낸다. 완료 상태가 올
때까지 ROS2 브리지는 주행 명령을 0·중앙 조향으로 유지한다.

```bash
ros2 topic pub --once \
  /fork/command std_msgs/msg/String \
  "{data: INITIALIZE}"
```

하단 리밋 접촉 후 정지하고, 500ms 뒤 조금 상승해 스위치가 해제된 상태에서 `DONE`이 나오면 초기화 완료다.

### 검증 결과와 범위

S15P11A304-100에서 무부하 공중 부양 상태의 `전진 구동 → 정지 → 포크 상승` 자동 순차 동작과 포크의 `RUNNING → DONE` 상태 전환을 실차 하드웨어로 확인했다.

본 시험은 차체 이동을 제외한 무부하 벤치 통합 검증이다. 실제 지면에서의 위치 이동, AMCL 수렴 및 Nav2 경로 추종은 후속 실차 검증 범위다.
