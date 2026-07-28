// 화면 표시용 포맷 유틸.
//
// 백엔드 시각 필드는 두 종류다.
//  - OffsetDateTime: "2026-07-28T11:20:27+09:00" (오프셋 포함) — dashboard 의 시각 필드는 전부 이 형태
//  - LocalDateTime : "2026-07-28T11:20:27"       (오프셋 없음) — 차량 createdAt 등, 이 화면에서는 쓰지 않음
// 오프셋이 없는 문자열을 new Date() 로 파싱하면 브라우저 로컬 타임존으로 해석되어
// 한국 외 환경에서 어긋날 수 있다. 이 화면은 오프셋 포함 필드만 다룬다.

/** 숫자를 고정 소수점으로 표시한다. null/undefined/비유한 값은 "—". */
export function formatNumber(value: number | null | undefined, digits = 2): string {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    return "—"
  }
  return value.toFixed(digits)
}

/** ISO-8601 시각을 HH:mm:ss 로 표시한다. 파싱할 수 없으면 "—". */
export function formatClockTime(value: string | null | undefined): string {
  if (!value) {
    return "—"
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return "—"
  }
  return date.toLocaleTimeString("ko-KR", { hour12: false })
}
