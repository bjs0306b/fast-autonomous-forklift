import assert from "node:assert/strict"
import test from "node:test"

import { formatTippingLevel, toTippingBadge } from "./tippingLevel.ts"

test("등급을 한글 문구로 바꾼다", () => {
  assert.equal(formatTippingLevel("SAFE"), "안전")
  assert.equal(formatTippingLevel("WARNING"), "주의")
  assert.equal(formatTippingLevel("DANGER"), "높음")
})

test("AI 가 보내는 소문자도 받는다", () => {
  // 스테이션은 `safe` 로 보내고 백엔드가 대문자화한다. 어느 쪽이 와도 같게 읽혀야
  // 한 곳이 대문자화를 그만둬도 화면이 조용히 비지 않는다.
  assert.equal(formatTippingLevel("safe"), "안전")
  assert.equal(toTippingBadge("warning")?.level, "WARNING")
})

test("값이 없으면 배지를 그리지 않는다", () => {
  // 회색 배지로 채우면 "판정 없음"이 "측정이 끝나 안전"으로 보인다.
  assert.equal(toTippingBadge(null), null)
  assert.equal(toTippingBadge(undefined), null)
  assert.equal(toTippingBadge(""), null)
})

test("등급마다 색이 다르다", () => {
  const safe = toTippingBadge("SAFE")!
  const warning = toTippingBadge("WARNING")!
  const danger = toTippingBadge("DANGER")!
  assert.notEqual(safe.className, warning.className)
  assert.notEqual(warning.className, danger.className)
})

test("모르는 등급은 문구는 그대로, 색은 중립으로", () => {
  // 초록으로 칠하면 모르는 값이 안전으로 오해된다.
  const unknown = toTippingBadge("TOPPLED")!
  assert.equal(unknown.label, "TOPPLED")
  assert.equal(unknown.className, toTippingBadge("ZZZ")!.className)
  assert.notEqual(unknown.className, toTippingBadge("SAFE")!.className)
})
