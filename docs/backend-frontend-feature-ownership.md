# 백엔드·관제 프론트 기능별 작업 범위

## 1. 분담 기준

백엔드와 프론트엔드를 별도로 나누지 않고 기능을 기준으로 분담한다. 각 파트는 담당 기능의 백엔드 API, 프론트 화면, 테스트까지 함께 관리한다.

| 파트 | 기능 범위 | 백엔드 영역 | 프론트 영역 |
|---|---|---|---|
| A: 차량·실시간 관제 | 차량 위치·상태·제어 | `vehicle`, `forklift`, `isaac`, `embedded`, `command`, `monitoring`, `mqtt` | 미니맵, 차량 상태·상세, 차량 명령, 비상정지, Isaac WebRTC |
| B: 화물·적재 운영 | 측정·안전·적재·운반 | `ai`, `station`, `loadsafety`, `storage`, `transport` | 측정 결과, 화물 안전 경고, 적재 위치, 운반 작업 |

## 2. A 파트 작업 범위

### 담당 기능

- 차량 등록 및 현재 상태 관리
- MQTT 차량 위치·배터리·포크 상태 수신
- WebSocket 실시간 차량 상태 전송
- 차량 명령 발행, 결과 처리 및 비상정지
- 다중 차량 미니맵 표시
- Isaac Sim WebRTC 스트리밍
- 차량 오류 및 연결 상태 표시

### 백엔드

```text
src/main/java/com/fast/backend/vehicle
src/main/java/com/fast/backend/forklift
src/main/java/com/fast/backend/isaac
src/main/java/com/fast/backend/embedded
src/main/java/com/fast/backend/command
src/main/java/com/fast/backend/monitoring
src/main/java/com/fast/backend/mqtt
```

같은 이름의 `src/test/java/com/fast/backend/*` 테스트 폴더도 A가 담당한다.

### 프론트엔드

현재 구조에서는 다음 영역을 담당한다.

```text
components/monitoring/MiniMap*
components/monitoring/VehicleDetailPanel.tsx
components/monitoring/SelectedVehicleOverlay.tsx
components/monitoring/GlobalEmergencyStopBar.tsx
components/monitoring/CommandNotice.tsx
components/monitoring/ConnectionStatusBadge.tsx
components/monitoring/RealtimeConnectionBadge.tsx
components/monitoring/DigitalTwinVideoLayer.tsx
components/monitoring/IsaacSimStream.tsx
components/monitoring/vehicle-status.ts

hooks/useMonitoringDashboard.ts
hooks/useMonitoringSocket.ts

lib/api/commandApi.ts
lib/api/monitoringApi.ts
lib/config/isaacSim.ts
lib/config/webrtcStatusText.ts
lib/coordinate.ts
lib/realtimeEvent.ts
lib/vehicleStatus.ts
```

새 기능은 다음과 같이 구분할 수 있다.

```text
features/vehicle-monitoring
features/vehicle-command
features/isaac-stream
```

## 3. B 파트 작업 범위

### 담당 기능

- 스테이션 세션 및 측정 결과 관리
- 화물 높이·전복 위험·돌출률 처리
- 적재 가능 여부와 최적 적재 위치 계산
- `storage_slot` 점유 및 예약 관리
- `transport_task` 생성 및 상태 관리
- 화물 측정·적재·운반 관련 관제 화면

### 백엔드

```text
src/main/java/com/fast/backend/ai
src/main/java/com/fast/backend/station
src/main/java/com/fast/backend/loadsafety
src/main/java/com/fast/backend/storage
src/main/java/com/fast/backend/transport
```

같은 이름의 `src/test/java/com/fast/backend/*` 테스트 폴더도 B가 담당한다.

### 프론트엔드

현재 구조에서는 다음 영역을 담당한다.

```text
components/monitoring/LoadSafetyOverlay.tsx
components/monitoring/LoadSafetyPanel.tsx

lib/api/loadSafetyApi.ts
lib/loadSafety.ts
types/loadSafety.ts
```

새 기능은 다음 영역에 추가한다.

```text
features/station-measurement
features/load-safety
features/storage
features/transport
```

## 4. 기능 연결 경계

```text
B: 적재 위치 선정 및 운반 작업 생성
        ↓ transport_task
A: 차량 명령 생성 및 전달
        ↓ MQTT
ROS2 / Isaac Sim
        ↓ 차량 상태 및 명령 결과
A: 실시간 차량 상태 저장 및 표시
        ↓ 작업 완료 결과
B: 운반 작업 및 적재 위치 상태 변경
```

- B는 무엇을 어디로 운반할지 결정한다.
- A는 차량에 명령을 전달하고 실제 실행 상태를 관리한다.
- 기능 연결에는 `taskId`, `vehicleId`, `commandId`, `cargoId`, `slotCode`를 사용한다.

## 5. `transport` 관련 책임

### B 파트

- 운반 작업 생성
- 적재 목적지 선정
- 차량 배정 요청
- 작업 상태 관리
- 적재 완료 처리
- 슬롯 예약·점유 상태 변경

### A 파트

- 차량 명령 생성 및 발행
- MQTT 명령 전달
- 명령 응답 처리
- 차량 실행 상태 관리
- 차량 오류 및 비상정지 처리

## 6. 공용 영역

다음 영역은 한 파트가 단독으로 변경 범위를 결정하지 않고 상대 파트와 영향을 확인한다.

```text
src/main/java/com/fast/backend/common
src/main/java/com/fast/backend/config
src/main/resources/application*.yml

frontend/monitoring-control-system/app
frontend/monitoring-control-system/components/ui
frontend/monitoring-control-system/lib/api/httpClient.ts
frontend/monitoring-control-system/lib/utils.ts
frontend/monitoring-control-system/types/api.ts
```

| 공용 영역 | 주 담당 |
|---|---|
| MQTT 공통 연결 설정 | A |
| DB 공통 스키마 | B |
| 프론트 공통 UI 및 레이아웃 | A |
| 공통 응답 및 예외 처리 | 변경하는 파트가 영향 확인 |
| API·MQTT·WebSocket 계약 | A·B가 함께 확인 |

## 7. Mapper 범위

### A 파트

```text
Vehicle*
Embedded*
```

### B 파트

```text
Ai*
Station*
Cargo*
Pallet*
Rack*
StorageSlot*
Transport*
VehicleLoadSafetyMapper
```

DB 스키마 공통 파일은 B가 주로 관리하고, 차량 관련 테이블 변경은 A가 함께 확인한다.
