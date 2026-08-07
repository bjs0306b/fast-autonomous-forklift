/**
 * AI 측정 검출 상자의 픽셀 좌표를 화면 표시 좌표(%)로 환산한다.
 *
 * <b>왜 % 인가</b>: 영상 요소의 실제 크기는 반응형이라 렌더 시점마다 다르다. px 로 계산해
 * 두면 창 크기가 바뀔 때마다 사각형이 어긋난다. 부모를 기준으로 한 %로 두면 CSS 가
 * 알아서 따라간다.
 *
 * <b>주의 — 이 좌표는 지게차 이동에 쓸 수 없다.</b> 카메라 화면 안의 픽셀 위치일 뿐이고,
 * 창고 맵 좌표로 바꾸려면 카메라 설치 위치·각도(외부 파라미터)가 필요한데 그 값은 없다.
 */

/** 백엔드 `MeasurementBox` 와 같은 모양. */
export interface MeasurementBox {
  /** `[x1, y1, x2, y2]` — 좌상단·우하단 픽셀 */
  bboxPx: number[]
  score: number | null
}

/** CSS 로 바로 쓸 수 있는 사각형(모두 % 단위). */
export interface BoxRect {
  leftPct: number
  topPct: number
  widthPct: number
  heightPct: number
  score: number | null
}

/**
 * 픽셀 상자를 % 사각형으로 바꾼다. 그릴 수 없는 값이면 `null`.
 *
 * 걸러내는 경우와 그 이유:
 *   - 프레임 크기를 모름 → 환산 기준이 없다. 임의 값으로 그리면 엉뚱한 자리에 사각형이 생긴다
 *   - 좌표가 4개가 아니거나 숫자가 아님 → 모양이 깨진 입력
 *   - 폭·높이가 0 이하 → 뒤집힌 좌표. 좌우를 바꿔 살리지 않는다.
 *     검출이 잘못됐다는 신호인데 그리면 정상처럼 보인다
 */
export function toBoxRect(
  box: MeasurementBox | null | undefined,
  frameWidth: number | null | undefined,
  frameHeight: number | null | undefined,
): BoxRect | null {
  if (!box || !Array.isArray(box.bboxPx) || box.bboxPx.length !== 4) return null
  if (!isPositive(frameWidth) || !isPositive(frameHeight)) return null

  const [x1, y1, x2, y2] = box.bboxPx
  if (![x1, y1, x2, y2].every((v) => typeof v === "number" && Number.isFinite(v))) return null

  const w = x2 - x1
  const h = y2 - y1
  if (w <= 0 || h <= 0) return null

  return {
    leftPct: (x1 / frameWidth!) * 100,
    topPct: (y1 / frameHeight!) * 100,
    widthPct: (w / frameWidth!) * 100,
    heightPct: (h / frameHeight!) * 100,
    score: box.score ?? null,
  }
}

/** 그릴 수 있는 것만 남긴다. 하나가 깨져도 나머지는 그린다. */
export function toBoxRects(
  boxes: MeasurementBox[] | null | undefined,
  frameWidth: number | null | undefined,
  frameHeight: number | null | undefined,
): BoxRect[] {
  if (!Array.isArray(boxes)) return []
  return boxes
    .map((box) => toBoxRect(box, frameWidth, frameHeight))
    .filter((rect): rect is BoxRect => rect !== null)
}

function isPositive(value: number | null | undefined): boolean {
  return typeof value === "number" && Number.isFinite(value) && value > 0
}
