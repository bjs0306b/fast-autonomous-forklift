import type { VehicleStatus } from "@/types/monitoring"

/**
 * 차량 상태 한글 라벨.
 * 백엔드 VehicleStatus enum 10종과 1:1 대응한다(FR-402-1 연동에서 스캐폴딩 값 교체).
 */
export const VEHICLE_STATUS_LABEL: Record<VehicleStatus, string> = {
  UNKNOWN: "상태 미상",
  IDLE: "대기",
  ACTIVE: "작업 중",
  MOVING: "이동 중",
  LIFTING: "포크 승강",
  LOADING: "적재 중",
  UNLOADING: "하역 중",
  ESTOP: "비상정지",
  ERROR: "오류",
  OFFLINE: "오프라인",
}

/**
 * 상태별 마커 색상 팔레트 (다크 관제 화면 기준).
 * 게임 UI 같은 과도한 네온은 지양하고 채도가 절제된 산업용 톤을 사용한다.
 */
export interface StatusColor {
  /** 마커 본체 색 */
  base: string
  /** 마커 테두리 */
  border: string
  /** glow / 강조에 사용하는 색 */
  glow: string
  /** 라벨 텍스트 대비색 */
  text: string
}

export const VEHICLE_STATUS_COLOR: Record<VehicleStatus, StatusColor> = {
  MOVING: { base: "#10b981", border: "#34d399", glow: "16, 185, 129", text: "#04120c" },
  IDLE: { base: "#3b82f6", border: "#60a5fa", glow: "59, 130, 246", text: "#04101f" },
  ACTIVE: { base: "#eab308", border: "#facc15", glow: "234, 179, 8", text: "#1a1400" },
  LIFTING: { base: "#a855f7", border: "#c084fc", glow: "168, 85, 247", text: "#12041f" },
  LOADING: { base: "#f59e0b", border: "#fbbf24", glow: "245, 158, 11", text: "#1a1200" },
  UNLOADING: { base: "#fb923c", border: "#fdba74", glow: "251, 146, 60", text: "#1a0d00" },
  ESTOP: { base: "#ef4444", border: "#f87171", glow: "239, 68, 68", text: "#1a0303" },
  ERROR: { base: "#dc2626", border: "#f87171", glow: "220, 38, 38", text: "#1a0303" },
  OFFLINE: { base: "#64748b", border: "#94a3b8", glow: "100, 116, 139", text: "#0b1220" },
  UNKNOWN: { base: "#64748b", border: "#94a3b8", glow: "100, 116, 139", text: "#0b1220" },
}

/** 경고 강조가 필요한 상태(오류/비상정지). */
export function isAlertStatus(status: VehicleStatus): boolean {
  return status === "ESTOP" || status === "ERROR"
}
