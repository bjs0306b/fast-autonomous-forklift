import type { ControlVehicleView } from "@/lib/api/operationApi"

/**
 * 주기 단계·합류 상태를 화면 문구로 바꾼다.
 *
 * <b>왜 필요한가</b>: 지금 화면은 차량이 멈춰 있으면 전부 「대기」로 보인다. 그런데 멈춘
 * 이유는 셋이고 대응이 다르다 — 아직 합류 순서를 기다리는 중(정상), 사람이 세운 것,
 * 관제 규칙이 세운 것. 발표 중에 뭔가 멈췄을 때 화면만 보고 구별할 수 있어야 한다.
 *
 * 모르는 단계 값이 와도 <b>원문 그대로 보여준다</b> — F팀이 단계를 추가했는데 화면에서
 * 조용히 사라지면, 값이 안 오는 것과 구별할 수 없다.
 */

/** 백엔드 `CyclePhase` 와 같은 값. */
const PHASE_LABELS: Record<string, string> = {
  TO_BAY: "바이로 이동",
  ALIGN_BAY: "정렬 중",
  LOAD: "적재 중",
  TO_EXIT: "바이 탈출",
  TO_RACK: "랙으로 이동",
  RACK: "랙 적재 중",
}

/** telemetry 의 `step` — 차량이 지금 수행 중인 절차. */
const STEP_LABELS: Record<string, string> = {
  align: "정렬",
  fork: "포크 동작",
  dock: "도킹",
  place: "적재",
}

export interface CycleDisplay {
  /** 단계 문구. 주기가 시작되지 않았으면 `null` */
  phaseLabel: string | null
  /** 목표(스테이션·랙 코드). 없으면 `null` */
  target: string | null
  /** "3주기" 형태. 0이면 `null` — 0주기는 표시할 가치가 없다 */
  cyclesLabel: string | null
  /** 합류/정지 상태 한 줄. 정상 주행 중이면 `null` */
  standbyReason: string | null
  /** 지금 관제가 이 차량을 움직이고 있는가 */
  driving: boolean
  /** 수행 중인 절차 문구. 없거나 대기 중이면 `null` */
  stepLabel: string | null
}

/** 표시할 것이 하나도 없을 때. 관제가 꺼져 있으면 이 값이라 화면이 지금과 똑같이 유지된다. */
export const EMPTY_CYCLE_DISPLAY: CycleDisplay = {
  phaseLabel: null,
  target: null,
  cyclesLabel: null,
  standbyReason: null,
  driving: false,
  stepLabel: null,
}

export function toCycleDisplay(
  view: ControlVehicleView | null | undefined,
): CycleDisplay {
  if (!view) {
    return EMPTY_CYCLE_DISPLAY
  }
  return {
    phaseLabel: toPhaseLabel(view.phase),
    target: view.target && view.target !== "null" ? view.target : null,
    cyclesLabel: view.cycles > 0 ? `${view.cycles}주기` : null,
    standbyReason: toStandbyReason(view),
    driving: view.online && view.joined && !view.held,
    stepLabel: toStepLabel(view.step),
  }
}

/**
 * 절차 이름을 문구로. 모르는 값은 원문 그대로.
 *
 * 빈 문자열은 "절차 없음"이라 `null` 이다 — 시뮬이 정렬을 끝내면 `step` 을 `""` 로 보낸다.
 */
export function toStepLabel(step: string | null | undefined): string | null {
  if (!step || !step.trim()) {
    return null
  }
  return STEP_LABELS[step] ?? step
}

/** 모르는 값은 원문 그대로 — 조용히 사라지면 "값이 안 온 것"과 구별되지 않는다. */
export function toPhaseLabel(phase: string | null | undefined): string | null {
  if (!phase) {
    return null
  }
  return PHASE_LABELS[phase] ?? phase
}

/**
 * 차가 안 움직이는 이유. 움직이고 있으면 `null`.
 *
 * 순서가 중요하다 — 여러 조건이 겹칠 때 <b>가장 바깥쪽 원인</b>을 보여야 한다.
 * 연결이 끊긴 차량에 "합류 대기"라고 쓰면, 순서를 기다리는 줄 알고 계속 기다리게 된다.
 */
export function toStandbyReason(view: ControlVehicleView): string | null {
  if (!view.online) {
    return "연결 끊김"
  }
  // 관제 대상이 아니면 버튼도 안 보인다. 그 이유를 화면에 남긴다 —
  // 아무 표시 없이 버튼만 사라지면 고장으로 읽힌다.
  if (!view.controlled) {
    return "관제 대상 아님"
  }
  if (view.held) {
    return "수동 정지"
  }
  if (!view.joined) {
    return "합류 대기"
  }
  return null
}

/**
 * [출발]을 지금 누를 수 없는 이유. 누를 수 있으면 `null`.
 *
 * <b>버튼을 감추지 않고 이유를 돌려준다.</b> 처음에는 못 누르는 상황이면 감추게 만들었는데,
 * 그러면 "출발 버튼이 어디 갔지?"가 되어 원인을 찾을 수 없었다. 실제로 관제 대상 설정이
 * 비어 있을 때 버튼이 통째로 사라져 한참을 헤맸다.
 *
 * 이제는 <b>항상 보이되 비활성 + 이유 표시</b>다. 눌러서 409 를 보는 것보다 낫고,
 * 아무것도 안 보이는 것보다는 훨씬 낫다.
 */
export function dispatchBlockReason(
  view: ControlVehicleView | null | undefined,
): string | null {
  if (!view) {
    return "관제 정보를 받지 못했습니다"
  }
  if (!view.controlled) {
    return "관제 대상이 아닙니다 (traffic.vehicles 설정 확인)"
  }
  if (!view.online) {
    // 끊긴 차에 목표를 주면 되살아났을 때 밀린 명령이 한꺼번에 적용된다(규격 §0.5).
    return "telemetry 가 오지 않습니다"
  }
  if (view.joined && !view.held) {
    return "이미 출발했습니다"
  }
  return null
}

/** 지금 출발시킬 수 있는가. */
export function canDispatch(view: ControlVehicleView | null | undefined): boolean {
  return dispatchBlockReason(view) === null
}
