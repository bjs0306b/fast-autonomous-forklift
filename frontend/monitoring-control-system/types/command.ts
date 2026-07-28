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

/** 백엔드 VehicleCommandType enum 8종 중 안전 명령 계열. */
export type SafetyCommandName = "STOP" | "EMERGENCY_STOP" | "RESET_ESTOP"

/**
 * POST body. 두 필드 모두 선택이며 body 자체를 생략할 수 있다.
 * 각각 최대 100자(백엔드 @Size(max = 100)).
 *
 * requestedBy 는 재사용 테이블에 컬럼이 없어 DB에 저장되지 않고 백엔드 감사 로그로만 남는다.
 */
export interface SafetyCommandRequest {
  reason?: string | null
  requestedBy?: string | null
}

/** 개별 STOP/EMERGENCY_STOP 응답 (백엔드 VehicleCommandResponse). */
export interface VehicleCommandResponse {
  commandId: string
  vehicleId: string
  command: string | null
  targetSystem: string | null
  commandCategory: string | null
  /** 발행 성공 여부는 HTTP status 가 아니라 이 값으로 판단한다. */
  status: VehicleCommandStatus | null
  reason: string | null
  payloadJson: string | null
  issuedAt: string | null
  /** PUBLISH_FAILED 면 null */
  publishedAt: string | null
  /** 차량 결과 미수신이면 null */
  completedAt: string | null
  errorCode: string | null
  resultMessage: string | null
  stoppedActions: string[]
  emergencyStopApplied: boolean | null
  requiresReset: boolean | null
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
