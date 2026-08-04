/**
 * 관제 화면의 차량 <b>표시 순서</b>.
 *
 * 관제 대상 차량은 Isaac Sim 의 `SIM-F01` 한 대다. 나머지는 뒤에 붙는다.
 *
 * <b>순서는 선택과 무관하다.</b> 맨 앞 차량이 자동으로 선택되지 않으며, 상세 패널의 선택은 오직
 * 사용자 클릭으로만 생긴다. 예전에는 이 파일이 기본 선택까지 정했는데(`resolvePreferredVehicleId`),
 * 고르지도 않은 차량의 정보가 상세에 떠서 어느 차량을 보고 있는지 헷갈리게 만들었다.
 * 그 함수는 제거했다 — 남겨 두면 같은 동작이 다시 붙는다.
 *
 * <b>ID 는 어디에서도 치환하지 않는다.</b> 실물/시뮬 구분은 별도 필드가 아니라 ID 접두어로 하며,
 * 그 판정은 `resolveVehicleSource` 가 한다. 실물 차량(`REAL-F01`)은 관제 대상에서 빠졌고
 * DB 에도 등록돼 있지 않다 — 목록에 나타나지 않으므로 전용 순위가 필요 없다.
 */

/** 관제 대상 차량(Isaac Sim). 목록 맨 앞에 온다. */
export const PRIMARY_VEHICLE_ID = "SIM-F01"

/** 작을수록 앞. 같은 순위 안에서는 vehicleId 오름차순으로 정렬한다. */
export function getVehiclePriority(vehicleId: string): number {
  if (vehicleId === PRIMARY_VEHICLE_ID) return 0
  return 1
}

export function compareVehicleIds(a: string, b: string): number {
  const diff = getVehiclePriority(a) - getVehiclePriority(b)
  return diff !== 0 ? diff : a.localeCompare(b)
}

/**
 * 우선순위대로 정렬한 새 배열을 돌려준다.
 *
 * `Array.prototype.sort` 는 제자리 정렬이라 API 응답 배열을 그대로 넘기면 원본이 바뀐다.
 * 호출부가 실수하지 않도록 이 함수가 항상 복사본을 만든다.
 */
export function sortVehicleIds(vehicleIds: readonly string[]): string[] {
  return [...vehicleIds].sort(compareVehicleIds)
}
