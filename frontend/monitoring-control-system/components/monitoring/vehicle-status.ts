import type { VehicleStatus } from "@/types/monitoring"

/** 차량 상태 한글 라벨 */
export const VEHICLE_STATUS_LABEL: Record<VehicleStatus, string> = {
  IDLE: "대기",
  MOVING: "이동 중",
  WORKING: "작업 중",
  STOPPED: "정지",
  ESTOP: "비상정지",
  OFFLINE: "오프라인",
  UNKNOWN: "위치 미수신",
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
  WORKING: { base: "#eab308", border: "#facc15", glow: "234, 179, 8", text: "#1a1400" },
  STOPPED: { base: "#94a3b8", border: "#cbd5e1", glow: "148, 163, 184", text: "#0b1220" },
  ESTOP: { base: "#ef4444", border: "#f87171", glow: "239, 68, 68", text: "#1a0303" },
  OFFLINE: { base: "#64748b", border: "#94a3b8", glow: "100, 116, 139", text: "#0b1220" },
  UNKNOWN: { base: "#64748b", border: "#94a3b8", glow: "100, 116, 139", text: "#0b1220" },
}
