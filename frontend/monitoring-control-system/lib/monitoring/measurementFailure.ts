import { formatTippingLevel } from "@/lib/monitoring/tippingLevel"
import type { DashboardFailure } from "@/types/monitoring"

/**
 * 실패 원인 코드 → 관제 화면 문구.
 *
 * 문구를 여기 한 곳에만 두는 이유: 백엔드가 사람이 읽을 문장을 만들어 내려보내면 같은 의미가
 * 백엔드 문자열과 화면 문구 두 벌로 갈라지고, 한쪽만 고쳐져 조용히 어긋난다. 백엔드는 코드와
 * 원본 측정값만 주고 표현은 프론트가 책임진다.
 *
 * 컴포넌트 안에서 긴 if 문을 반복하지 않도록 이 모듈만 호출하게 한다.
 */

export type MeasurementFailureSeverity = "warning" | "error"

export interface MeasurementFailureDetail {
  label: string
  /** 실제로 존재하는 값만 담긴다 — 값이 없는 항목은 목록에서 빠진다. */
  value: string
}

export interface MeasurementFailureView {
  title: string
  description: string
  action?: string
  severity: MeasurementFailureSeverity
  details: MeasurementFailureDetail[]
}

interface FailureText {
  title: string
  description: string
  action?: string
  severity: MeasurementFailureSeverity
}

/**
 * 코드별 문구.
 *
 * <b>severity 는 "장비를 의심해야 하는가"로 가른다.</b>
 * warning 은 측정 자체는 이뤄졌고 판정 일부만 못 한 상태(= dimensions_only)이고,
 * error 는 사람이 무언가 조치해야 다음 작업이 진행되는 상태다. 적재 부적합은 측정이 정상이어도
 * 화물을 다시 쌓기 전에는 작업이 못 나가므로 error 다 — "경고"로 보여 주면 넘어가도 되는
 * 상태로 읽힌다.
 *
 * title 은 무슨 일이 벌어졌는지, description 은 그 의미와 확인할 것, action 은 <b>사람이 할 일</b>이다.
 * 셋의 역할을 섞지 않는다.
 */
const FAILURE_TEXT: Record<string, FailureText> = {
  MEASUREMENT_NO_DETECTION: {
    title: "화물이 감지되지 않았습니다",
    // 파렛트 유무는 판정에 영향을 주지 않는다 — 박스가 없으면 무조건 no_detection 이다.
    description: "화물이 측정 위치에 놓였는지 확인하세요.",
    action: "화물 배치 상태 확인",
    severity: "error",
  },
  MEASUREMENT_DISTANCE_UNRELIABLE: {
    title: "거리 측정에 실패했습니다",
    // 어느 센서인지 적어야 작업자가 찾아갈 수 있다. FoV 는 조준 판단에 필요한 값이라 함께 남긴다.
    description: "TF-Nova 거리 센서의 조준 상태를 확인하세요. (센서 시야각 FoV 14°)",
    action: "센서 조준 확인(FoV 14°)",
    severity: "error",
  },
  MEASUREMENT_PALLET_NOT_DETECTED: {
    title: "파렛트 미감지",
    // dimensions_only 다. 치수는 측정됐으므로 "측정 실패"와 같은 얼굴로 보여 주지 않는다.
    description: "치수는 측정되었지만 편하중·전복 판정을 수행할 수 없습니다.",
    action: "파렛트 각도와 조명 확인",
    severity: "warning",
  },
  PLACEMENT_INELIGIBLE: {
    title: "적재 부적합",
    // 측정은 정상이다. 재측정이 아니라 화물을 다시 쌓아야 한다.
    description: "화물을 다시 쌓아야 합니다.",
    action: "화물 재적재",
    severity: "error",
  },
  MEASUREMENT_NO_RESPONSE: {
    title: "측정 장비 응답 없음",
    // 백엔드가 아는 것은 "TTL 안에 결과가 오지 않았다"뿐이다. 카메라·거리 센서·네트워크 중
    // 무엇이 원인인지 단정하지 않고 셋을 함께 안내한다.
    description: "카메라·거리 센서 또는 네트워크 상태를 확인하세요.",
    action: "측정 장비와 네트워크 연결 확인",
    severity: "error",
  },
  PLACEMENT_SLOT_UNAVAILABLE: {
    title: "적재 위치 없음",
    description: "조건에 맞는 적재 위치를 찾지 못했습니다.",
    action: "창고 빈 위치 확인",
    severity: "error",
  },
}

/** 코드가 없던 시절의 실패 데이터나 아직 모르는 코드도 화면에서 사라지지 않게 한다. */
const UNKNOWN_FAILURE: FailureText = {
  title: "작업 실패",
  description: "원인이 기록되지 않았습니다",
  action: "작업 이력을 확인하세요",
  severity: "error",
}

function formatPercent(ratio: number | null): string | null {
  if (ratio == null || !Number.isFinite(ratio)) return null
  return `${(ratio * 100).toFixed(1)}%`
}

/**
 * 적재 부적합일 때만 상세값을 붙인다. 다른 실패는 애초에 전복·돌출을 판정하지 못한 상태라
 * 빈 값만 늘어놓게 되고, 그러면 작업자가 "측정은 됐는데 값이 비었다"고 오해한다.
 *
 * <b>없는 값은 항목째 빼고 만들어 내지 않는다.</b> 예전에는 null 을 "-" 로 채워 넣었는데,
 * 그러면 "판정한 적 없음"이 "0에 가까운 값"처럼 보인다.
 */
function buildDetails(failure: DashboardFailure, code: string): MeasurementFailureDetail[] {
  if (code !== "PLACEMENT_INELIGIBLE") return []
  const overhang = formatPercent(failure.overhangRatio)
  const tipping = formatTippingLevel(failure.tippingLevel)
  const details: MeasurementFailureDetail[] = []
  if (overhang) details.push({ label: "돌출 비율", value: overhang })
  if (tipping) details.push({ label: "전복 위험", value: tipping })
  return details
}

/**
 * 실패 원인을 화면 문구로 바꾼다.
 *
 * <p>우선순위는 <b>백엔드가 정한 코드를 그대로 따른다</b>. 프론트에서 원본 status 를 다시 보고
 * 판단하면 같은 규칙이 두 곳에 생겨 어긋난다. 다만 코드가 비어 있는 과거 데이터는 원본 status 로
 * 보완한다 — 그마저 없으면 "원인 기록 없음"으로 남긴다.
 *
 * @returns 실패가 없으면 null
 */
export function getMeasurementFailureView(
  failure: DashboardFailure | null | undefined,
): MeasurementFailureView | null {
  if (!failure) return null
  const code = resolveCode(failure)
  const text = FAILURE_TEXT[code] ?? UNKNOWN_FAILURE
  return { ...text, details: buildDetails(failure, code) }
}

function resolveCode(failure: DashboardFailure): string {
  if (failure.failureCode) return failure.failureCode
  switch (failure.measurementStatus) {
    case "no_detection":
      return "MEASUREMENT_NO_DETECTION"
    case "unreliable":
      return "MEASUREMENT_DISTANCE_UNRELIABLE"
    case "dimensions_only":
      return "MEASUREMENT_PALLET_NOT_DETECTED"
    case "ok":
      // 측정이 정상인데 실패로 남았다면 남은 원인은 적재 부적합뿐이다.
      return failure.placementEligible === false ? "PLACEMENT_INELIGIBLE" : "UNKNOWN"
    default:
      return "UNKNOWN"
  }
}
