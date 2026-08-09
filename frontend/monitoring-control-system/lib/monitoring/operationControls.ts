import type { OperationState, OperationStateResponse } from "@/lib/api/operationApi"

/**
 * 운행 상태에 따라 어떤 버튼을 보일지 정한다.
 *
 * <b>왜 순수 함수로 빼는가</b>: 상태가 넷이고 버튼이 넷이라 조합이 열여섯 가지다. 컴포넌트
 * 안에 삼항 연산자로 흩어 두면 "비상정지 상태에서 시작 버튼이 눌린다" 같은 조합을 놓치기
 * 쉽고, 그 버그는 눌러 봐야 안다. 여기 모아 두면 테스트로 고정할 수 있다.
 *
 * <b>서버 값을 우선한다</b>: `canStart` / `canResume` 는 백엔드가 계산해서 준다. 전이 규칙이
 * 양쪽에 흩어지면 한쪽만 고쳤을 때 "눌리는데 409 가 뜨는" 버튼이 생긴다.
 */

export interface OperationControls {
  /** [운행 시작] */
  showStart: boolean
  /** [일시정지] */
  showPause: boolean
  /** [재개] */
  showResume: boolean
  /** [운행 종료] */
  showReset: boolean
  /** 재개 전에 확인을 받아야 하는가 — 비상정지였다면 사람이 현장을 확인한 뒤여야 한다 */
  confirmBeforeResume: boolean
  /** 화면에 보여줄 상태 문구 */
  label: string
  /** 합류 진행 중인가 — "합류 중 (1/3)" 을 띄울지 정한다 */
  joining: boolean
}

const LABELS: Record<OperationState, string> = {
  IDLE: "대기",
  RUNNING: "주행 중",
  PAUSED: "일시정지",
  ESTOPPED: "비상정지",
}

/** 서버가 상태를 아직 안 준 동안 쓸 값. 아무 버튼도 눌리지 않게 한다. */
export const UNKNOWN_CONTROLS: OperationControls = {
  showStart: false,
  showPause: false,
  showResume: false,
  showReset: false,
  confirmBeforeResume: false,
  label: "연결 중",
  joining: false,
}

export function toOperationControls(
  operation: OperationStateResponse | null | undefined,
): OperationControls {
  if (!operation || !isKnownState(operation.state)) {
    // 모르는 상태에서 버튼을 열어 두면, 무엇이 일어날지 모르는 채로 명령이 나간다.
    return UNKNOWN_CONTROLS
  }
  const state = operation.state
  const running = state === "RUNNING"
  const stopped = state === "PAUSED" || state === "ESTOPPED"

  return {
    showStart: operation.canStart,
    showPause: running,
    showResume: operation.canResume,
    // 종료는 시작하지 않은 상태에서만 숨긴다 — 어떤 정지 상태에서든 빠져나올 길이 있어야 한다.
    showReset: state !== "IDLE",
    confirmBeforeResume: state === "ESTOPPED",
    label: LABELS[state],
    joining: running && isJoining(operation),
  }
}

/**
 * 합류가 아직 진행 중인가.
 *
 * 대상 수를 모르거나(0) 이미 다 합류했으면 진행 표시를 하지 않는다 — 0/0 같은 문구가
 * 화면에 남으면 "뭔가 안 되고 있다"로 읽힌다.
 *
 * **차량 개별 출발 모드(`autoRelease=false`)에서는 표시하지 않는다.** 한 대만 보내려고
 * 고른 것인데 "합류 중 (1/3)"이 뜨면 나머지도 곧 나갈 것처럼 읽힌다.
 */
export function isJoining(operation: OperationStateResponse): boolean {
  return (
    operation.autoRelease &&
    operation.joinTargetCount > 0 &&
    operation.joinedCount < operation.joinTargetCount
  )
}

/** "합류 중 (1/3)" 문구. 진행 중이 아니면 `null`. */
export function joiningLabel(
  operation: OperationStateResponse | null | undefined,
): string | null {
  if (!operation || !isJoining(operation)) {
    return null
  }
  return `합류 중 (${operation.joinedCount}/${operation.joinTargetCount})`
}

function isKnownState(state: string): state is OperationState {
  return state === "IDLE" || state === "RUNNING" || state === "PAUSED" || state === "ESTOPPED"
}
