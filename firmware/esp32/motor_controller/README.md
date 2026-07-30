# ESP32 Forklift Motor Controller

ESP32-S3에서 전륜 DC 구동 모터, 후륜 서보 조향, 포크 리니어 스텝모터를 제어하고 MPU6500 자이로를 상행 발행하는 ESP-IDF 프로젝트입니다. Jetson의 ROS2 텔레옵 노드가 UART로 보낸 최신 명령만 실행하며, 통신이 끊기면 자동 정지합니다.

보드는 **Geekble nano ESP32-S3**(Arduino Nano ESP32 풋프린트)입니다. GPIO15·16은 헤더에 나오지 않으므로 배선 시 아래 표를 그대로 따릅니다.

## 링크 구조

방향에 따라 물리 링크가 다릅니다.

| 방향 | 링크 | 내용 |
|---|---|---|
| Jetson → ESP32 | UART1 (GPIO17/18, 115200 8N1) | `@CMD` 명령, 20Hz |
| ESP32 → Jetson | UART1 | `@ACK` 응답 |
| ESP32 → Jetson | **네이티브 USB CDC** (`/dev/ttyACM*`) | `@IMU` 100Hz, `@IMS` 1Hz |

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

- `signed_pwm`: `-60`~`60`. 양수는 지게차 전진, 음수는 후진
- `servo_cdeg`: 초기 안전 범위 `8500`~`11500`, 중앙 `10000`
- CRC: `CMD`부터 마지막 필드까지 CRC-16/CCITT-FALSE
- 정상 수신 응답: `@ACK,<seq>,OK*<CRC16>\n`

유효한 명령만 모터 태스크의 최신 명령 큐에 전달하고 watchdog을 갱신합니다. 잘못된 CRC, 잘린 프레임, 과도하게 긴 프레임 또는 범위 밖 명령은 폐기합니다.

## USB 센서 상행 프레임

프레이밍과 CRC는 UART 명령과 동일합니다(`frame_codec.c` 공유). 모든 필드는 10진수입니다.

```text
@IMU,<seq>,<t_us>,<gz_mdps>,<temp_cdeg>*<CRC16>\n     100Hz
@IMS,<who_am_i>,<bias_mdps>,<idle>,<dropped>,<seq>*<CRC16>\n   1Hz
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

자이로 X·Y와 가속도, 자기계는 발행하지 않습니다. `two_d_mode` EKF에서 쓰이지 않고, 모터·스텝퍼 진동이 가속도 신호를 크게 오염시키며 병진 속도는 rf2o가 더 잘 줍니다.

### 바이어스 처리

바이어스가 0.5 dps만 남아도 1분에 약 30도가 틀어집니다. 엔코더가 없어 자이로 의존도가 높은 만큼 두 단계로 처리합니다.

1. **부팅 시** — 포크 호밍이 끝난 뒤 300샘플(3초) 평균. 이 구간에 로봇이 움직이면 바이어스가 오염되어 한쪽으로 계속 틀어집니다
2. **주행 중** — `drive_percent == 0`이 1초 이상 유지될 때만 `bias = 0.999*bias + 0.001*raw`. 차량이 car-like이므로 구동 명령이 0이면 조향각과 무관하게 요레이트가 0입니다. 계수를 낮게 잡은 이유는 관성으로 미끄러지는 구간에 바이어스가 오염되는 것을 막기 위함입니다

### 실패 시 동작

IMU나 USB 호스트가 없어도 주행과 포크는 그대로 동작합니다. `WHO_AM_I` 불일치나 USB 미연결은 로그를 남기고 지나갑니다. USB 링버퍼가 차면 프레임을 통째로 버리고 `dropped`만 올립니다 — 제어 루프는 절대 텔레메트리를 기다리지 않습니다.

## 안전 동작

- 부팅 시 DC 모터 정지 및 서보 100도 중앙
- 유효 명령이 500ms 동안 없으면 즉시 정지 및 중앙 복귀
- 모터 또는 서보 제어 오류 시 정지 및 중앙 복귀
- 지게차 전진은 실제 DC 전기 방향 `DC_MOTOR_DIRECTION_REVERSE`에 매핑

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
