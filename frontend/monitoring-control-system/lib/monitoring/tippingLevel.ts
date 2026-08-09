/**
 * 전복 위험 등급의 화면 표기.
 *
 * <b>편하중과 다른 지표다.</b> 편하중(`load_balance`)은 화물이 자기 폭 대비 쏠렸는지를
 * 보고, 전복은 <i>무게중심이 파렛트 지지면을 벗어나는가</i>를 본다. 잘 쌓았어도 파렛트
 * 가장자리에 얹으면 WARNING 이 나온다 — 그래서 "BALANCED = SAFE" 가 아니다.
 *
 * <b>등급은 AI 가 정하고 백엔드가 저장한 값을 그대로 쓴다.</b> 이탈률 임계(60%/85%)를
 * 프론트에서 다시 계산하지 않는다 — 같은 규칙이 두 곳에 생기면 한쪽만 바뀌었을 때
 * 화면과 저장값이 조용히 어긋난다.
 */

export const TIPPING_LEVEL_LABEL: Record<string, string> = {
  SAFE: "안전",
  WARNING: "주의",
  DANGER: "높음",
}

/** 색은 MeasurementFailureCard 의 amber(경고)/red(위험) 조합을 그대로 따른다. */
const TIPPING_LEVEL_CLASS: Record<string, string> = {
  SAFE: "border-emerald-500/50 bg-emerald-950/70 text-emerald-200",
  WARNING: "border-amber-500/50 bg-amber-950/70 text-amber-200",
  DANGER: "border-red-500/50 bg-red-950/70 text-red-200",
}

/** 모르는 값은 원문 그대로 보여 준다 — 임의로 등급을 지어내지 않는다. */
export function formatTippingLevel(level: string | null | undefined): string | null {
  if (!level) return null
  return TIPPING_LEVEL_LABEL[level.toUpperCase()] ?? level
}

export interface TippingBadgeView {
  label: string
  className: string
  /** 스크린리더·테스트용 원본 등급(대문자). */
  level: string
}

/**
 * 배지로 그릴 수 있으면 표기 정보를, 아니면 `null`.
 *
 * <b>값이 없으면 아무것도 그리지 않는다.</b> "판정 없음"을 회색 배지로 채우면 측정이
 * 끝나 안전 판정이 난 것처럼 보인다 — 없는 것은 없는 채로 둔다.
 */
export function toTippingBadge(level: string | null | undefined): TippingBadgeView | null {
  const label = formatTippingLevel(level)
  if (!label || !level) return null
  const upper = level.toUpperCase()
  return {
    label,
    level: upper,
    // 모르는 등급도 문구는 보여 주되 색은 중립으로 — 초록으로 칠하면 안전으로 오해된다.
    className: TIPPING_LEVEL_CLASS[upper] ?? "border-slate-500/50 bg-slate-900/70 text-slate-200",
  }
}
