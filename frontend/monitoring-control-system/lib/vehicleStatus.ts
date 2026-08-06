import type { VehicleStatus } from "@/types/monitoring"

/**
 * 백엔드 VehicleStatus enum과 동일한 목록.
 * 배열 순서는 백엔드 enum 선언 순서와 동일하다.
 */
export const VEHICLE_STATUS_VALUES = [
  "UNKNOWN",
  "IDLE",
  "ACTIVE",
  "MOVING",
  "LIFTING",
  "LOADING",
  "UNLOADING",
  "HOLDING",
  "ESTOP",
  "ERROR",
  "OFFLINE",
] as const satisfies readonly VehicleStatus[]

const VEHICLE_STATUS_SET: ReadonlySet<string> = new Set(VEHICLE_STATUS_VALUES)

/**
 * 임의의 값을 안전하게 VehicleStatus 로 정규화한다.
 *
 * Isaac 경로의 상태 payload 는 백엔드에서 정규화되지 않은 <b>원본 문자열</b>이 그대로 오기 때문에
 * (IsaacVehicleStatusEventData.status) 프론트가 방어적으로 처리해야 한다.
 *
 * - 문자열이 아니면 UNKNOWN
 * - 앞뒤 공백 제거 후 대문자로 변환
 * - 허용값이 아니면 UNKNOWN (알 수 없는 값을 임의의 다른 상태로 흡수하지 않는다)
 */
export function normalizeVehicleStatus(value: unknown): VehicleStatus {
  if (typeof value !== "string") {
    return "UNKNOWN"
  }
  const normalized = value.trim().toUpperCase()
  return VEHICLE_STATUS_SET.has(normalized) ? (normalized as VehicleStatus) : "UNKNOWN"
}

/**
 * 위치/telemetry 이벤트가 보고한 상태를 기존 화면 상태에 병합할 수 있는 값으로 바꾼다.
 * 계약 밖 문자열과 UNKNOWN 은 "갱신 정보 없음"인 null 로 반환해, REST 로 이미 알고 있던
 * ERROR/IDLE 같은 상태를 불완전한 위치 이벤트가 지우지 않게 한다.
 */
export function normalizeReportedVehicleStatus(value: unknown): VehicleStatus | null {
  const normalized = normalizeVehicleStatus(value)
  return normalized === "UNKNOWN" ? null : normalized
}

/** 차량 ID에서 미니맵 마커용 짧은 라벨을 만든다. 예: "SIM-F01" → "F01" */
export function toShortLabel(vehicleId: string): string {
  const trimmed = vehicleId.trim()
  if (!trimmed) {
    return "?"
  }
  const lastSegment = trimmed.split("-").pop()
  return (lastSegment && lastSegment.length > 0 ? lastSegment : trimmed).slice(0, 6)
}
