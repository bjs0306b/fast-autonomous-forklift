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

export type TaskStatus =
  | "PENDING"
  | "ASSIGNED"
  | "MOVING_TO_PICKUP"
  | "MEASURING"
  | "PICKING_UP"
  | "TRANSPORTING"
  | "PLACING"
  | "COMPLETED"
  | "FAILED"
  | "CANCELLED"

/** 좌표는 m, 방향은 degree, 속도는 m/s를 사용한다. */
export interface DashboardLocation {
  x: number | null
  y: number | null
  heading: number | null
  speed: number | null
  frameId: string | null
  messageAt: string | null
  receivedAt: string | null
}

/**
 * 백엔드가 내려주는 실패 원인 코드.
 *
 * 백엔드는 코드와 원본 측정 상태만 주고 사용자 문구는 프론트가 만든다
 * (`lib/monitoring/measurementFailure.ts`) — 같은 의미를 두 벌로 관리하지 않기 위해서다.
 */
export type TaskFailureCode =
  | "MEASUREMENT_NO_DETECTION"
  | "MEASUREMENT_DISTANCE_UNRELIABLE"
  | "MEASUREMENT_PALLET_NOT_DETECTED"
  | "PLACEMENT_INELIGIBLE"
  | "MEASUREMENT_NO_RESPONSE"
  | "PLACEMENT_SLOT_UNAVAILABLE"

/** AI 측정 파이프라인이 보고하는 원본 상태. */
export type MeasurementStatus = "ok" | "dimensions_only" | "no_detection" | "unreliable"

export interface DashboardFailure {
  taskId: string
  /** 백엔드가 코드를 남기지 못한 과거 실패 데이터가 있을 수 있어 string | null 로 받는다. */
  failureCode: TaskFailureCode | string | null
  /** 측정 결과가 아예 도착하지 않았으면 null. */
  measurementStatus: MeasurementStatus | string | null
  /** null 은 "부적합"이 아니라 **판정한 적 없음**이다. */
  placementEligible: boolean | null
  /** 팔레트 폭 대비 돌출 비율(무차원). 판정 불가 상태면 null. */
  overhangRatio: number | null
  /** SAFE / WARNING / DANGER. 판정 불가 상태면 null. */
  tippingLevel: string | null
  occurredAt: string | null
}

export interface DashboardCurrentTask {
  taskId: string
  status: TaskStatus
  updatedAt: string | null
}

export interface DashboardVehicle {
  vehicleId: string
  name: string
  active: boolean
  status: VehicleStatus
  location: DashboardLocation | null
  currentTask: DashboardCurrentTask | null
  /** 적재 여부. null 은 "미적재"가 아니라 **확인할 근거가 없음**을 뜻한다. */
  hasCargo: boolean | null
  cargoId: number | null
  /** 팔레트를 제외한 화물 높이(m). 측정 결과가 없으면 null. */
  cargoHeight: number | null
  /**
   * 이 차량의 가장 최근 실패. 실패가 없거나 그 뒤로 새 작업이 시작됐으면 null 이다
   * (지나간 경고를 화면에 남기면 현재 상태를 오해한다).
   */
  lastFailure: DashboardFailure | null
  lastUpdatedAt: string | null
}

export interface DashboardTask {
  taskId: string
  vehicleId: string | null
  status: TaskStatus | null
  failureCode: TaskFailureCode | string | null
  updatedAt: string | null
}

export interface DashboardResponse {
  vehicles: DashboardVehicle[]
  tasks: DashboardTask[]
}

export type WebRtcStatus =
  | "idle"
  | "connecting"
  | "connected"
  | "reconnecting"
  | "disconnected"
  | "failed"

export type RealtimeConnectionStatus = "connecting" | "connected" | "disconnected" | "error"

export interface SelectedVehicleSummary {
  vehicleId: string
  name?: string | null
  status: VehicleStatus
  currentTask?: string | null
}
