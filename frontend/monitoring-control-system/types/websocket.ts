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
}
