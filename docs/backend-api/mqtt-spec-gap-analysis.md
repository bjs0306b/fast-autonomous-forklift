# F팀 MQTT 규격 대비 구현 범위 분석 — 백엔드 · 프론트엔드

작성 2026-08-09 · 백엔드(E팀)
대상 `isaac_sim/docs/backend-mqtt-guide.md` · `backend-control-impl.md`
**분석만 했고 구현하지 않았다.**

---

## 0. 결론 먼저

규격이 요구하는 것을 **통신 계층**과 **관제 로직**으로 나누면 그림이 분명해진다.

| 계층 | 상태 |
|---|---|
| **통신 계층** (접속·토픽·페이로드·수신) | ✅ **거의 완성** |
| **관제 로직** (교통 규칙·주기·운행 제어) | 🔴 **전무** |

F팀이 "옮겨야 할 것은 판단 로직뿐이고 명령 전달 수단은 이미 전부 열려 있다"고 쓴 것이
정확하다. 백엔드는 **받고 보낼 준비는 끝났고, 무엇을 보낼지 정하는 부분이 없다.**

프론트엔드는 **모니터링은 되지만 조작이 비상정지 하나뿐**이다.

---

## 1. 백엔드 — 이미 되는 것 ✅

| 규격 항목 | 근거 |
|---|---|
| TLS 8883 + 계정 인증 | `MqttConfig` · `application-local.yml:32` |
| hostname 검증 끄기 (CN이 IP) | `ssl-hostname-verification-enabled: false` |
| `telemetry` 구독 | `MqttConfig:174` |
| `event` 구독 | `MqttConfig:175` |
| `forklift/{id}/arrived` 구독 | `MqttConfig:176` |
| telemetry 전 필드 파싱 | `IsaacVehicleTelemetryMessage` — `cargo` 포함 |
| `state` 신규값 `LOADING`/`UNLOADING` | `VehicleStatus:35-36` |
| 차량 ID 별칭 `sim02/sim03/fk01` | `application-local.yml:71-74` |
| `task` 발행 | `MqttTopics:11` |
| `control` 발행 | `MqttTopics:12` |
| `cargo` 발행 (토픽) | `MqttTopics:13` |
| `fast/v1/control/all` | `MqttTopics:14` |
| 미등록 차량 자동 등록 | `VehicleAutoRegistrar` |
| 랙 좌표 24칸 | `storage_slot` — 로컬·EC2 반영 완료 |

**F팀이 §0에서 "깨짐"으로 표시한 두 건은 이미 대응돼 있다.**
차량 ID는 별칭표에 네 개가 다 있고, 좌표는 애초에 변환하지 않는다.

---

## 2. 백엔드 — 없는 것

### 2.1 통신 계층에서 빠진 것 (작음)

| # | 항목 | 규격 | 현재 | 크기 |
|---|---|---|---|---|
| B1 | `cargo` 액션 | `align_bay` / `place_rack` / `drop` | 토픽만 있고 **액션 미구현** | 소 |
| B2 | `CANCEL_TASK` | control 4종 중 하나 | `VehicleCommandType`에 없음 | 소 |
| B3 | `fast/v1/sim/camera` | 시뮬 시점 전환 | 없음 | 소 |
| B4 | 3초 LOST 판정 | `ts` 3초 경과 시 LOST | `offline-check-enabled: false`, timeout 10초 | 소 |
| B5 | `task` pickup/dropoff 형식 | §4.2 2지점 형식 | 확인 필요 | 소 |

**B4가 관제 로직의 전제다.** 규격은 "LOST인 차량에 목표를 보내지 마라"고 하는데,
지금은 판정 자체가 꺼져 있다.

### 2.2 관제 로직 — 전부 없음 (큼)

| # | 항목 | 규격 위치 | 상태 |
|---|---|---|---|
| B6 | **호장(arc-length) 투영** | impl §2 | 🔴 없음 |
| B7 | **순환로 모델** (모서리 4개, 둘레 67.0) | impl §1 | 🔴 없음 |
| B8 | 규칙 0 — 합류 순서 (실물 우선, 3초 간격) | impl §4 | 🔴 없음 |
| B9 | 규칙 1 — 일방통행 (다음 모서리) | impl §4 | 🔴 없음 |
| B10 | 규칙 2 — 차간 유지 (HOLD 9.0 / SAFE 6.0) | impl §4 | ⚠️ **있으나 유클리드** |
| B11 | 규칙 3 — 바이 예약 (한 대만, 나머지는 선회) | impl §4 | 🔴 없음 |
| B12 | 규칙 4 — 정체 감시 (20초) | impl §4 | 🔴 없음 |
| B13 | 규칙 5 — 관제 정지 존중 | impl §4 | 🔴 없음 |
| B14 | **주기 상태기계** 6단계 | impl §5 | 🔴 없음 |
| B15 | 랙 배정표 (차량별 A/B) | impl §6 | 🔴 없음 |
| B16 | 0.5초 tick 스케줄러 | impl §8 | 🔴 없음 |
| B17 | `OperationState` (IDLE/RUNNING/PAUSED/ESTOPPED) | impl §0.6 | 🔴 없음 |
| B18 | `/api/operation/*` 5개 | impl §0.6 | 🔴 없음 |
| B19 | `/api/vehicles/{id}/hold` `resume` | impl §0.6 | 🔴 없음 |
| B20 | `GET /api/vehicles` 확장 응답 | impl §0.6 | ⚠️ 있으나 필드 부족 |
| B21 | WebSocket 실시간 푸시 (0.5~1초) | impl §0.6 | ⚠️ 있으나 내용 부족 |

### 2.3 🔴 B10 — 기존 구현이 규격이 금지한 방식이다

FR-502-1a로 만든 `traffic` 패키지는 **유클리드 거리**로 차간을 잰다
(`CollisionPredictor.distance` / `currentGap` / `predictedGap`).

규격 §10 "흔한 함정" 첫 줄이 정확히 이것이다.

> | 유클리드 거리로 차간 판정 | 반대편 통로 차를 앞차로 착각해 엉뚱하게 멈춤 |

왼쪽 통로(x=5)와 오른쪽 통로(x=15.5)는 직선 **10.5**, 경로상 **약 33**이다.
`HOLD_DIST`가 9.0이라 정통로에서는 간신히 안 걸리지만, 모서리 근처에서는 오판한다.

**다만 전부 버릴 필요는 없다.** `predictPosition`·`isClosing`·홀드/해제 이력·
히스테리시스는 그대로 쓸 수 있고, **거리 계산부만 `Track.gap`으로 교체**하면 된다.

---

## 3. 프론트엔드 — 이미 되는 것 ✅

| 항목 | 근거 |
|---|---|
| 차량 위치 미니맵 | `app/page.tsx` |
| `state` 표시 (`LOADING`/`UNLOADING`/`HOLDING` 포함) | `lib/vehicleStatus.ts` |
| 전체 비상정지 바 | `GlobalEmergencyStopBar.tsx` |
| 차량별 비상정지 | `VehicleDetailPanel.tsx` |
| 명령 결과 알림 | `CommandNotice.tsx` |
| AI 측정 영상 + 검출 상자 | `AiMeasurementVideo.tsx` |
| WebSocket 수신 (STOMP) | 대시보드 갱신 |

---

## 4. 프론트엔드 — 없는 것

| # | 항목 | 규격 | 크기 |
|---|---|---|---|
| F1 | **운행 시작 버튼** | impl §0.6 | 소 |
| F2 | **일시정지 / 재개 / 종료 버튼** | impl §0.6 | 소 |
| F3 | **`OperationState` 표시** (IDLE/RUNNING/PAUSED/ESTOPPED) | impl §0.6 | 소 |
| F4 | 버튼 활성 조건 (상태별 노출) | impl §0.6 표 | 소 |
| F5 | 차량별 **HOLD / RESUME** | impl §0.6 | 소 |
| F6 | "합류 중 (1/3)" 표시 | impl §0.6 | 소 |
| F7 | `phase` · `target` · `cycles` · `joined` · `held` 표시 | impl §0.6 | 중 |
| F8 | 랙 점유 현황 (`racks` 필드) | impl §0.6 | 중 |
| F9 | `cargo.w/d/h` 표시 | guide §3 | 소 |
| F10 | 위치 보간 (1 Hz 수신 → 부드러운 이동) | impl §0.6 | 소 |
| F11 | 모르는 `state` 값 원문 표시 | guide §3 | 소 |

현재 프론트가 호출하는 API는 **두 개뿐**이다.

```
/api/monitoring/dashboard
/api/vehicles/commands/emergency-stop-all
```

즉 **조작 수단이 비상정지 하나**다. 규격이 요구하는 운행 제어 5개
(start/stop/estop/resume/reset)와 차량별 hold/resume 2개가 전부 없다.

F10은 규격이 CSS 한 줄로도 된다고 알려준다.

```css
.vehicle { transition: transform 500ms linear; }
```

---

## 5. 규모 감각

| 묶음 | 항목 | 크기 | 비고 |
|---|---|---|---|
| ① 통신 마무리 | B1~B5 | **소** | 액션 문자열·enum 추가 수준 |
| ② 호장 투영 | B6·B7 | **소** | 참조 구현 60줄, Java 코드가 문서에 있음 |
| ③ 기존 차간 교체 | B10 | **소** | 거리 계산부만 교체 |
| ④ 주행 규칙 | B8·B9·B11·B12·B13 | **중** | 규칙별 의사코드 제공됨 |
| ⑤ 주기 상태기계 | B14·B15·B16 | **중** | 전이 조건표 제공됨 |
| ⑥ 운행 제어 API | B17~B21 | **중** | REST 8개 + WebSocket |
| ⑦ 프론트 조작 UI | F1~F6 | **소~중** | ⑥에 의존 |
| ⑧ 프론트 정보 표시 | F7~F11 | **중** | ⑥에 의존 |

**설계 부담은 낮다** — 의사코드가 상세하고 참조 구현이 저장소에 있다.
**통합 검증이 어렵다** — ④부터는 F팀 데모를 꺼야 확인할 수 있다.

---

## 6. 권고 순서

규격 §7이 제시한 순서를 그대로 따르되, 현재 상태를 반영해 조정했다.

```
① telemetry 수신 → 화면 표시     ✅ 이미 됨
② 정지 / 재개                     ⚠️ 비상정지만 됨 → F5 추가 필요
③ 목적지 지시                     ✅ 토픽 열림 (F팀 데모 꺼야 검증)
④ 교통 규칙 전체 이식             🔴 여기부터
```

④를 다시 쪼개면:

| 단계 | 내용 | 왜 이 순서인가 |
|---|---|---|
| 1 | **B6 `Track` (호장 투영)** | 순수 함수라 단위 테스트로 검증 가능. F팀 데모 안 꺼도 됨 |
| 2 | **B10 거리 계산부 교체** | 지금 잘못 판정하는 것을 바로잡음. **뒤를 못 해도 가치 있음** |
| 3 | B4 LOST 판정 | 이후 규칙 전부의 전제 |
| 4 | B17·B18 운행 상태 + REST | 프론트가 붙을 수 있게 먼저 |
| 5 | F1~F5 조작 UI | 4번 나오면 바로 |
| 6 | B8·B9·B11~B16 규칙 + 주기 | 가장 큼. 데모 꺼야 검증 |
| 7 | B1~B3, F7~F11 | 마무리 |

**1·2번이 핵심 권고다.** 기존 코드의 잘못을 고치는 일이고, 나머지를 못 하더라도
독립적으로 가치가 있으며, F팀 데모와 충돌하지 않아 지금 당장 할 수 있다.

---

## 7. 시작 전에 정해야 할 것

규격 §9가 "아직 정하지 못한 것"으로 남긴 항목 중 **구현에 직접 걸리는 것**:

| 항목 | 왜 걸리나 |
|---|---|
| **`arrived` 발행 주체** | 주기 상태기계 `LOAD` 단계의 진입 트리거다. AI가 `cargo`를 쏘는지 백엔드가 쏘는지 정해야 B14를 짤 수 있다 |
| `arrived` 토픽 접두사 | 혼자 `forklift/`를 쓴다. 통일하면 구독 설정을 바꿔야 한다 |
| 주기 종료 조건 | 지금은 무한 반복. 랙 24칸이 다 차면 어떻게 할지 |
| 인증서 SAN | 오린 브리지가 hostname 검증을 강제한다(`tls_insecure_set(False)`) — 백엔드와 달리 끌 수 없다 |

---

## 8. 확인하지 못한 것

- `task` 발행이 §4.2 `pickup`/`dropoff` 2지점 형식을 지원하는지 (코드를 더 봐야 함)
- 프론트 WebSocket이 실제로 몇 Hz로 갱신되는지
- `usable_height` 2.00의 근거 — 화물 최대가 실물 111 mm라 **높이 검사가 사실상 꺼져 있다**
- F팀 데모(`demo_loop2.py`)와 백엔드가 동시에 목표를 보낼 때의 실제 증상

---

## 부록 — 관련 파일

| 파일 | 역할 |
|---|---|
| `isaac_sim/docs/backend-mqtt-guide.md` | 접속·토픽·페이로드 |
| `isaac_sim/docs/backend-control-impl.md` | 관제 로직 설계서 |
| `isaac_sim/docs/traffic-rules-spec.md` | 알고리즘 배경 |
| `isaac_sim/nav2/scripts/track.py` | 호장 투영 참조 구현 (60줄) |
| `isaac_sim/nav2/scripts/demo_loop2.py` | 관제 로직 참조 구현 |
| `src/main/java/com/fast/backend/config/mqtt/` | 접속·토픽 (완성) |
| `src/main/java/com/fast/backend/isaac/dto/` | telemetry DTO (완성) |
| `src/main/java/com/fast/backend/traffic/` | 기존 차간 판정 (교체 필요) |
| `frontend/.../components/monitoring/` | 관제 화면 |
