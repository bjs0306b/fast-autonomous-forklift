import type { VehicleStatus } from "@/types/monitoring"
import type {
  NormalizedVehicleLocation,
  NormalizedVehicleStatusUpdate,
  RealtimeEvent,
} from "@/types/websocket"
import { VEHICLE_LOCATION_EVENT_TYPE, VEHICLE_STATUS_EVENT_TYPE } from "@/types/websocket"
import { normalizeVehicleStatus } from "./vehicleStatus"

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
}

function toFiniteNumberOrNull(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null
}

function toStringOrNull(value: unknown): string | null {
  return typeof value === "string" && value.length > 0 ? value : null
}

export function parseRealtimeEvent(body: string): RealtimeEvent<unknown> | null {
  let parsed: unknown
  try {
    parsed = JSON.parse(body)
  } catch {
    return null
  }
  if (!isRecord(parsed)) return null
  const eventType = toStringOrNull(parsed.eventType)
  const vehicleId = toStringOrNull(parsed.vehicleId)
  if (!eventType || !vehicleId) return null
  return {
    eventType,
    vehicleId,
    occurredAt: toStringOrNull(parsed.occurredAt) ?? "",
    data: parsed.data,
  }
}

export function normalizeStatusEvent(
  event: RealtimeEvent<unknown>,
): NormalizedVehicleStatusUpdate | null {
  if (event.eventType !== VEHICLE_STATUS_EVENT_TYPE) return null
  const data = isRecord(event.data) ? event.data : {}
  const status: VehicleStatus = normalizeVehicleStatus(data.status)
  const updatedAt =
    toStringOrNull(data.messageAt) ??
    toStringOrNull(data.receivedAt) ??
    toStringOrNull(event.occurredAt)
  return { vehicleId: event.vehicleId, status, updatedAt }
}

export function normalizeLocationEvent(
  event: RealtimeEvent<unknown>,
): NormalizedVehicleLocation | null {
  if (event.eventType !== VEHICLE_LOCATION_EVENT_TYPE || !isRecord(event.data)) return null
  const data = event.data
  if (!isRecord(data.position)) return null
  return {
    x: toFiniteNumberOrNull(data.position.x),
    y: toFiniteNumberOrNull(data.position.y),
    heading: toFiniteNumberOrNull(data.heading),
    speed: toFiniteNumberOrNull(data.speed),
    frameId: toStringOrNull(data.position.frameId),
    messageAt: toStringOrNull(data.messageAt) ?? toStringOrNull(event.occurredAt),
    receivedAt: toStringOrNull(data.receivedAt),
  }
}
