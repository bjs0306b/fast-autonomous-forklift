// 창고 월드 좌표 <-> 화면 퍼센트 좌표 변환 유틸
//
// TODO(coordinate): 실제 창고 좌표 범위(min/max)와 원점은 백엔드 코드에서 확인할 수 없다.
//   → ROS2 / Isaac Sim 담당자 협의 후 DEFAULT_WORLD_BOUNDS 를 교체한다.
// TODO(coordinate): heading 0도 기준축(+X 인지 +Y 인지)과 회전 방향(시계/반시계)도 미확정이다.
//   → 협의 결과에 따라 MiniMapVehicleMarker 의 회전 계산을 조정한다.

export interface WorldBounds {
  minX: number
  maxX: number
  minY: number
  maxY: number
}

/**
 * 임시 창고 bounds. 실제 창고 규격이 확정되면 교체한다.
 * 백엔드는 좌표 범위를 검증하지 않으므로(NaN/Infinity 만 거부) 이 값은 프론트 표시용 가정이다.
 */
export const DEFAULT_WORLD_BOUNDS: WorldBounds = {
  minX: 0,
  maxX: 30,
  minY: 0,
  maxY: 20,
}

export interface PercentPosition {
  /** 0 ~ 100 (%) */
  left: number
  /** 0 ~ 100 (%) */
  top: number
}

export interface WorldToPercentOptions {
  bounds?: WorldBounds
  /**
   * 월드 Y축 증가 방향을 화면 위쪽으로 뒤집을지 여부.
   *
   * 현재 기본값은 false — y 가 커질수록 화면 아래(top 증가)로 매핑한다.
   * 실제 좌표계가 확정되면 이 값만 바꾸면 되도록 상수가 아니라 옵션으로 분리했다.
   */
  invertY?: boolean
}

/** 좌표계 확정 전까지 사용하는 Y축 반전 기본값. */
export const DEFAULT_INVERT_Y = false

function clamp(value: number, min = 0, max = 100): number {
  return Math.min(max, Math.max(min, value))
}

/**
 * 월드 좌표(x, y)를 화면 내부 퍼센트 좌표(left, top)로 변환한다.
 *
 * - 결과는 항상 0~100 으로 clamp 되어 마커가 미니맵 밖으로 나가지 않는다.
 * - bounds 의 span 이 0 이면 0 나눗셈이 되므로 1 로 대체한다.
 * - 좌표가 유한한 숫자가 아니면 호출자가 걸러야 한다(이 함수는 NaN 을 clamp 로 흡수하지 않는다).
 */
export function worldToPercent(
  x: number,
  y: number,
  optionsOrBounds?: WorldToPercentOptions | WorldBounds,
): PercentPosition {
  // 기존 호출부 호환: 세 번째 인자로 WorldBounds 를 직접 넘기던 형태도 계속 지원한다.
  const options: WorldToPercentOptions =
    optionsOrBounds && "minX" in optionsOrBounds
      ? { bounds: optionsOrBounds }
      : (optionsOrBounds ?? {})

  const bounds = options.bounds ?? DEFAULT_WORLD_BOUNDS
  const invertY = options.invertY ?? DEFAULT_INVERT_Y

  const spanX = bounds.maxX - bounds.minX || 1
  const spanY = bounds.maxY - bounds.minY || 1

  const left = ((x - bounds.minX) / spanX) * 100
  const rawTop = ((y - bounds.minY) / spanY) * 100
  const top = invertY ? 100 - rawTop : rawTop

  return {
    left: clamp(left),
    top: clamp(top),
  }
}
