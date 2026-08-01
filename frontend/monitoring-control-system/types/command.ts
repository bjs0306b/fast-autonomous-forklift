// 차량 안전 명령(FR-503) 타입.
// 백엔드 com.fast.backend.command.dto 패키지의 실제 DTO 필드명을 그대로 따른다.

/** 백엔드 VehicleCommandStatus enum 9종. */
export type VehicleCommandStatus =
  | "PENDING"
  | "PUBLISHED"
  | "PUBLISH_FAILED"
  | "ACCEPTED"
  | "IN_PROGRESS"
  | "SUCCESS"
  | "FAILED"
  | "REJECTED"
  | "CANCELLED"

/** 백엔드 VehicleCommandType 중 안전 명령 계열. */
export type SafetyCommandName = "STOP" | "EMERGENCY_STOP" | "RESET_ESTOP"

/** 개별 STOP/EMERGENCY_STOP 응답 (백엔드 VehicleCommandResponse). */
export interface VehicleCommandResponse {
  commandId: string
  vehicleId: string
  command: string | null
  targetSystem: string | null
  /** 발행 성공 여부는 HTTP status 가 아니라 이 값으로 판단한다. */
  status: VehicleCommandStatus | null
  completedAt: string | null
  resultMessage: string | null
  createdAt: string | null
}

/** 전체 비상정지 결과의 차량별 항목 (백엔드 EmergencyStopAllResponse.Item). */
export interface EmergencyStopVehicleResult {
  vehicleId: string
  /** 명령 생성 자체가 실패하면 null */
  commandId: string | null
  command: string
  status: VehicleCommandStatus | string
  failureReason: string | null
}

/**
 * 전체 비상정지 요약 (백엔드 EmergencyStopAllResponse).
 *
 * 주의: 전체 대상 수의 실제 필드명은 `totalCount` 가 아니라 **`requestedCount`** 다.
 */
export interface EmergencyStopAllResponse {
  requestedCount: number
  publishedCount: number
  failedCount: number
  results: EmergencyStopVehicleResult[]
}
