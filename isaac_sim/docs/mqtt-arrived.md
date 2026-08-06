# MQTT — 지게차 정지(입구 도착) 이벤트

지게차가 입고 위치에 도착해 멈추면 발행한다. AI(비전)가 이 메시지를 받아
용적 측정을 시작하는 트리거로 쓴다.

E 의 `location` 메시지 양식을 따르되 이벤트 필드를 추가했다.

## 토픽

```
forklift/{vehicleId}/arrived
```
- QoS: 1
- Retained: false
- 예: `forklift/SIM-F01/arrived`

## Payload

```json
{
  "vehicleId": "SIM-F01",
  "event": "ARRIVED",
  "location": "INBOUND",
  "position": {
    "x": 1.0,
    "y": 2.0,
    "frameId": "map"
  },
  "heading": 0.0,
  "messageAt": "2026-08-01T10:30:45.304+09:00"
}
```

## 필드

| 필드 | 타입 | 설명 |
|---|---|---|
| `vehicleId` | string | `SIM-F01`(시뮬) / `REAL-F01`(실물). 하이픈 사용 |
| `event` | string | `ARRIVED` 고정 |
| `location` | string | `INBOUND`(입고) / `OUTBOUND`(출고) / `RACK`(적재) |
| `position.x/y` | number | **실물 축척(m)**. 시뮬은 내부 좌표를 1/10 해서 보냄 |
| `position.frameId` | string | `map` |
| `heading` | number | 방향(rad), -π~π, x축 기준 반시계 |
| `messageAt` | string | ISO8601 (+09:00) |

## ⚠️ 확인 필요 (E·AI 합의)

1. **vehicleId** — 시뮬 `SIM-F01` 맞나? (ROS2 내부는 `SIM_F01` 밑줄, MQTT 나갈 때 하이픈 변환)
2. **heading 단위** — rad 인가 deg 인가? E location 양식과 통일
3. **location 값** — `INBOUND`/`OUTBOUND`/`RACK` 이 맞나? AI 가 구분 필요한 위치
4. **AI 응답** — 측정 완료 후 AI 가 무슨 메시지를 되보내는지 (용적 결과)
5. **좌표 축척** — 실물 기준(÷10)으로 보내는 게 맞나

## 흐름

```
지게차 입구 도착 → 정지
   ↓ forklift/SIM-F01/arrived (이 메시지)
AI(비전): 용적 측정 시작
   ↓ (측정 결과 메시지 — 양식 미정, AI 담당)
백엔드: 용적에 맞는 물류 생성 지시
   ↓
지게차: 운반 시작
```
