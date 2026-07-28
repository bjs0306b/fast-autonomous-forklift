// 관제 프론트 스캐폴딩용 최소 타입 정의
// 실제 백엔드 계약(enum/필드)은 연동 단계에서 다시 확정한다.

/** 차량 상태 (백엔드 VehicleStatus enum은 연동 단계에서 확정) */
export type VehicleStatus =
  | "IDLE"
  | "MOVING"
  | "WORKING"
  | "STOPPED"
  | "ESTOP"
  | "OFFLINE"
  | "UNKNOWN"

/** 차량 출처 (REAL / SIMULATION) */
export type VehicleSource = "REAL" | "SIMULATION"

/**
 * 디지털 트윈 영상 스트림 연결 상태.
 * 스캐폴딩 단계에서는 실제 스트림을 연결하지 않고 상태만 표현한다.
 * - idle:       아직 연결 시도 전 / 영상 미연결
 * - connecting: 연결 시도 중 (로딩)
 * - connected:  스트림 표시 가능 (실제 URL/프로토콜은 미확정)
 * - error:      연결 실패
 */
export type StreamConnectionStatus = "idle" | "connecting" | "connected" | "error"

/** 창고 월드 좌표. 단위/원점은 연동 단계에서 확정한다. */
export interface VehicleLocation {
  x: number
  y: number
}

/**
 * Mock 디지털 트윈 화면에 배치되는 차량 정보.
 * 실제 API/WebSocket 연동 전, 시각적 관제 화면 표현을 위한 형태다.
 */
export interface MockVehicle {
  vehicleId: string
  /** 사람이 읽는 차량명 (예: 지게차 F01) */
  name?: string | null
  /** 마커 위에 표시할 짧은 라벨 (예: F01, S01) */
  shortLabel: string
  status: VehicleStatus
  source: VehicleSource
  /** 창고 내 월드 좌표. null 이면 위치 미수신 → 미니맵에 표시하지 않는다. */
  location: VehicleLocation | null
  /** 진행 방향(도). 실제 기준축/회전 방향은 미확정. */
  heading?: number | null
  /** 속도 (단위 미확정, 예: m/s). null 이면 미수신 */
  speed?: number | null
  /** 현재 작업 라벨 (있을 때만 표시) */
  currentTask?: string | null
}

/** 실시간 관제 화면 오버레이에 표시할 선택 차량 요약 정보 */
export interface SelectedVehicleSummary {
  vehicleId: string
  name?: string | null
  source?: VehicleSource | null
  status: VehicleStatus
  currentTask?: string | null
}
