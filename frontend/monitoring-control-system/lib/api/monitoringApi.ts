import type { DashboardResponse } from "@/types/monitoring"
import { getJson } from "./httpClient"

/**
 * 관제 초기 데이터 조회.
 *
 * 이 한 번의 호출로 차량 목록·상태·최신 위치·현재 작업이 모두 채워진다
 * (백엔드가 name/source/active/location 메타/currentTask 를 모두 포함하도록 보완됨).
 * 따라서 GET /api/vehicles 나 /api/vehicles/locations/latest 를 추가로 부르지 않는다.
 */
export async function fetchMonitoringDashboard(signal?: AbortSignal): Promise<DashboardResponse> {
  const data = await getJson<DashboardResponse>("/api/monitoring/dashboard", signal)

  // 백엔드가 항상 배열을 주지만, 방어적으로 보정해 렌더 단계에서 터지지 않게 한다.
  return {
    vehicles: Array.isArray(data.vehicles) ? data.vehicles : [],
    tasks: Array.isArray(data.tasks) ? data.tasks : [],
  }
}
