import { getJson, postJson } from "./httpClient"

// 운행 제어 API (F팀 backend-control-impl §0.6).
//
// 「시작 시그널」이라는 별도 명령은 없다. 차량은 스스로 출발하지 않고 백엔드가 첫 목표를
// 보내야 움직이므로, **시작을 누르는 것이 곧 첫 목표를 주는 것**이다.
//
// 다섯 조작이 모두 같은 모양(OperationStateResponse)을 돌려준다 — 호출 후 화면이 다시
// 조회할 필요 없이 바로 버튼 상태를 갱신할 수 있게 하기 위해서다.

/** 전체 운행 상태. */
export type OperationState = "IDLE" | "RUNNING" | "PAUSED" | "ESTOPPED"

export interface OperationStateResponse {
  state: OperationState
  /** [운행 시작] 활성 여부. **백엔드가 계산해 준다** — 프론트가 state 로 재분기하지 않는다 */
  canStart: boolean
  /** [재개] 활성 여부 */
  canResume: boolean
  /** 순환로에 합류한 차량 수 — "합류 중 (1/3)" 의 분자 */
  joinedCount: number
  /** 합류 대상(온라인 관제 대상) 수 — 분모 */
  joinTargetCount: number
  joinedVehicles: string[]
  /** 사용자가 개별로 세워 둔 차량 */
  heldVehicles: string[]
  /** 규칙 0 자동 합류가 도는가. [운행 시작]이면 true, 차량 개별 출발이면 false */
  autoRelease: boolean
}

/** 현재 운행 상태. 화면 진입 시 버튼 노출을 정하는 데 쓴다. */
export async function fetchOperationState(
  signal?: AbortSignal,
): Promise<OperationStateResponse> {
  return getJson<OperationStateResponse>("/api/operation", signal)
}

/**
 * 운행 시작.
 *
 * 이 호출이 곧바로 전 차량을 출발시키지 않는다 — 백엔드가 3초 간격으로 한 대씩 합류시킨다
 * (규칙 0, 실물 우선). 화면은 `joinedCount / joinTargetCount` 로 진행을 보여주면 된다.
 *
 * 연결된 차량이 하나도 없으면 409 로 거부된다.
 */
export async function startOperation(
  signal?: AbortSignal,
): Promise<OperationStateResponse> {
  return postJson<OperationStateResponse>("/api/operation/start", undefined, signal)
}

/** 전체 일시정지(HOLD). 합류 상태는 유지되므로 재개하면 이어서 돈다. */
export async function stopOperation(
  signal?: AbortSignal,
): Promise<OperationStateResponse> {
  return postJson<OperationStateResponse>("/api/operation/stop", undefined, signal)
}

/** 비상정지. 어떤 상태에서든 받아들여진다. */
export async function emergencyStopOperation(
  signal?: AbortSignal,
): Promise<OperationStateResponse> {
  return postJson<OperationStateResponse>("/api/operation/estop", undefined, signal)
}

/** 재개. 개별로 세워 둔 차량까지 함께 푼다. */
export async function resumeOperation(
  signal?: AbortSignal,
): Promise<OperationStateResponse> {
  return postJson<OperationStateResponse>("/api/operation/resume", undefined, signal)
}

/** 운행 종료 — 전체 정지 + 합류 해제 + 초기화. */
export async function resetOperation(
  signal?: AbortSignal,
): Promise<OperationStateResponse> {
  return postJson<OperationStateResponse>("/api/operation/reset", undefined, signal)
}

/**
 * 차량 하나 출발 — 미니맵에서 차량을 고르고 [출발]을 눌렀을 때.
 *
 * [운행 시작]과 다르다. 그쪽은 3초 간격으로 전 차량을 내보내고, 이쪽은 **고른 차 한 대만**
 * 내보낸다. 운행이 아직 시작 전이면 함께 시작 상태로 올라간다.
 *
 * 끊긴 차량이거나 이미 출발한 차량이면 409 로 거부된다.
 */
export async function startVehicleInOperation(
  vehicleId: string,
  signal?: AbortSignal,
): Promise<OperationStateResponse> {
  return postJson<OperationStateResponse>(
    `/api/operation/vehicles/${encodeURIComponent(vehicleId)}/start`,
    undefined,
    signal,
  )
}

/**
 * 차량 하나 정지 (규격 §0.6).
 *
 * `/api/vehicles/{id}/commands/stop` 과 다르다 — 그쪽은 명령 한 발을 쏘고 끝이라 다음 tick 에
 * 관제가 안전하다고 판단하면 곧바로 다시 움직인다. 이쪽은 **"사람이 세워 뒀다"를 백엔드가
 * 기억**해서(규칙 5) 관제가 건드리지 않는다.
 */
export async function holdVehicleInOperation(
  vehicleId: string,
  signal?: AbortSignal,
): Promise<OperationStateResponse> {
  return postJson<OperationStateResponse>(
    `/api/operation/vehicles/${encodeURIComponent(vehicleId)}/hold`,
    undefined,
    signal,
  )
}

/** 차량 하나 재개. 사람이 세워 둔 기억을 지우고 관제에 다시 맡긴다. */
export async function resumeVehicleInOperation(
  vehicleId: string,
  signal?: AbortSignal,
): Promise<OperationStateResponse> {
  return postJson<OperationStateResponse>(
    `/api/operation/vehicles/${encodeURIComponent(vehicleId)}/resume`,
    undefined,
    signal,
  )
}

/** 관제 대상 차량 목록 — phase·joined·cycles 포함. */
export interface ControlVehicleView {
  id: string
  kind: "real" | "sim"
  online: boolean
  /** 관제 대상(`traffic.vehicles`)에 있는가. **false 면 관제가 아무 명령도 안 보낸다** */
  controlled: boolean
  joined: boolean
  held: boolean
  /** 주기가 시작되지 않았으면 null */
  phase: string | null
  target: string | null
  cycles: number
  /** 절차(정렬·도킹·적재) 수행 중인가. **null 은 "모른다"** — 시뮬만 보내고 실물은 아직 없다 */
  busy: boolean | null
  /** 지금 수행 중인 절차 이름(`align` `dock` …). 없으면 null */
  step: string | null
}

export async function fetchControlVehicles(
  signal?: AbortSignal,
): Promise<ControlVehicleView[]> {
  const rows = await getJson<ControlVehicleView[]>("/api/operation/vehicles", signal)
  return Array.isArray(rows) ? rows : []
}
