// 관제 화면 도메인 타입.
//
// FR-402-1 연동 단계에서 스캐폴딩용 Mock 타입을 걷어내고
// 실제 백엔드 계약(GET /api/monitoring/dashboard)에 맞춰 재정의했다.

/**
 * 차량 상태. 백엔드 com.fast.backend.vehicle.domain.VehicleStatus enum 10종과 정확히 일치한다.
 *
 * 스캐폴딩 시절의 WORKING / STOPPED 는 백엔드에 존재하지 않아 제거했고,
 * 백엔드에만 있던 ACTIVE / LIFTING / LOADING / UNLOADING / ERROR 를 추가했다.
 */
export type VehicleStatus =
  | "UNKNOWN"
  | "IDLE"
  | "ACTIVE"
  | "MOVING"
  | "LIFTING"
  | "LOADING"
  | "UNLOADING"
  | "ESTOP"
  | "ERROR"
  | "OFFLINE"

/**
 * 차량 등록 출처. 백엔드 VehicleSource enum.
 *
 * 주의: 위치 스냅샷의 출처 태그({@link LocationSource})와 값 집합이 다르다.
 * 두 타입을 하나로 합치지 않는다.
 */
export type VehicleSource = "REAL" | "SIMULATION"

/**
 * 위치 메시지 출처 태그. ROS2 경로는 "REAL", Isaac Sim 경로는 "SIM" 이다.
 * 차량 등록 정보인 {@link VehicleSource}("SIMULATION")와 값이 다르므로 별도 타입으로 유지한다.
 */
export type LocationSource = "REAL" | "SIM"

/** 운반 작업 상태. 백엔드 TaskStatus enum. */
export type TaskStatus =
  | "PENDING"
  | "ASSIGNED"
  | "MOVING_TO_PICKUP"
  | "PICKING_UP"
  | "TRANSPORTING"
  | "PLACING"
  | "COMPLETED"
  | "FAILED"
  | "CANCELLED"

/** 운반 명령 상태. 백엔드 TransportCommandStatus enum. */
export type TransportCommandStatus =
  | "CREATED"
  | "PUBLISHED"
  | "ACKNOWLEDGED"
  | "SUCCEEDED"
  | "FAILED"
  | "PUBLISH_FAILED"
  | "TIMEOUT"

/**
 * 차량 최신 위치. 위치를 한 번도 수신하지 못한 차량은 이 객체 전체가 null 이다
 * (개별 필드만 null 인 경우와 구분해야 한다).
 *
 * 단위: x/y = m, heading = degree([0,360)), speed = m/s.
 * 좌표 원점과 heading 0도 기준축은 백엔드가 정의하지 않는다(ROS2/Isaac 협의 대상).
 */
export interface DashboardLocation {
  x: number | null
  y: number | null
  heading: number | null
  speed: number | null
  /** "map" | "odom" */
  frameId: string | null
  /** 차량이 메시지를 만든 시각 (ISO-8601 +09:00) */
  messageAt: string | null
  /** 백엔드가 메시지를 수신한 시각 (ISO-8601 +09:00) */
  receivedAt: string | null
  source: LocationSource
}

/** 차량이 현재 수행 중인 작업 요약. 진행 중 작업이 없으면 null. */
export interface DashboardCurrentTask {
  /** 백엔드 taskCode */
  taskId: string
  status: TaskStatus
  /** 아직 운반 명령이 발행되지 않았으면 null */
  commandStatus: TransportCommandStatus | null
  updatedAt: string | null
}

/** GET /api/monitoring/dashboard 의 vehicles[] 항목. */
export interface DashboardVehicle {
  vehicleId: string
  name: string
  source: VehicleSource
  /** 현재 dashboard 는 활성 차량만 반환하므로 항상 true 다(계약 명시용 필드). */
  active: boolean
  status: VehicleStatus
  /** 위치 미수신이면 null */
  location: DashboardLocation | null
  /** 진행 중 작업이 없으면 null */
  currentTask: DashboardCurrentTask | null
  /** 상태 messageAt ?? receivedAt */
  lastUpdatedAt: string | null
}

/** GET /api/monitoring/dashboard 의 tasks[] 항목(최근 100건). */
export interface DashboardTask {
  taskId: string
  vehicleId: string | null
  status: TaskStatus | null
  commandStatus: TransportCommandStatus | null
  updatedAt: string | null
}

export interface DashboardResponse {
  vehicles: DashboardVehicle[]
  tasks: DashboardTask[]
}

/**
 * Isaac Sim WebRTC 영상 연결 상태.
 *
 * **사용자가 고르는 값이 아니다**(prompt80). 오직 실제 WebRTC 이벤트만이 이 값을 바꾼다 —
 * 화면 어디에도 이 상태를 수동으로 지정하는 select/토글을 두지 않는다.
 *
 *   idle          연결을 아직 시작하지 않음
 *   connecting    최초 연결 시도 중(시그널링 포함)
 *   connected     **영상이 실제로 재생 중**(video 의 playing 이벤트 기준)
 *   reconnecting  끊긴 뒤 재연결 시도 중
 *   disconnected  연결됐다가 끊김
 *   failed        연결 실패(오류)
 *
 * `connected` 판정 기준은 시그널링 성공이 아니라 **영상 프레임 재생**이다.
 * 시그널링만 붙은 상태를 connected 로 표시하면 검은 화면을 "연결됨"으로 읽게 된다.
 */
export type WebRtcStatus =
  | "idle"
  | "connecting"
  | "connected"
  | "reconnecting"
  | "disconnected"
  | "failed"

/** 관제 실시간(WebSocket) 연결 상태. 영상 스트림 상태와는 별개다. */
export type RealtimeConnectionStatus = "connecting" | "connected" | "disconnected" | "error"

/** 실시간 관제 화면 오버레이에 표시할 선택 차량 요약 정보 */
export interface SelectedVehicleSummary {
  vehicleId: string
  name?: string | null
  source?: VehicleSource | null
  status: VehicleStatus
  currentTask?: string | null
}
