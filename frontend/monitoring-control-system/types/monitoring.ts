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
  cargoId: string | null
  /** 팔레트를 제외한 화물 높이(m). 측정 결과가 없으면 null. */
  cargoHeight: number | null
  lastUpdatedAt: string | null
}

export interface DashboardTask {
  taskId: string
  vehicleId: string | null
  status: TaskStatus | null
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
