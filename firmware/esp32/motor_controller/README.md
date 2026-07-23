# ESP32 Forklift Motor Controller

ESP32-S3에서 전륜 DC 구동 모터와 후륜 서보 조향을 제어하는 ESP-IDF 프로젝트입니다. Jetson의 ROS2 텔레옵 노드가 UART로 보낸 최신 명령만 실행하며, 통신이 끊기면 자동 정지합니다.

## 하드웨어 연결

- I2C: SDA GPIO8, SCL GPIO9
- Jetson UART: TX GPIO17, RX GPIO18, GND 공통
- UART 설정: 115200 bps, 8N1, flow control 없음
- 구동 모터: Waveshare Motor Driver HAT의 Motor B
- 조향 서보: 별도 PCA9685 채널 0

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

## 안전 동작

- 부팅 시 DC 모터 정지 및 서보 100도 중앙
- 유효 명령이 500ms 동안 없으면 즉시 정지 및 중앙 복귀
- 모터 또는 서보 제어 오류 시 정지 및 중앙 복귀
- 지게차 전진은 실제 DC 전기 방향 `DC_MOTOR_DIRECTION_REVERSE`에 매핑

## 빌드 및 플래시

```bash
idf.py set-target esp32s3
idf.py build
idf.py -p COM3 flash monitor
```

실제 포트에 맞게 `COM3`를 변경합니다. 최초 실차 시험은 모터 전원을 분리한 UART 시험부터 시작한 뒤, 바퀴를 지면에서 띄워 방향과 안전 정지를 확인합니다.
