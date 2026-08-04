import type { VehicleStatus } from "@/types/monitoring"
import type {
  NormalizedVehicleLocation,
  NormalizedVehicleStatusUpdate,
  RealtimeEvent,
  TransportTaskEvent,
  VehicleCommandResultUpdate,
  VehiclePathSnapshot,
} from "@/types/websocket"
import {
  VEHICLE_COMMAND_RESULT_EVENT_TYPE,
  VEHICLE_LOCATION_EVENT_TYPE,
  VEHICLE_PATH_EVENT_TYPE,
  VEHICLE_STATUS_EVENT_TYPE,
} from "@/types/websocket"
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

function toBooleanOrNull(value: unknown): boolean | null {
  return typeof value === "boolean" ? value : null
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

/**
 * 운반 작업 이벤트 파싱.
 *
 * {@link parseRealtimeEvent} 를 재사용하지 않는 이유: 그쪽은 vehicleId 가 없으면 버리는데,
 * 운반 작업은 배차 전이라 vehicleId 가 비어 있을 수 있다. 그 이벤트까지 버리면 실패가
 * 화면에 못 닿는다.
 */
export function parseTransportTaskEvent(body: string): TransportTaskEvent | null {
  let parsed: unknown
  try {
    parsed = JSON.parse(body)
  } catch {
    return null
  }
  if (!isRecord(parsed)) return null
  const eventType = toStringOrNull(parsed.eventType)
  const taskId = toStringOrNull(parsed.taskId)
  if (!eventType || !taskId) return null
  return {
    eventType,
    taskId,
    status: toStringOrNull(parsed.status),
    vehicleId: toStringOrNull(parsed.vehicleId),
    failureCode: toStringOrNull(parsed.failureCode),
    occurredAt: toStringOrNull(parsed.occurredAt),
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
  return {
    vehicleId: event.vehicleId,
    status,
    updatedAt,
    hasCargo: toBooleanOrNull(data.hasCargo),
    cargoId: toFiniteNumberOrNull(data.cargoId),
  }
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

export function normalizePathEvent(event: RealtimeEvent<unknown>): VehiclePathSnapshot | null {
  if (event.eventType !== VEHICLE_PATH_EVENT_TYPE || !isRecord(event.data)) return null
  const waypoints = Array.isArray(event.data.waypoints)
    ? event.data.waypoints.flatMap((item) => {
        if (!isRecord(item)) return []
        const x = toFiniteNumberOrNull(item.x)
        const y = toFiniteNumberOrNull(item.y)
        return x == null || y == null ? [] : [{ x, y }]
      })
    : []
  const rawGoal = isRecord(event.data.goal) ? event.data.goal : null
  const goalX = rawGoal ? toFiniteNumberOrNull(rawGoal.x) : null
  const goalY = rawGoal ? toFiniteNumberOrNull(rawGoal.y) : null
  return {
    vehicleId: event.vehicleId,
    waypoints,
    goal:
      goalX == null || goalY == null
        ? null
        : { x: goalX, y: goalY, heading: toFiniteNumberOrNull(rawGoal?.heading) },
    messageAt:
      toStringOrNull(event.data.timestamp) ?? toStringOrNull(event.occurredAt),
    receivedAt: toStringOrNull(event.data.receivedAt),
  }
}

export function normalizeCommandResultEvent(
  event: RealtimeEvent<unknown>,
): VehicleCommandResultUpdate | null {
  if (event.eventType !== VEHICLE_COMMAND_RESULT_EVENT_TYPE || !isRecord(event.data)) return null
  const commandId = toStringOrNull(event.data.commandId)
  if (!commandId) return null
  return {
    commandId,
    vehicleId: event.vehicleId,
    targetSystem: toStringOrNull(event.data.targetSystem),
    commandCategory: toStringOrNull(event.data.commandCategory),
    command: toStringOrNull(event.data.command),
    result: toStringOrNull(event.data.result),
    message: toStringOrNull(event.data.message),
    completedAt:
      toStringOrNull(event.data.completedAt) ?? toStringOrNull(event.occurredAt),
  }
}
