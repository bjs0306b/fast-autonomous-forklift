# S15P11A304-80 UART 텔레옵 검증 결과

- 검증일: 2026-07-24
- 대상: Jetson Orin Nano(JetPack 6.2, ROS 2 Humble) ↔ ESP32-S3
- 기준 커밋: `a15c707`
- UART: `/dev/ttyTHS1`, 115200 bps, 8N1, flow control 없음

## 연결

| Jetson Orin Nano | ESP32-S3 |
|---|---|
| Pin 8 TX | GPIO18 RX |
| Pin 10 RX | GPIO17 TX |
| Pin 6 GND | GND |

UART의 TX와 RX는 서로 교차 연결한다.

## 검증 결과

- [x] ESP-IDF `fullclean build` 성공
- [x] Python 단위 테스트 13개 통과
- [x] Jetson Pin 8-TX와 Pin 10-RX UART 루프백 성공
- [x] Jetson↔ESP32 CRC-16/CCITT-FALSE 명령 및 ACK 왕복 통신 성공
- [x] 50% PWM 지게차 기준 전진 구동 및 정지 성공
- [x] `/cmd_vel` Publisher 1개와 Subscriber 1개 연결 확인
- [x] 키보드 텔레옵 전진·후진·좌우 후륜 조향 및 정지 확인
- [x] 명령 중단 후 500ms watchdog 자동 정지 확인
- [x] 제자리 회전 명령 차단 확인

## CRC/ACK 확인

정지·중앙 조향 명령을 sequence 1~3으로 전송했으며 모든 프레임에서 정상 ACK를
수신했다.

```text
TX: @CMD,1,0,10000*D07C
RX: @ACK,1,OK*E2D9

TX: @CMD,2,0,10000*FD38
RX: @ACK,2,OK*7905

TX: @CMD,3,0,10000*161B
RX: @ACK,3,OK*0FB1
```

## 50% PWM 확인

`drive_percent=50`, `steering_cdeg=10000` 명령을 10Hz로 1초간 전송했다.
sequence 1~10의 ACK를 모두 수신했고 DC 모터가 지게차 기준 전진 방향으로
회전했다. sequence 11의 정지 명령 ACK 수신 직후 모터가 실제로 정지했다.

## ROS 2 및 텔레옵 확인

```text
/teleop_twist_keyboard
/uart_teleop_bridge

/cmd_vel Publisher count: 1
/cmd_vel Subscription count: 1
```

바퀴를 지면에서 띄운 상태에서 `i`, `,`, `u`, `o`, `m`, `.`, `k` 명령의
전진·후진·좌우 조향·정지를 확인했다. `j`, `l` 제자리 회전 요청은 구동하지
않았고, 키 입력 중단 후 500ms 이내에 모터가 자동 정지했다.

실기 검증에서는 초기 제한값으로 PWM 50~60%, 서보 95~105도를 사용했다.
