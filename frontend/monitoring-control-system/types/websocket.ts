// STOMP 실시간 이벤트 타입.
//
// 백엔드 com.fast.backend.common.websocket.RealtimeEvent 봉투와 대응한다.
// 차량 상태/위치 이벤트는 항상 이 봉투로 감싸져 오며, 봉투 최상위 vehicleId 가 대상 차량이다.
//
// 주의: 안전 명령(/topic/vehicles/commands), 전체 비상정지(/topic/vehicles/emergency-stop),
// 운반 작업(/topic/transport-tasks) 이벤트는 이 봉투를 쓰지 않는 평면 payload 다.
// FR-402-1 범위에서는 구독하지 않으므로 여기 정의하지 않는다.

/** 차량 도메인 이벤트 공통 봉투. */
export interface RealtimeEvent<T = unknown> {
  eventType: string
  /** 대상 차량. 차량 이벤트에서는 항상 채워진다. */
  vehicleId: string
  /** 이벤트가 실제 발생한 시각 (ISO-8601 +09:00) */
  occurredAt: string
  data: T
}

export const VEHICLE_STATUS_EVENT_TYPE = "VEHICLE_STATUS_UPDATED"
export const VEHICLE_LOCATION_EVENT_TYPE = "VEHICLE_LOCATION_UPDATED"

/** 구독 대상 공통 토픽. 차량별 토픽(/{vehicleId})은 중복 수신이 되므로 구독하지 않는다. */
export const TOPIC_VEHICLE_STATUS = "/topic/vehicles/status"
export const TOPIC_VEHICLE_LOCATION = "/topic/vehicles/location"

/**
 * 위치 이벤트를 화면에서 쓰기 좋게 정규화한 형태.
 * ROS2(중첩 position)와 Isaac(평면 x/y) 두 payload 를 이 하나로 통일한다.
 */
export interface NormalizedVehicleLocation {
  x: number | null
  y: number | null
  heading: number | null
  speed: number | null
  frameId: string | null
  messageAt: string | null
  receivedAt: string | null
  source: "REAL" | "SIM"
}

/** 상태 이벤트를 정규화한 형태. */
export interface NormalizedVehicleStatusUpdate {
  vehicleId: string
  status: import("./monitoring").VehicleStatus
  /** 상태 갱신 시각 (messageAt ?? timestamp ?? occurredAt) */
  updatedAt: string | null
}
