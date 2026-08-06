/**
 * 위치 신선도 판정 테스트.
 *
 * 이 프로젝트에는 프론트 테스트 프레임워크가 없다. 새로 도입하지 않고 **Node 내장 테스트 러너**로
 * 돌린다(Node 22+ 는 TypeScript 를 그대로 실행한다).
 *
 *     node --test lib/monitoring/locationFreshness.test.ts
 *
 * 그래서 대상 모듈은 의존성이 없는 순수 함수여야 하고, import 는 경로 별칭(@/) 이 아니라 상대 경로여야
 * 한다 — 별칭은 번들러가 푸는 것이라 Node 가 알지 못한다.
 */
import assert from "node:assert/strict"
import { describe, it } from "node:test"

import {
  LOCATION_DELAYED_AFTER_MS,
  LOCATION_STALE_AFTER_MS,
  getLocationFreshness,
  isLocationOutdated,
} from "./locationFreshness.ts"

const NOW = Date.parse("2026-08-05T14:32:07+09:00")

/** NOW 기준으로 주어진 밀리초만큼 과거인 ISO 문자열. */
function agoIso(ms: number): string {
  return new Date(NOW - ms).toISOString()
}

describe("getLocationFreshness", () => {
  it("receivedAt 이 없으면 missing", () => {
    for (const value of [null, undefined, ""]) {
      const result = getLocationFreshness(value, NOW)
      assert.equal(result.level, "missing")
      assert.equal(result.ageMs, null)
      assert.equal(result.ageLabel, "—")
      assert.equal(result.statusLabel, "위치 미수신")
    }
  })

  it("잘못된 날짜 문자열이면 missing", () => {
    const result = getLocationFreshness("어제쯤", NOW)
    assert.equal(result.level, "missing")
    assert.equal(result.ageMs, null)
  })

  it("3초 전이면 fresh 이고 '방금'", () => {
    const result = getLocationFreshness(agoIso(3_000), NOW)
    assert.equal(result.level, "fresh")
    assert.equal(result.ageMs, 3_000)
    assert.equal(result.ageLabel, "방금")
    assert.equal(result.statusLabel, null)
  })

  it("8초 전이면 delayed 이고 '8초 전'", () => {
    const result = getLocationFreshness(agoIso(8_000), NOW)
    assert.equal(result.level, "delayed")
    assert.equal(result.ageLabel, "8초 전")
    assert.equal(result.statusLabel, "수신 지연")
  })

  it("40초 전이면 stale 이고 '40초 전'", () => {
    const result = getLocationFreshness(agoIso(40_000), NOW)
    assert.equal(result.level, "stale")
    assert.equal(result.ageLabel, "40초 전")
    assert.equal(result.statusLabel, "통신 두절 의심")
  })

  it("3분 전이면 stale 이고 '3분 전'", () => {
    const result = getLocationFreshness(agoIso(3 * 60_000), NOW)
    assert.equal(result.level, "stale")
    assert.equal(result.ageLabel, "3분 전")
  })

  it("2시간 전이면 '시간' 단위로 표시한다", () => {
    const result = getLocationFreshness(agoIso(2 * 60 * 60_000), NOW)
    assert.equal(result.level, "stale")
    assert.equal(result.ageLabel, "2시간 전")
  })

  it("미래 시각이면 경과 시간을 0 으로 보정하고 fresh", () => {
    // 브라우저 시계가 서버보다 뒤처진 경우다. "-10초 전" 같은 문구를 내보내지 않는다.
    const result = getLocationFreshness(new Date(NOW + 10_000).toISOString(), NOW)
    assert.equal(result.level, "fresh")
    assert.equal(result.ageMs, 0)
    assert.equal(result.ageLabel, "방금")
  })

  it("임계값 정확히 5초면 delayed", () => {
    const result = getLocationFreshness(agoIso(LOCATION_DELAYED_AFTER_MS), NOW)
    assert.equal(result.level, "delayed")
    assert.equal(result.ageLabel, "5초 전")
  })

  it("임계값 바로 아래(4999ms)면 fresh", () => {
    const result = getLocationFreshness(agoIso(LOCATION_DELAYED_AFTER_MS - 1), NOW)
    assert.equal(result.level, "fresh")
  })

  it("임계값 정확히 30초면 stale", () => {
    const result = getLocationFreshness(agoIso(LOCATION_STALE_AFTER_MS), NOW)
    assert.equal(result.level, "stale")
    assert.equal(result.ageLabel, "30초 전")
  })

  it("임계값 바로 아래(29999ms)면 delayed", () => {
    const result = getLocationFreshness(agoIso(LOCATION_STALE_AFTER_MS - 1), NOW)
    assert.equal(result.level, "delayed")
  })

  it("시간이 흐르면 fresh 에서 delayed 로 바뀐다(타이머가 하는 일)", () => {
    const receivedAt = agoIso(0)
    assert.equal(getLocationFreshness(receivedAt, NOW).level, "fresh")
    assert.equal(getLocationFreshness(receivedAt, NOW + 4_000).level, "fresh")
    assert.equal(getLocationFreshness(receivedAt, NOW + 6_000).level, "delayed")
    assert.equal(getLocationFreshness(receivedAt, NOW + 31_000).level, "stale")
  })
})

describe("isLocationOutdated", () => {
  it("delayed 와 stale 만 낡은 값으로 본다", () => {
    assert.equal(isLocationOutdated("fresh"), false)
    assert.equal(isLocationOutdated("missing"), false)
    assert.equal(isLocationOutdated("delayed"), true)
    assert.equal(isLocationOutdated("stale"), true)
  })
})
