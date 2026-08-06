/**
 * Isaac Sim 스트림(MediaMTX) 설정 파싱 테스트.
 *
 * 이 프로젝트에는 프론트 테스트 프레임워크가 없다. 새로 도입하지 않고 Node 내장 테스트 러너로
 * 돌린다(lib/monitoring/locationFreshness.test.ts 와 같은 방식).
 *
 *     node --test lib/config/isaacSimStream.test.ts
 *
 * import 는 경로 별칭(@/) 이 아니라 상대 경로여야 한다 — 별칭은 번들러가 푸는 것이라 Node 가
 * 알지 못한다.
 */
import assert from "node:assert/strict"
import { describe, it } from "node:test"

import {
  ISAAC_SIM_STREAM_LOAD_TIMEOUT_MS,
  ISAAC_SIM_STREAM_PROBE_TIMEOUT_MS,
  ISAAC_SIM_STREAM_RETRY_DELAY_MS,
  isStreamUrlConfigured,
  parseStreamEnabled,
  parseStreamUrl,
} from "./isaacSimStream.ts"

describe("parseStreamUrl", () => {
  it("값이 없으면 기본 MediaMTX URL을 쓴다", () => {
    assert.equal(parseStreamUrl(undefined), "http://i15a304.p.ssafy.io:8889/sim")
  })

  it("공백만 있으면 기본값을 쓴다", () => {
    assert.equal(parseStreamUrl("   "), "http://i15a304.p.ssafy.io:8889/sim")
  })

  it("값이 있으면 앞뒤 공백만 제거하고 그대로 쓴다", () => {
    assert.equal(parseStreamUrl("  http://example.com:8889/sim  "), "http://example.com:8889/sim")
  })

  it("형식을 검증하지 않는다 — 잘못된 문자열도 그대로 통과시킨다", () => {
    assert.equal(parseStreamUrl("not-a-url"), "not-a-url")
  })
})

describe("parseStreamEnabled", () => {
  it("값이 없으면 켜진 것으로 본다", () => {
    assert.equal(parseStreamEnabled(undefined), true)
  })

  it("정확히 \"false\" 면 끈다(대소문자 무시)", () => {
    assert.equal(parseStreamEnabled("false"), false)
    assert.equal(parseStreamEnabled("FALSE"), false)
    assert.equal(parseStreamEnabled(" False "), false)
  })

  it("오타나 다른 값이면 켜진 것으로 본다", () => {
    assert.equal(parseStreamEnabled("flase"), true)
    assert.equal(parseStreamEnabled("0"), true)
    assert.equal(parseStreamEnabled("true"), true)
    assert.equal(parseStreamEnabled(""), true)
  })
})

describe("isStreamUrlConfigured", () => {
  it("빈 문자열/공백/null/undefined는 미설정으로 본다", () => {
    assert.equal(isStreamUrlConfigured(""), false)
    assert.equal(isStreamUrlConfigured("   "), false)
    assert.equal(isStreamUrlConfigured(null), false)
    assert.equal(isStreamUrlConfigured(undefined), false)
  })

  it("공백이 아닌 문자열이 있으면 설정된 것으로 본다", () => {
    assert.equal(isStreamUrlConfigured("http://i15a304.p.ssafy.io:8889/sim"), true)
  })
})

describe("타이밍 상수", () => {
  it("로드 타임아웃은 권장 범위(8~12초) 안에 있다", () => {
    assert.ok(ISAAC_SIM_STREAM_LOAD_TIMEOUT_MS >= 8_000)
    assert.ok(ISAAC_SIM_STREAM_LOAD_TIMEOUT_MS <= 12_000)
  })

  it("재시도 지연은 양수다", () => {
    assert.ok(ISAAC_SIM_STREAM_RETRY_DELAY_MS > 0)
  })

  it("네트워크 사전 확인 타임아웃은 iframe load 타임아웃보다 짧다", () => {
    // 먼저 도달성을 확인한 뒤에만 iframe 을 붙이므로, 전체 대기 시간이 두 배로 늘어나지
    // 않으려면 사전 확인이 더 짧아야 한다.
    assert.ok(ISAAC_SIM_STREAM_PROBE_TIMEOUT_MS > 0)
    assert.ok(ISAAC_SIM_STREAM_PROBE_TIMEOUT_MS < ISAAC_SIM_STREAM_LOAD_TIMEOUT_MS)
  })
})
