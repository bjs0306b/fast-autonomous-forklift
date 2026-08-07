export interface RealtimeEvent<T = unknown> {
  eventType: string
  vehicleId: string
  occurredAt: string
  data: T
}

export const VEHICLE_STATUS_EVENT_TYPE = "VEHICLE_STATUS_UPDATED"
export const VEHICLE_LOCATION_EVENT_TYPE = "VEHICLE_LOCATION_UPDATED"
export const VEHICLE_PATH_EVENT_TYPE = "VEHICLE_PATH_UPDATED"
export const VEHICLE_COMMAND_RESULT_EVENT_TYPE = "VEHICLE_COMMAND_RESULT_UPDATED"

export const TRANSPORT_TASK_FAILED_EVENT_TYPE = "TRANSPORT_TASK_FAILED"
export const TRANSPORT_TASK_MEASURED_EVENT_TYPE = "TRANSPORT_TASK_MEASURED"

export const TOPIC_VEHICLE_STATUS = "/topic/vehicles/status"
export const TOPIC_VEHICLE_LOCATION = "/topic/vehicles/location"
export const TOPIC_VEHICLE_PATH = "/topic/vehicles/path"
export const TOPIC_VEHICLE_RESULT = "/topic/vehicles/result"
/** 백엔드가 이미 발행하고 있던 운반 작업 토픽. 새로 만든 경로가 아니다. */
export const TOPIC_TRANSPORT_TASKS = "/topic/transport-tasks"
/** 측정 완료 이벤트. 검출 상자 좌표가 여기로 온다(백엔드 StationMeasurementTopics.ALL). */
export const TOPIC_STATION_MEASUREMENTS = "/topic/stations/measurements"

/**
 * 운반 작업 이벤트. 차량 이벤트({@link RealtimeEvent})와 달리 vehicleId 가 없을 수 있어
 * (배차 전 작업) 별도 타입을 둔다.
 */
export interface TransportTaskEvent {
  eventType: string
  taskId: string
  status: string | null
  vehicleId: string | null
  /** 실패 이벤트일 때만 채워진다. */
  failureCode: string | null
  occurredAt: string | null
}

export interface NormalizedVehicleLocation {
  x: number | null
  y: number | null
  heading: number | null
  speed: number | null
  frameId: string | null
  messageAt: string | null
  receivedAt: string | null
}

/** 위치 이벤트 한 건에서 위치와 함께 전달되는 차량 상태. */
export interface NormalizedVehicleLocationUpdate {
  location: NormalizedVehicleLocation
  /** 값이 없거나 백엔드 계약 밖의 값이면 null. 기존의 더 구체적인 상태를 지우지 않는다. */
  reportedStatus: import("./monitoring").VehicleStatus | null
  telemetry: {
    forkHeight: number | null
    battery: number | null
    reportedCargoId: string | null
    reportedCargoHeight: number | null
    loaded: boolean | null
    reportedTaskId: string | null
  }
}

export interface NormalizedVehicleStatusUpdate {
  vehicleId: string
  status: import("./monitoring").VehicleStatus
  updatedAt: string | null
  /**
   * 차량이 보고한 적재 정보. **payload 에 값이 없으면 null** 이며, 이는 "미적재"가 아니라
   * "이 이벤트로는 알 수 없음"이다. 그래서 병합할 때 null 로 기존 값을 덮지 않는다.
   * (대시보드 값은 운반 작업에서 유도될 수 있는데, 이벤트의 null 이 그걸 지우면 안 된다.)
   */
  hasCargo: boolean | null
  cargoId: number | null
}

export interface VehiclePathWaypoint {
  x: number
  y: number
}

export interface VehiclePathSnapshot {
  vehicleId: string
  waypoints: VehiclePathWaypoint[]
  goal: (VehiclePathWaypoint & { heading: number | null }) | null
  messageAt: string | null
  receivedAt: string | null
}

export interface VehicleCommandResultUpdate {
  commandId: string
  vehicleId: string
  targetSystem: string | null
  commandCategory: string | null
  command: string | null
  result: string | null
  message: string | null
  completedAt: string | null
}

export const STATION_MEASUREMENT_COMPLETED_EVENT_TYPE = "STATION_MEASUREMENT_COMPLETED"

/**
 * 측정 완료 결과. 백엔드 `StationMeasurementResponse` 와 같은 모양이다.
 *
 * `boxes` 는 검출 상자의 **이미지 픽셀 좌표**라 화면에 그릴 때만 쓴다 — 지게차 이동
 * 좌표가 아니다(lib/monitoring/measurementBox.ts 주석 참고).
 */
export interface StationMeasurementData {
  measurementId: string
  sessionId: string | null
  cargoId: number | null
  status: string
  cargoHeight: number | null
  cargoWidth: number | null
  frameWidth: number | null
  frameHeight: number | null
  boxes: { bboxPx: number[]; score: number | null }[] | null
  tippingLevel: string | null
  overhangRatio: number | null
  placementEligible: boolean
  createdAt: string | null
}
