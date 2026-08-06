/**
 * Isaac Sim 좌표가 미니맵 어디에 찍히는지 확인하는 테스트.
 *
 * 좌표 변환 코드를 바꾸지 않고 **현재 동작을 고정**하는 것이 목적이다. Isaac 이 보내는 값이 창고 맵
 * 범위(x 0~20, y 0~30) 안에 들어오긴 하지만 원점 근처에 몰려 있어서, 나중에 "마커가 왜 구석에 있지"를
 * 추적할 때 이 테스트가 기준점이 된다.
 *
 *     node --test lib/monitoring/isaacCoordinate.test.ts
 */
import assert from "node:assert/strict"
import { describe, it } from "node:test"

import { worldToPercent } from "../coordinate.ts"
import { WAREHOUSE_WORLD_BOUNDS } from "../config/warehouseMap.ts"

const OPTIONS = { bounds: WAREHOUSE_WORLD_BOUNDS, invertY: true }

describe("Isaac Sim 좌표 → 미니맵 퍼센트", () => {
  it("창고 맵 범위는 x 0~20, y 0~30 이다", () => {
    assert.deepEqual(WAREHOUSE_WORLD_BOUNDS, { minX: 0, maxX: 20, minY: 0, maxY: 30 })
  })

  it("sim01(1.55, 0.4)과 sim02(1.0, 0.4)는 서로 다른 x 위치에 찍힌다", () => {
    const sim01 = worldToPercent(1.55, 0.4, OPTIONS)
    const sim02 = worldToPercent(1.0, 0.4, OPTIONS)

    assert.notEqual(sim01.left, sim02.left)
    assert.equal(sim01.left, (1.55 / 20) * 100) // 7.75%
    assert.equal(sim02.left, (1.0 / 20) * 100) // 5%
  })

  it("y 축은 반전된다 — 월드 y 가 작을수록 화면 아래쪽이다", () => {
    const low = worldToPercent(1.0, 0.4, OPTIONS)
    const high = worldToPercent(1.0, 20.0, OPTIONS)

    assert.ok(low.top > high.top)
    assert.equal(low.top, 100 - (0.4 / 30) * 100) // 98.67%
  })

  it("Isaac 좌표는 맵 범위 안이지만 좌측 하단 모서리에 몰린다", () => {
    // clamp 에 걸린 것이 아니라 실제로 원점 근처라는 뜻이다. 좌표계 원점이 다른지 확인이 필요하다.
    const sim01 = worldToPercent(1.55, 0.4, OPTIONS)

    assert.ok(sim01.left > 0 && sim01.left < 10)
    assert.ok(sim01.top > 90 && sim01.top < 100)
  })

  it("범위를 벗어난 좌표는 0~100 으로 clamp 된다", () => {
    assert.equal(worldToPercent(-5, 0.4, OPTIONS).left, 0)
    assert.equal(worldToPercent(999, 0.4, OPTIONS).left, 100)
  })
})
