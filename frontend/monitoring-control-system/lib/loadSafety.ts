import type {
  LoadSafetyRiskLevel,
  LoadSafetySource,
  LoadSafetyState,
} from "@/types/loadSafety"
import { LOAD_SAFETY_STALE_AFTER_MS } from "@/types/loadSafety"
import type { RealtimeEvent } from "@/types/websocket"
import { VEHICLE_LOAD_SAFETY_EVENT_TYPE } from "@/types/websocket"

// 적재 안전 이벤트 정규화. realtimeEvent.ts 와 동일한 원칙을 따른다:
// 실패하면 예외를 던지지 않고 null 을 반환한다 — 메시지 한 건 때문에 구독 전체가 죽으면 안 된다.

const RISK_LEVELS: readonly string[] = ["NORMAL", "CAUTION", "WARNING", "DANGER", "UNKNOWN"]
const SOURCES: readonly string[] = ["VISION", "SENSOR", "ROS2", "UNKNOWN"]

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
}

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
 * 백엔드가 보낸 riskLevel 문자열을 union 으로 좁힌다.
 *
 * 모르는 값은 "NORMAL" 이 아니라 **"UNKNOWN"** 으로 떨어뜨린다 — 백엔드 enum 과 같은 원칙이다.
 * 미정의 값을 정상으로 해석하면 실제로 위험한 상태가 화면에 초록색으로 뜬다.
 */
export function normalizeRiskLevel(value: unknown): LoadSafetyRiskLevel {
  const raw = typeof value === "string" ? value.trim().toUpperCase() : ""
  return (RISK_LEVELS.includes(raw) ? raw : "UNKNOWN") as LoadSafetyRiskLevel
}

export function normalizeSource(value: unknown): LoadSafetySource {
  const raw = typeof value === "string" ? value.trim().toUpperCase() : ""
  return (SOURCES.includes(raw) ? raw : "UNKNOWN") as LoadSafetySource
}

/**
 * REST 응답 또는 WebSocket data 를 화면용 상태로 정규화한다.
 *
 * 두 경로가 같은 구조이므로 정규화 함수도 하나다. 숫자는 유한값만 통과시켜 NaN/Infinity 가
 * 화면 계산에 스며들지 않게 한다.
 */
export function normalizeLoadSafetyPayload(
  vehicleId: string,
  data: unknown,
): LoadSafetyState | null {
  if (!isRecord(data)) {
    return null
  }
  return {
    vehicleId,
    cargoId: toStringOrNull(data.cargoId),
    forkHeight: toFiniteNumberOrNull(data.forkHeight),
    cargoHeight: toFiniteNumberOrNull(data.cargoHeight),
    roll: toFiniteNumberOrNull(data.roll),
    pitch: toFiniteNumberOrNull(data.pitch),
    loadOffsetX: toFiniteNumberOrNull(data.loadOffsetX),
    loadOffsetY: toFiniteNumberOrNull(data.loadOffsetY),
    riskLevel: normalizeRiskLevel(data.riskLevel),
    riskCode: toStringOrNull(data.riskCode),
    message: toStringOrNull(data.message),
    source: normalizeSource(data.source),
    detectedAt: toStringOrNull(data.detectedAt),
    receivedAt: toStringOrNull(data.receivedAt),
  }
}

/**
 * 적재 안전 WebSocket 이벤트를 정규화한다.
 *
 * 대상 차량은 **봉투 최상위 vehicleId** 만 사용한다(data.vehicleId 는 쓰지 않는다) —
 * realtimeEvent.ts 의 상태/위치 이벤트와 같은 규칙이다.
 */
export function normalizeLoadSafetyEvent(
  event: RealtimeEvent<unknown>,
): LoadSafetyState | null {
  if (event.eventType !== VEHICLE_LOAD_SAFETY_EVENT_TYPE) {
    return null
  }
  return normalizeLoadSafetyPayload(event.vehicleId, event.data)
}

/**
 * 데이터가 오래됐는지 판정한다(prompt63.md 3장 7번).
 *
 * **이것은 위험도 판정이 아니다** — 오래된 데이터라고 riskLevel 을 바꾸지 않는다. 화면에 "N초 전"
 * 이라는 사실만 덧붙여, 조작자가 "지금 안전한 것"과 "한동안 갱신이 없는 것"을 구분하게 한다.
 *
 * 기준 시각은 detectedAt(센서 감지 시각)이다. 파싱할 수 없으면 stale 로 단정하지 않고 false 를
 * 반환한다 — 시각 형식 문제로 멀쩡한 데이터에 경고를 붙이지 않기 위해서다.
 */
export function isLoadSafetyStale(
  state: LoadSafetyState | null | undefined,
  now: number = Date.now(),
  staleAfterMs: number = LOAD_SAFETY_STALE_AFTER_MS,
): boolean {
  if (!state?.detectedAt) return false
  const detected = Date.parse(state.detectedAt)
  if (Number.isNaN(detected)) return false
  return now - detected > staleAfterMs
}

/** detectedAt 이후 경과 시간(ms). 판정 불가면 null. */
export function loadSafetyAgeMs(
  state: LoadSafetyState | null | undefined,
  now: number = Date.now(),
): number | null {
  if (!state?.detectedAt) return null
  const detected = Date.parse(state.detectedAt)
  if (Number.isNaN(detected)) return null
  return Math.max(0, now - detected)
}
