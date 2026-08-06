export interface Point {
  x: number
  y: number
}

export interface Rectangle {
  left: number
  top: number
  right: number
  bottom: number
}

/**
 * 드래그 시작 시점의 오버레이 사각형을 기준으로 다음 이동량을 컨테이너 안에 가둔다.
 *
 * CSS의 초기 배치(right/top)는 그대로 두고 transform 이동량만 계산하므로, 반응형 폭 규칙을
 * 복제하지 않아도 된다. 오버레이가 컨테이너보다 큰 극단적인 경우에는 왼쪽·위쪽을 우선 맞춘다.
 */
export function clampOverlayOffset(
  startOffset: Point,
  pointerDelta: Point,
  overlayAtDragStart: Rectangle,
  container: Rectangle,
): Point {
  const minX = startOffset.x + container.left - overlayAtDragStart.left
  const maxX = startOffset.x + container.right - overlayAtDragStart.right
  const minY = startOffset.y + container.top - overlayAtDragStart.top
  const maxY = startOffset.y + container.bottom - overlayAtDragStart.bottom

  return {
    x: clamp(startOffset.x + pointerDelta.x, minX, Math.max(minX, maxX)),
    y: clamp(startOffset.y + pointerDelta.y, minY, Math.max(minY, maxY)),
  }
}

function clamp(value: number, minimum: number, maximum: number) {
  return Math.min(maximum, Math.max(minimum, value))
}
