// 창고 월드 좌표 <-> 화면 퍼센트 좌표 변환 유틸
//
// 좌표 범위와 Y축 방향의 근거는 `lib/config/warehouseMap.ts` 에 모여 있다(시뮬 맵 생성
// 스크립트 isaac_sim/nav2/make_map.py 의 WORLD_W/WORLD_H/ORIGIN/world_to_px 기준).
//
// TODO(coordinate): heading 0도 기준축(+X 인지 +Y 인지)과 회전 방향(시계/반시계)은 여전히
//   미확정이다. → 협의 결과에 따라 warehouseMap.ts 의 headingToMarkerRotation() 을 조정한다.

export interface WorldBounds {
  minX: number
  maxX: number
  minY: number
  maxY: number
}

/**
 * 창고 bounds 기본값. 시뮬 맵 정의(20m x 30m, 원점 = 왼쪽아래)를 그대로 따른다 —
 * 배경 이미지(400x600px @ 0.05m/px)와 같은 정의라 마커가 배경과 어긋나지 않는다.
 * 백엔드는 좌표 범위를 검증하지 않으므로(NaN/Infinity 만 거부) 범위를 벗어난 값은
 * {@link worldToPercent} 가 0~100 으로 clamp 한다.
 */
export const DEFAULT_WORLD_BOUNDS: WorldBounds = {
  minX: 0,
  maxX: 20,
  minY: 0,
  maxY: 30,
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
   * 기본값은 true — 맵 이미지는 행이 위에서 아래로 증가하는데 월드 Y 는 아래에서 위로
   * 증가하기 때문이다(make_map.py 의 world_to_px 가 행을 뒤집어 이미지를 만든다).
   * 좌표계가 바뀌면 이 값만 바꾸면 되도록 상수가 아니라 옵션으로 분리했다.
   */
  invertY?: boolean
}

/** 맵 이미지 기준 Y축 반전 기본값. 근거는 lib/config/warehouseMap.ts 참고. */
export const DEFAULT_INVERT_Y = true

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
