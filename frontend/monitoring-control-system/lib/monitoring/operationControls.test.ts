/**
 * 운행 버튼 노출 규칙 테스트.
 *
 * 이 프로젝트에는 프론트 테스트 프레임워크가 없다. 새로 도입하지 않고 Node 내장 러너로
 * 돌린다(lib/monitoring/measurementBox.test.ts 와 같은 방식).
 *
 *     node --test lib/monitoring/operationControls.test.ts
 */
import assert from "node:assert/strict"
import { describe, it } from "node:test"

import type { OperationState, OperationStateResponse } from "../api/operationApi.ts"
import { joiningLabel, toOperationControls } from "./operationControls.ts"

function op(
  state: OperationState,
  overrides: Partial<OperationStateResponse> = {},
): OperationStateResponse {
  return {
    state,
    canStart: state === "IDLE",
    canResume: state === "PAUSED" || state === "ESTOPPED",
    joinedCount: 0,
    joinTargetCount: 0,
    joinedVehicles: [],
    heldVehicles: [],
    autoRelease: state === "RUNNING",
    ...overrides,
  }
}

describe("toOperationControls", () => {
  it("IDLE — 시작만 보인다", () => {
    const c = toOperationControls(op("IDLE"))
    assert.equal(c.showStart, true)
    assert.equal(c.showPause, false)
    assert.equal(c.showResume, false)
    assert.equal(c.showReset, false)
    assert.equal(c.label, "대기")
  })

  it("RUNNING — 일시정지와 종료", () => {
    const c = toOperationControls(op("RUNNING"))
    assert.equal(c.showStart, false)
    assert.equal(c.showPause, true)
    assert.equal(c.showResume, false)
    assert.equal(c.showReset, true)
  })

  it("PAUSED — 재개와 종료", () => {
    const c = toOperationControls(op("PAUSED"))
    assert.equal(c.showResume, true)
    assert.equal(c.showPause, false)
    assert.equal(c.showReset, true)
    assert.equal(c.confirmBeforeResume, false)
  })

  /** 비상정지에서 바로 재개되면 사람이 현장을 확인하기 전에 차가 움직인다. */
  it("ESTOPPED — 재개 전 확인을 요구한다", () => {
    const c = toOperationControls(op("ESTOPPED"))
    assert.equal(c.showResume, true)
    assert.equal(c.confirmBeforeResume, true)
    assert.equal(c.showStart, false)
  })

  it("어떤 정지 상태에서도 종료로 빠져나갈 수 있다", () => {
    for (const state of ["RUNNING", "PAUSED", "ESTOPPED"] as const) {
      assert.equal(toOperationControls(op(state)).showReset, true, state)
    }
  })

  /** 서버 판단을 프론트가 뒤집으면 "눌리는데 409 가 뜨는" 버튼이 생긴다. */
  it("서버가 막으면 버튼도 막힌다", () => {
    const c = toOperationControls(op("IDLE", { canStart: false }))
    assert.equal(c.showStart, false)
  })

  it("상태를 모르면 아무 버튼도 열지 않는다", () => {
    for (const value of [null, undefined]) {
      const c = toOperationControls(value)
      assert.equal(c.showStart, false)
      assert.equal(c.showPause, false)
      assert.equal(c.showResume, false)
      assert.equal(c.showReset, false)
    }
    const unknown = toOperationControls(
      { ...op("IDLE"), state: "SOMETHING_NEW" as OperationState },
    )
    assert.equal(unknown.showStart, false)
    assert.equal(unknown.label, "연결 중")
  })
})

describe("합류 진행 표시", () => {
  it("주행 중이고 아직 덜 합류했으면 표시한다", () => {
    const operation = op("RUNNING", { joinedCount: 1, joinTargetCount: 3 })
    assert.equal(toOperationControls(operation).joining, true)
    assert.equal(joiningLabel(operation), "합류 중 (1/3)")
  })

  it("다 합류하면 표시하지 않는다", () => {
    const operation = op("RUNNING", { joinedCount: 3, joinTargetCount: 3 })
    assert.equal(toOperationControls(operation).joining, false)
    assert.equal(joiningLabel(operation), null)
  })

  /** 0/0 이 화면에 남으면 "뭔가 안 되고 있다"로 읽힌다. */
  it("대상이 0 이면 표시하지 않는다", () => {
    const operation = op("RUNNING", { joinedCount: 0, joinTargetCount: 0 })
    assert.equal(toOperationControls(operation).joining, false)
    assert.equal(joiningLabel(operation), null)
  })

  it("주행 중이 아니면 표시하지 않는다", () => {
    const paused = op("PAUSED", { joinedCount: 1, joinTargetCount: 3 })
    assert.equal(toOperationControls(paused).joining, false)
  })

  it("상태가 없으면 null", () => {
    assert.equal(joiningLabel(null), null)
    assert.equal(joiningLabel(undefined), null)
  })
})
