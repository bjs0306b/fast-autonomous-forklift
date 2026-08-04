export interface RealtimeEvent<T = unknown> {
  eventType: string
  vehicleId: string
  occurredAt: string
  data: T
}

export const VEHICLE_STATUS_EVENT_TYPE = "VEHICLE_STATUS_UPDATED"
export const VEHICLE_LOCATION_EVENT_TYPE = "VEHICLE_LOCATION_UPDATED"

export const TOPIC_VEHICLE_STATUS = "/topic/vehicles/status"
export const TOPIC_VEHICLE_LOCATION = "/topic/vehicles/location"

export interface NormalizedVehicleLocation {
  x: number | null
  y: number | null
  heading: number | null
  speed: number | null
  frameId: string | null
  messageAt: string | null
  receivedAt: string | null
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
