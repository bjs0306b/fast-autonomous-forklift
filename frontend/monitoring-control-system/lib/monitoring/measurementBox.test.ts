/**
 * 검출 상자 좌표 환산 테스트.
 *
 * 이 프로젝트에는 프론트 테스트 프레임워크가 없다. 새로 도입하지 않고 Node 내장 러너로
 * 돌린다(lib/monitoring/locationFreshness.test.ts 와 같은 방식).
 *
 *     node --test lib/monitoring/measurementBox.test.ts
 */
import assert from "node:assert/strict"
import { describe, it } from "node:test"

import { toBoxRect, toBoxRects } from "./measurementBox.ts"

describe("toBoxRect", () => {
  it("픽셀 좌표를 프레임 기준 %로 바꾼다", () => {
    const rect = toBoxRect({ bboxPx: [192, 108, 384, 324], score: 0.91 }, 1920, 1080)
    assert.ok(rect)
    assert.equal(rect.leftPct, 10)
    assert.equal(rect.topPct, 10)
    assert.equal(rect.widthPct, 10)
    assert.equal(rect.heightPct, 20)
    assert.equal(rect.score, 0.91)
  })

  /** 기준이 없으면 어디에 그릴지 정할 수 없다 — 임의 값으로 그리면 엉뚱한 자리가 된다. */
  it("프레임 크기를 모르면 그리지 않는다", () => {
    assert.equal(toBoxRect({ bboxPx: [0, 0, 10, 10], score: 1 }, null, 1080), null)
    assert.equal(toBoxRect({ bboxPx: [0, 0, 10, 10], score: 1 }, 1920, null), null)
    assert.equal(toBoxRect({ bboxPx: [0, 0, 10, 10], score: 1 }, 0, 1080), null)
  })

  it("좌표가 4개가 아니면 그리지 않는다", () => {
    assert.equal(toBoxRect({ bboxPx: [0, 0, 10], score: 1 }, 1920, 1080), null)
    assert.equal(toBoxRect({ bboxPx: [], score: 1 }, 1920, 1080), null)
  })

  /** 뒤집힌 좌표는 검출이 잘못됐다는 신호다. 좌우를 바꿔 살리면 정상처럼 보인다. */
  it("폭이나 높이가 0 이하면 그리지 않는다", () => {
    assert.equal(toBoxRect({ bboxPx: [100, 0, 50, 10], score: 1 }, 1920, 1080), null)
    assert.equal(toBoxRect({ bboxPx: [0, 100, 10, 50], score: 1 }, 1920, 1080), null)
    assert.equal(toBoxRect({ bboxPx: [10, 10, 10, 20], score: 1 }, 1920, 1080), null)
  })

  it("score 가 없으면 null 로 둔다", () => {
    const rect = toBoxRect({ bboxPx: [0, 0, 192, 108], score: null }, 1920, 1080)
    assert.ok(rect)
    assert.equal(rect.score, null)
  })

  it("숫자가 아닌 좌표는 그리지 않는다", () => {
    // 백엔드가 깨진 JSON 을 흘려보낸 경우
    const broken = { bboxPx: [0, 0, "10", 10] as unknown as number[], score: 1 }
    assert.equal(toBoxRect(broken, 1920, 1080), null)
  })
})

describe("toBoxRects", () => {
  it("여러 상자를 한 번에 바꾼다", () => {
    const rects = toBoxRects(
      [
        { bboxPx: [0, 0, 192, 108], score: 0.9 },
        { bboxPx: [960, 540, 1152, 648], score: 0.8 },
      ],
      1920,
      1080,
    )
    assert.equal(rects.length, 2)
    assert.equal(rects[1].leftPct, 50)
    assert.equal(rects[1].topPct, 50)
  })

  /** 하나가 깨졌다고 나머지를 못 그리면 안 된다. */
  it("깨진 상자만 빼고 나머지는 그린다", () => {
    const rects = toBoxRects(
      [
        { bboxPx: [0, 0, 192, 108], score: 0.9 },
        { bboxPx: [100, 0, 50, 10], score: 0.5 }, // 뒤집힘
      ],
      1920,
      1080,
    )
    assert.equal(rects.length, 1)
    assert.equal(rects[0].score, 0.9)
  })

  it("목록이 없으면 빈 배열", () => {
    assert.deepEqual(toBoxRects(null, 1920, 1080), [])
    assert.deepEqual(toBoxRects(undefined, 1920, 1080), [])
    assert.deepEqual(toBoxRects([], 1920, 1080), [])
  })
})
