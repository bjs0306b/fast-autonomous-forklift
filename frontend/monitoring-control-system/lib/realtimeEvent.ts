import type { VehicleStatus } from "@/types/monitoring"
import type {
  NormalizedVehicleLocation,
  NormalizedVehicleStatusUpdate,
  RealtimeEvent,
} from "@/types/websocket"
import { VEHICLE_LOCATION_EVENT_TYPE, VEHICLE_STATUS_EVENT_TYPE } from "@/types/websocket"
import { normalizeVehicleStatus } from "./vehicleStatus"

// STOMP 메시지 본문 → 화면에서 쓸 수 있는 값으로 바꾸는 순수 함수 모음.
//
// 이 파일의 함수는 전부 "실패하면 null 을 반환"한다. 예외를 던지지 않는다 —
// 잘못된 메시지 한 건 때문에 WebSocket 구독 전체가 죽으면 안 되기 때문이다.

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
}

/** number 이면서 유한한 값만 통과시킨다. null/undefined 는 null 로, 그 외 타입도 null 로 떨어뜨린다. */
function toFiniteNumberOrNull(value: unknown): number | null {
  if (typeof value !== "number" || Number.isNaN(value) || !Number.isFinite(value)) {
    return null
  }
  return value
}

function toStringOrNull(value: unknown): string | null {
  return typeof value === "string" && value.length > 0 ? value : null
}

/**
 * STOMP 메시지 본문을 RealtimeEvent 봉투로 파싱한다.
 * JSON 파싱 실패, 봉투 구조 불일치, vehicleId 누락은 전부 null 을 반환한다.
 */
export function parseRealtimeEvent(body: string): RealtimeEvent<unknown> | null {
  let parsed: unknown
  try {
    parsed = JSON.parse(body)
  } catch {
    return null
  }

  if (!isRecord(parsed)) {
    return null
  }

  const eventType = toStringOrNull(parsed.eventType)
  const vehicleId = toStringOrNull(parsed.vehicleId)
  if (!eventType || !vehicleId) {
    return null
  }

  return {
    eventType,
    vehicleId,
    occurredAt: toStringOrNull(parsed.occurredAt) ?? "",
    data: parsed.data,
  }
}

/**
 * 차량 상태 이벤트를 정규화한다.
 *
 * ROS2/REST payload 는 { status, messageAt, receivedAt, ... },
 * Isaac payload 는 { forkliftId, status, timestamp, receivedAt, ... } 형태다.
 * 두 형태 모두 data.status 키를 공유하므로 상태값은 같은 방식으로 읽는다.
 *
 * 대상 차량은 <b>봉투 최상위 vehicleId</b>만 사용한다 — data.vehicleId / data.forkliftId 는 쓰지 않는다.
 * battery 는 이번 화면에서 사용하지 않으므로 읽지 않는다.
 * positionX/positionY 도 읽지 않는다(위치는 위치 토픽만 사용한다).
 */
export function normalizeStatusEvent(
  event: RealtimeEvent<unknown>,
): NormalizedVehicleStatusUpdate | null {
  if (event.eventType !== VEHICLE_STATUS_EVENT_TYPE) {
    return null
  }

  const data = isRecord(event.data) ? event.data : {}
  const status: VehicleStatus = normalizeVehicleStatus(data.status)

  const updatedAt =
    toStringOrNull(data.messageAt) ??
    toStringOrNull(data.timestamp) ??
    toStringOrNull(event.occurredAt)

  return { vehicleId: event.vehicleId, status, updatedAt }
}

/**
 * 차량 위치 이벤트를 정규화한다. ROS2/Isaac 두 payload 를 하나의 형태로 통일한다.
 *
 * 판별 규칙:
 * - data.position 이 객체면 ROS2 (source = "REAL")
 * - 아니고 x/y 키가 있으면 Isaac (source = "SIM", frameId 는 "map" 고정 — Isaac 메시지엔 frameId 가 없다)
 * - 둘 다 아니면 null
 *
 * 좌표는 유한한 number 만 통과시키고 NaN/Infinity 는 null 로 만든다.
 * (x 또는 y 가 null 이면 미니맵이 해당 차량을 렌더하지 않는다.)
 */
export function normalizeLocationEvent(
  event: RealtimeEvent<unknown>,
): NormalizedVehicleLocation | null {
  if (event.eventType !== VEHICLE_LOCATION_EVENT_TYPE) {
    return null
  }
  if (!isRecord(event.data)) {
    return null
  }

  const data = event.data

  // --- ROS2 형태: 중첩 position ---
  if (isRecord(data.position)) {
    const position = data.position
    return {
      x: toFiniteNumberOrNull(position.x),
      y: toFiniteNumberOrNull(position.y),
      heading: toFiniteNumberOrNull(data.heading),
      speed: toFiniteNumberOrNull(data.speed),
      frameId: toStringOrNull(position.frameId),
      messageAt: toStringOrNull(data.messageAt) ?? toStringOrNull(event.occurredAt),
      receivedAt: toStringOrNull(data.receivedAt),
      source: "REAL",
    }
  }

  // --- Isaac 형태: 평면 x/y ---
  if ("x" in data && "y" in data) {
    return {
      x: toFiniteNumberOrNull(data.x),
      y: toFiniteNumberOrNull(data.y),
      heading: toFiniteNumberOrNull(data.heading),
      speed: toFiniteNumberOrNull(data.speed),
      // Isaac 위치 메시지에는 frameId 가 없다. 백엔드도 map 을 기본값으로 쓴다.
      frameId: "map",
      messageAt: toStringOrNull(data.timestamp) ?? toStringOrNull(event.occurredAt),
      receivedAt: toStringOrNull(data.receivedAt),
      source: "SIM",
    }
  }

  return null
}
