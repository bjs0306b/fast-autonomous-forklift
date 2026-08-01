# 백엔드·관제 프론트 기능별 작업 범위

백엔드와 프론트를 따로 나누지 않고 기능을 세로로 분할한다. 각 담당자는 API, 서비스, DB 연동, 화면, 테스트까지 해당 기능을 끝까지 관리한다.

## A: 차량·실시간 관제

### 기능

- 차량 등록과 활성 상태 관리
- MQTT 차량 상태·위치·경로·명령 결과 수신
- 차량 현재 상태 저장과 stale 위치 차단
- WebSocket 실시간 차량 상태 전송
- 차량 명령과 비상정지
- 미니맵, 차량 마커·상세, 연결 상태
- Isaac Sim WebRTC 화면

### 백엔드 범위

```text
src/main/java/com/fast/backend/vehicle
src/main/java/com/fast/backend/forklift
src/main/java/com/fast/backend/isaac
src/main/java/com/fast/backend/command
src/main/java/com/fast/backend/monitoring
src/main/java/com/fast/backend/mqtt
```

### 프론트 범위

```text
components/monitoring/MiniMap*
components/monitoring/VehicleDetailPanel.tsx
components/monitoring/SelectedVehicleOverlay.tsx
components/monitoring/GlobalEmergencyStopBar.tsx
components/monitoring/CommandNotice.tsx
components/monitoring/*Connection*Badge.tsx
components/monitoring/DigitalTwinVideoLayer.tsx
components/monitoring/IsaacSimStream.tsx
hooks/useMonitoringDashboard.ts
hooks/useMonitoringSocket.ts
lib/api/commandApi.ts
lib/api/monitoringApi.ts
lib/config/isaacSim.ts
lib/coordinate.ts
lib/realtimeEvent.ts
types/monitoring.ts
types/websocket.ts
```

## B: 화물·측정·적재 운영

### 기능

- 측정 세션 생성과 단일 측정 설비 점유 관리
- REST 측정 결과 처리와 선택적 MQTT 결과 수신
- 화물 높이·전복 위험·돌출률 저장
- 적재 가능 여부와 적재 위치 계산
- 적재 위치 예약·점유 관리
- 운반 작업 생성과 상태 변경
- 화물 측정·적재·운반 관제 화면

### 백엔드 범위

```text
src/main/java/com/fast/backend/station
src/main/java/com/fast/backend/storage
src/main/java/com/fast/backend/transport
```

AI 추론 코드는 `ai/src/station`에 있지만, 결과 저장 계약과 적재 판단은 백엔드 B 범위다. 기존 `ai`, `loadsafety`, `pallet`, `rack`, `rack_level` 백엔드 패키지는 MVP 스키마에서 제거됐다.

### 프론트 범위

현재 전용 화면은 아직 분리되지 않았다. 추가 시 다음 기능 단위로 구성한다.

```text
features/station-measurement
features/storage
features/transport
```

기존 `LoadSafetyPanel`, `LoadSafetyOverlay`, `loadSafetyApi`는 삭제됐다. 안전 정보는 `station_measurement` 결과의 `tippingLevel`과 `overhangRatio`를 표시하는 방식으로 새로 연결한다.

## 경계

```text
B: 측정 결과 검증 → 적재 위치 선정 → transport_task 생성
                                      ↓ taskId, vehicleId
A: vehicle_command 생성 → MQTT 발행 → ROS2/Isaac Sim 실행
                                      ↓ 명령 결과·차량 상태
A: 실시간 상태 저장·표시
                                      ↓ 작업 완료 처리
B: transport_task와 storage_slot 상태 갱신
```

- B는 목적지와 운반 작업을 결정한다.
- A는 차량 명령과 차량 실행 상태를 관리한다.
- 경계 식별자는 `taskCode`, `vehicleId`, `commandId`, `cargoId`, `slotCode`다.

## 공용 영역

```text
src/main/java/com/fast/backend/common
src/main/java/com/fast/backend/config
src/main/resources/application*.yml
src/main/resources/db/schema.sql
frontend/monitoring-control-system/app
frontend/monitoring-control-system/components/ui
frontend/monitoring-control-system/lib/api/httpClient.ts
```

| 공용 영역 | 주 담당 |
|---|---|
| MQTT 공통 연결 설정 | A |
| DB 정본 스키마 | B |
| 프론트 공통 UI·레이아웃 | A |
| API·MQTT·WebSocket 계약 | 변경 담당자가 상대 영향 확인 |

공용 파일은 동시에 수정하지 않고, 계약 변경 MR에는 영향을 받는 담당자가 함께 검토한다.
