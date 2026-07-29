// 적재 화물 안전 관제 타입(FR / prompt63.md).
// 백엔드 com.fast.backend.loadsafety.dto.LoadSafetyResponse 필드명을 그대로 따른다.

/**
 * 위험 단계. 백엔드 LoadSafetyRiskLevel enum 5종과 일치한다.
 *
 * 중요: **프론트는 이 값을 계산하지 않는다.** 비전·센서가 판정해 백엔드가 중계한 값을 그대로 표시한다
 * (prompt63.md 3장 "프론트는 위험도를 계산하지 않는다"). roll/pitch 를 임계값과 비교해 단계를 만들어내는
 * 코드를 이 프로젝트에 추가하면 안 된다 — 화면과 실제 안전 로직의 기준이 갈라진다.
 */
export type LoadSafetyRiskLevel = "NORMAL" | "CAUTION" | "WARNING" | "DANGER" | "UNKNOWN"

/** 데이터 출처. 백엔드 LoadSafetySource enum 4종. */
export type LoadSafetySource = "VISION" | "SENSOR" | "ROS2" | "UNKNOWN"

/**
 * 차량 최신 적재 안전 상태.
 *
 * REST(`GET /api/vehicles/{vehicleId}/load-safety/latest`)와 WebSocket
 * (`/topic/vehicles/load-safety` 의 `data`)이 **같은 구조**다 — 백엔드가 의도적으로 같은 DTO 를 쓴다.
 *
 * 측정값이 전부 nullable 인 이유: 비전이 화물을 못 찾았거나 IMU 가 없는 차량도 "위험 아님"을 보고할 수
 * 있어야 한다. 값이 없다는 사실을 0 으로 위조하지 않는다 — 화면에서도 0 이 아니라 "—" 로 표시한다.
 *
 * 단위: forkHeight/cargoHeight = m, roll/pitch = degree, loadOffsetX/Y = m.
 */
export interface LoadSafetyState {
  vehicleId: string
  /** 적재된 화물 식별자. 화물 미인식이면 null */
  cargoId: string | null
  forkHeight: number | null
  cargoHeight: number | null
  /** 좌우 기울기(degree). 양수/음수 방향 정의는 센서 팀 규격을 따른다. */
  roll: number | null
  /** 앞뒤 기울기(degree) */
  pitch: number | null
  /** 화물 중심 편향 X(m) */
  loadOffsetX: number | null
  /** 화물 중심 편향 Y(m) */
  loadOffsetY: number | null
  riskLevel: LoadSafetyRiskLevel
  /** 위험 코드(예: "LOAD_TILT_EXCEEDED"). 정상 상태면 null 일 수 있다. */
  riskCode: string | null
  /** 사람이 읽는 경고 메시지 */
  message: string | null
  source: LoadSafetySource
  /** 센서가 실제로 감지한 시각 (ISO-8601 +09:00) */
  detectedAt: string | null
  /** 백엔드가 메시지를 수신한 시각 (ISO-8601 +09:00) */
  receivedAt: string | null
}

/**
 * 위험 단계 한글 라벨.
 *
 * 패널·미니맵 마커·접근성 라벨이 **같은 문구**를 쓰도록 한 곳에서 관리한다.
 * 각자 정의하면 한쪽만 수정됐을 때 화면과 스크린리더가 서로 다른 단계를 말하게 된다.
 */
export const LOAD_SAFETY_RISK_LABEL: Record<LoadSafetyRiskLevel, string> = {
  NORMAL: "정상",
  CAUTION: "주의",
  WARNING: "경고",
  DANGER: "위험",
  UNKNOWN: "판정 불가",
}

/** 경고 오버레이를 띄우는 단계. 백엔드 LoadSafetyRiskLevel.isAlerting() 과 같은 기준이다. */
export const ALERTING_RISK_LEVELS: readonly LoadSafetyRiskLevel[] = ["WARNING", "DANGER"]

export function isAlertingRiskLevel(level: LoadSafetyRiskLevel): boolean {
  return ALERTING_RISK_LEVELS.includes(level)
}

/**
 * 데이터가 오래된 것으로 간주하는 기준(ms). prompt63.md 3장 7번 "데이터 미수신·오래된 데이터 표시".
 *
 * 이 값은 **위험도 판정이 아니라 표시 신선도 판정**이다 — 오래됐다고 위험 단계를 바꾸지 않고,
 * "이 값은 N초 전 것"이라는 사실만 화면에 덧붙인다.
 * 센서 발행 주기가 확정되면 팀 합의값으로 교체해야 한다(현재는 잠정값).
 */
export const LOAD_SAFETY_STALE_AFTER_MS = 15_000
