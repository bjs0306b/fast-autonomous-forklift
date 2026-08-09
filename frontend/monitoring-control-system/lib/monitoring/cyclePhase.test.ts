/**
 * 주기 단계 표시 테스트.
 *
 *     node --test --experimental-strip-types lib/monitoring/cyclePhase.test.ts
 */
import assert from "node:assert/strict"
import { describe, it } from "node:test"

import type { ControlVehicleView } from "../api/operationApi.ts"
import {
  canDispatch,
  dispatchBlockReason,
  toCycleDisplay,
  toPhaseLabel,
  toStandbyReason,
  toStepLabel,
} from "./cyclePhase.ts"

function view(overrides: Partial<ControlVehicleView> = {}): ControlVehicleView {
  return {
    id: "SIM-F02",
    kind: "sim",
    online: true,
    controlled: true,
    joined: true,
    held: false,
    phase: "TO_RACK",
    target: "A001",
    cycles: 3,
    busy: false,
    step: null,
    ...overrides,
  }
}

describe("toPhaseLabel", () => {
  it("여섯 단계를 한글로 바꾼다", () => {
    assert.equal(toPhaseLabel("TO_BAY"), "바이로 이동")
    assert.equal(toPhaseLabel("ALIGN_BAY"), "정렬 중")
    assert.equal(toPhaseLabel("LOAD"), "적재 중")
    assert.equal(toPhaseLabel("TO_EXIT"), "바이 탈출")
    assert.equal(toPhaseLabel("TO_RACK"), "랙으로 이동")
    assert.equal(toPhaseLabel("RACK"), "랙 적재 중")
  })

  /** 조용히 사라지면 "값이 안 온 것"과 구별되지 않는다. */
  it("모르는 단계는 원문 그대로 보여준다", () => {
    assert.equal(toPhaseLabel("SOMETHING_NEW"), "SOMETHING_NEW")
  })

  it("없으면 null", () => {
    assert.equal(toPhaseLabel(null), null)
    assert.equal(toPhaseLabel(undefined), null)
    assert.equal(toPhaseLabel(""), null)
  })
})

describe("toStandbyReason — 멈춘 이유", () => {
  it("정상 주행 중이면 null", () => {
    assert.equal(toStandbyReason(view()), null)
  })

  it("합류 전이면 합류 대기", () => {
    assert.equal(toStandbyReason(view({ joined: false })), "합류 대기")
  })

  it("사람이 세웠으면 수동 정지", () => {
    assert.equal(toStandbyReason(view({ held: true })), "수동 정지")
  })

  it("연결이 끊겼으면 연결 끊김", () => {
    assert.equal(toStandbyReason(view({ online: false })), "연결 끊김")
  })

  /** 끊긴 차에 "합류 대기"라고 쓰면 순서를 기다리는 줄 알고 계속 기다리게 된다. */
  it("여러 원인이 겹치면 가장 바깥쪽을 보여준다", () => {
    assert.equal(
      toStandbyReason(view({ online: false, joined: false, held: true })),
      "연결 끊김",
    )
    assert.equal(toStandbyReason(view({ joined: false, held: true })), "수동 정지")
  })
})

describe("toCycleDisplay", () => {
  it("주행 중인 차량", () => {
    const d = toCycleDisplay(view())
    assert.equal(d.phaseLabel, "랙으로 이동")
    assert.equal(d.target, "A001")
    assert.equal(d.cyclesLabel, "3주기")
    assert.equal(d.standbyReason, null)
    assert.equal(d.driving, true)
  })

  it("0주기는 표시하지 않는다", () => {
    assert.equal(toCycleDisplay(view({ cycles: 0 })).cyclesLabel, null)
  })

  /** 관제가 꺼져 있으면 화면이 지금과 똑같이 유지돼야 한다. */
  it("관제 정보가 없으면 아무것도 표시하지 않는다", () => {
    for (const value of [null, undefined]) {
      const d = toCycleDisplay(value)
      assert.equal(d.phaseLabel, null)
      assert.equal(d.target, null)
      assert.equal(d.cyclesLabel, null)
      assert.equal(d.standbyReason, null)
      assert.equal(d.driving, false)
    }
  })

  it("주기가 시작되지 않았으면 단계가 비어 있다", () => {
    const d = toCycleDisplay(view({ phase: null, target: null, cycles: 0 }))
    assert.equal(d.phaseLabel, null)
    assert.equal(d.target, null)
  })

  /** 백엔드가 String.valueOf(null) 로 "null" 을 흘릴 수 있다. */
  it('문자열 "null" 은 값으로 보지 않는다', () => {
    assert.equal(toCycleDisplay(view({ target: "null" })).target, null)
  })

  it("끊긴 차량은 driving 이 아니다", () => {
    assert.equal(toCycleDisplay(view({ online: false })).driving, false)
    assert.equal(toCycleDisplay(view({ held: true })).driving, false)
    assert.equal(toCycleDisplay(view({ joined: false })).driving, false)
  })
})

describe("toStepLabel — 수행 중인 절차", () => {
  it("알려진 절차를 한글로 바꾼다", () => {
    assert.equal(toStepLabel("align"), "정렬")
    assert.equal(toStepLabel("fork"), "포크 동작")
    assert.equal(toStepLabel("dock"), "도킹")
    assert.equal(toStepLabel("place"), "적재")
  })

  it("모르는 절차는 원문 그대로", () => {
    assert.equal(toStepLabel("reverse"), "reverse")
  })

  /** 시뮬은 절차가 끝나면 step 을 빈 문자열로 보낸다. */
  it("빈 값은 절차 없음", () => {
    assert.equal(toStepLabel(""), null)
    assert.equal(toStepLabel("   "), null)
    assert.equal(toStepLabel(null), null)
    assert.equal(toStepLabel(undefined), null)
  })

  it("toCycleDisplay 에 실린다", () => {
    assert.equal(toCycleDisplay(view({ step: "align" })).stepLabel, "정렬")
    assert.equal(toCycleDisplay(view({ step: "" })).stepLabel, null)
    assert.equal(toCycleDisplay(null).stepLabel, null)
  })
})

describe("dispatchBlockReason — [출발] 버튼이 막히는 이유", () => {
  it("아직 출발 안 한 차량은 누를 수 있다", () => {
    assert.equal(dispatchBlockReason(view({ joined: false })), null)
    assert.equal(canDispatch(view({ joined: false })), true)
  })

  it("세워 둔 차량도 누를 수 있다 — 정지 → 출발이 자연스럽다", () => {
    assert.equal(dispatchBlockReason(view({ joined: true, held: true })), null)
  })

  it("이미 달리는 중이면 이유를 알려준다", () => {
    assert.equal(dispatchBlockReason(view({ joined: true, held: false })), "이미 출발했습니다")
    assert.equal(canDispatch(view({ joined: true, held: false })), false)
  })

  /** 끊긴 차에 목표를 주면 되살아났을 때 밀린 명령이 한꺼번에 적용된다(규격 §0.5). */
  it("telemetry 가 없으면 이유를 알려준다", () => {
    assert.equal(dispatchBlockReason(view({ online: false })), "telemetry 가 오지 않습니다")
  })

  /** 이것 때문에 버튼이 통째로 사라져 한참 헤맸다. 이제는 이유가 보인다. */
  it("관제 대상이 아니면 설정을 짚어 준다", () => {
    const reason = dispatchBlockReason(view({ controlled: false }))
    assert.ok(reason && reason.includes("traffic.vehicles"))
    assert.equal(toStandbyReason(view({ controlled: false })), "관제 대상 아님")
  })

  it("관제 정보가 없어도 이유를 남긴다", () => {
    assert.equal(dispatchBlockReason(null), "관제 정보를 받지 못했습니다")
    assert.equal(dispatchBlockReason(undefined), "관제 정보를 받지 못했습니다")
    assert.equal(canDispatch(null), false)
  })

  /** 여러 원인이 겹칠 때 가장 바깥쪽(설정)을 먼저 보여야 한다. */
  it("설정 문제가 연결 문제보다 먼저다", () => {
    const reason = dispatchBlockReason(view({ controlled: false, online: false }))
    assert.ok(reason && reason.includes("traffic.vehicles"))
  })
})
