import type { LoadSafetyState } from "@/types/loadSafety"
import { ApiRequestError } from "@/types/api"
import { getJson } from "./httpClient"

// 적재 화물 안전 조회 API(prompt63.md 3장 2번). 조회 전용이다 —
// 이 도메인의 쓰기 경로는 MQTT 하나뿐이며 프론트가 위험 상태를 써넣는 API 는 존재하지 않는다.

/**
 * 차량 최신 적재 안전 상태.
 *
 * `GET /api/vehicles/{vehicleId}/load-safety/latest`
 *
 * 주의: 한 번도 수신하지 못한 차량은 **HTTP 200 + data:null** 이다(미수신은 오류가 아니다).
 * httpClient.getJson 은 data:null 을 business 오류로 던지므로, 여기서 그 경우만 잡아 null 로 바꾼다.
 * 등록되지 않은 차량(404)은 그대로 던져 호출자가 구분할 수 있게 한다.
 */
export async function fetchLatestLoadSafety(
  vehicleId: string,
  signal?: AbortSignal,
): Promise<LoadSafetyState | null> {
  try {
    return await getJson<LoadSafetyState>(
      `/api/vehicles/${encodeURIComponent(vehicleId)}/load-safety/latest`,
      signal,
    )
  } catch (error) {
    // 200 + data:null(적재 안전 데이터 미수신)은 오류가 아니라 정상적인 초기 상태다.
    // httpClient 가 이를 business 오류로 올리므로 여기서만 null 로 되돌린다.
    // 404(미등록 차량)나 network 오류는 그대로 전파한다 — 호출자가 구분해야 한다.
    if (error instanceof ApiRequestError && error.kind === "business" && error.status === 200) {
      return null
    }
    throw error
  }
}

/**
 * 활성 차량 전체의 최신 적재 안전 상태.
 *
 * `GET /api/vehicles/load-safety/latest`
 *
 * 미수신 차량은 배열에 **포함되지 않는다**(빈 항목이 아니라 없음). 화면 진입 시 한 번 호출해
 * 차량별 초기 상태를 채우고, 이후 갱신은 WebSocket 이 담당한다.
 */
export async function fetchAllLatestLoadSafety(
  signal?: AbortSignal,
): Promise<LoadSafetyState[]> {
  return getJson<LoadSafetyState[]>("/api/vehicles/load-safety/latest", signal)
}
