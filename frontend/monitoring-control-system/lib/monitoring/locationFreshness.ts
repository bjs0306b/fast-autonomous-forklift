/**
 * 위치 데이터의 신선도 판정.
 *
 * 관제 화면의 위치·Heading·Speed 칸에는 **항상 숫자가 차 있다.** 통신이 끊겨도 마지막으로 받은 값이
 * 그대로 남기 때문에, 시각만 보여주면 작업자는 그 좌표를 "지금 그 자리"로 읽는다. 그래서 값이 얼마나
 * 오래됐는지를 함께 알려 준다.
 *
 * 이 파일은 **의존성이 없는 순수 함수만** 담는다. React·DOM 을 끌어오지 않아야 `node --test` 로
 * 별도 테스트 프레임워크 없이 검증할 수 있다.
 *
 * ⚠️ **`receivedAt` 을 절대적인 신선도로 단정하지 않는다.** 백엔드는 인메모리 최신 위치가 없으면
 * `vehicle_current_status` 의 좌표로 폴백하는데(answer188 P1), 그 테이블의 `received_at` 컬럼은
 * 위치 메시지와 상태 메시지가 함께 쓰는 값이다. 즉 **폴백된 좌표는 실제 위치 수신 시각보다 새로
 * 보일 수 있다.** 현재 DTO 로는 두 출처를 구분할 방법이 없다(구분용 필드를 새로 넣는 것은 계약 변경이라
 * 범위 밖). 따라서 이 판정은 "이 값이 낡았을 수 있다"는 **하한 경고**로만 쓰고, 반대로 "fresh 니까
 * 확실히 최신"이라는 보장으로는 읽지 말 것.
 */

/** 이만큼 지나면 수신 지연으로 본다. */
export const LOCATION_DELAYED_AFTER_MS = 5_000

/** 이만큼 지나면 통신 두절을 의심한다. */
export const LOCATION_STALE_AFTER_MS = 30_000

export type LocationFreshnessLevel =
  | "missing"
  | "fresh"
  | "delayed"
  | "stale"

export interface LocationFreshness {
  level: LocationFreshnessLevel
  /** 경과 시간(ms). 수신 기록이 없으면 null. */
  ageMs: number | null
  /** "방금" / "8초 전" / "3분 전" / "2시간 전". 수신 기록이 없으면 "—". */
  ageLabel: string
  /** 사용자에게 보여줄 경고 문구. 정상이면 null. */
  statusLabel: string | null
}

const MISSING: LocationFreshness = {
  level: "missing",
  ageMs: null,
  ageLabel: "—",
  statusLabel: "위치 미수신",
}

/**
 * 마지막 수신 시각으로 신선도를 판정한다.
 *
 * @param receivedAt 백엔드 수신 시각(ISO-8601). 차량 발신 시각(`messageAt`)이 아니다 — 차량 시계가
 *                   어긋나도 흔들리지 않아야 하므로 서버가 찍은 시각을 쓴다.
 * @param nowMs      기준 시각. 테스트가 시간을 고정할 수 있도록 주입 가능하게 둔다.
 */
export function getLocationFreshness(
  receivedAt: string | null | undefined,
  nowMs: number = Date.now(),
): LocationFreshness {
  if (!receivedAt) return MISSING

  const receivedMs = Date.parse(receivedAt)
  if (Number.isNaN(receivedMs)) return MISSING

  // 브라우저 시계가 서버보다 뒤처져 있으면 경과 시간이 음수가 된다. "-3초 전" 같은 문구는 값이
  // 잘못됐다는 인상을 주므로 0 으로 보정한다(시계 차이는 사용자가 어찌할 수 없는 문제다).
  const ageMs = Math.max(0, nowMs - receivedMs)

  if (ageMs >= LOCATION_STALE_AFTER_MS) {
    return { level: "stale", ageMs, ageLabel: toAgeLabel(ageMs), statusLabel: "통신 두절 의심" }
  }
  if (ageMs >= LOCATION_DELAYED_AFTER_MS) {
    return { level: "delayed", ageMs, ageLabel: toAgeLabel(ageMs), statusLabel: "수신 지연" }
  }
  return { level: "fresh", ageMs, ageLabel: toAgeLabel(ageMs), statusLabel: null }
}

/** 이 신선도에서 좌표·방향·속도를 "지금 값"으로 믿어도 되는지. */
export function isLocationOutdated(level: LocationFreshnessLevel): boolean {
  return level === "delayed" || level === "stale"
}

function toAgeLabel(ageMs: number): string {
  if (ageMs < LOCATION_DELAYED_AFTER_MS) return "방금"

  const seconds = Math.floor(ageMs / 1000)
  if (seconds < 60) return `${seconds}초 전`

  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}분 전`

  return `${Math.floor(minutes / 60)}시간 전`
}
