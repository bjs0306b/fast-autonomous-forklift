import type {
  EmergencyStopAllResponse,
  SafetyCommandRequest,
  VehicleCommandResponse,
} from "@/types/command"
import { postJson } from "./httpClient"

// 차량 안전 명령 API (FR-503).
//
// 두 API 모두 발행 실패(PUBLISH_FAILED)를 HTTP 오류가 아니라 **HTTP 201 + 본문 status** 로 알린다.
// 따라서 호출자는 반드시 응답의 status 를 확인해야 한다. 이 파일은 봉투만 벗기고
// 성공/실패 판정은 호출자에게 넘긴다(판정 로직을 API 계층에 숨기지 않는다).

/**
 * 선택 차량 1대 비상정지.
 *
 * `POST /api/vehicles/{vehicleId}/commands/emergency-stop`
 *
 * @returns HTTP 201 응답 본문. `status` 가 `"PUBLISHED"` 여야 발행 성공이다.
 */
export async function emergencyStopVehicle(
  vehicleId: string,
  request?: SafetyCommandRequest,
  signal?: AbortSignal,
): Promise<VehicleCommandResponse> {
  return postJson<VehicleCommandResponse>(
    `/api/vehicles/${encodeURIComponent(vehicleId)}/commands/emergency-stop`,
    request,
    signal,
  )
}

/**
 * 활성 차량 전체 비상정지.
 *
 * `POST /api/vehicles/commands/emergency-stop-all`
 *
 * 차량마다 독립 commandId 로 개별 발행되며, 한 대가 실패해도 나머지는 계속 발행된다.
 * 부분 실패도 HTTP 201 이므로 `publishedCount` / `failedCount` 로 판단해야 한다.
 */
export async function emergencyStopAll(
  request?: SafetyCommandRequest,
  signal?: AbortSignal,
): Promise<EmergencyStopAllResponse> {
  return postJson<EmergencyStopAllResponse>(
    "/api/vehicles/commands/emergency-stop-all",
    request,
    signal,
  )
}
