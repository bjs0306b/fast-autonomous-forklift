// 창고 월드 좌표 <-> 화면 퍼센트 좌표 변환 유틸
// 실제 창고 물리 좌표계(단위/원점/축 방향)는 연동 단계에서 확정한다.

export interface WorldBounds {
  minX: number
  maxX: number
  minY: number
  maxY: number
}

/**
 * 스캐폴딩용 임시 창고 bounds.
 * 실제 창고 규격이 확정되면 교체한다.
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

function clamp(value: number, min = 0, max = 100): number {
  return Math.min(max, Math.max(min, value))
}

/**
 * 월드 좌표(x, y)를 화면 내부 퍼센트 좌표(left, top)로 변환한다.
 *
 * TODO(coordinate): 월드 Y축의 0도 기준과 증가 방향(위/아래)이 미확정이다.
 * 현재는 y가 커질수록 화면 아래(top 증가)로 매핑한다. 실제 좌표계 확정 시 반전 여부를 조정한다.
 */
export function worldToPercent(
  x: number,
  y: number,
  bounds: WorldBounds = DEFAULT_WORLD_BOUNDS,
): PercentPosition {
  const spanX = bounds.maxX - bounds.minX || 1
  const spanY = bounds.maxY - bounds.minY || 1

  const left = ((x - bounds.minX) / spanX) * 100
  const top = ((y - bounds.minY) / spanY) * 100

  return {
    left: clamp(left),
    top: clamp(top),
  }
}
