import assert from "node:assert/strict"
import test from "node:test"
import { clampOverlayOffset } from "./draggableOverlay.ts"

const container = { left: 0, top: 0, right: 1000, bottom: 700 }
const overlay = { left: 600, top: 50, right: 900, bottom: 250 }

test("컨테이너 안쪽 이동량은 그대로 유지한다", () => {
  assert.deepEqual(
    clampOverlayOffset({ x: 0, y: 0 }, { x: -200, y: 150 }, overlay, container),
    { x: -200, y: 150 },
  )
})

test("왼쪽과 위쪽 경계를 넘지 않는다", () => {
  assert.deepEqual(
    clampOverlayOffset({ x: 0, y: 0 }, { x: -800, y: -300 }, overlay, container),
    { x: -600, y: -50 },
  )
})

test("오른쪽과 아래쪽 경계를 넘지 않는다", () => {
  assert.deepEqual(
    clampOverlayOffset({ x: 0, y: 0 }, { x: 500, y: 900 }, overlay, container),
    { x: 100, y: 450 },
  )
})

test("기존 transform 이동량을 기준으로 다음 위치를 계산한다", () => {
  assert.deepEqual(
    clampOverlayOffset({ x: -100, y: 80 }, { x: -50, y: 20 }, overlay, container),
    { x: -150, y: 100 },
  )
})
