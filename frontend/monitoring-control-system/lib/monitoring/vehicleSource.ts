export type VehicleSource = "real" | "sim" | "unknown"

/**
 * 백엔드 정규 ID(SIM-F01)와 Isaac 외부 ID(sim01)를 같은 시뮬 차량으로 분류한다.
 * 하이픈·언더스코어 유무와 대소문자만 허용하고, SIM-FLOW 같은 임의 접두어는 시뮬로 오인하지 않는다.
 */
export function resolveVehicleSource(vehicleId: string): VehicleSource {
  const compactId = vehicleId.trim().toUpperCase().replace(/[-_]/g, "")
  if (/^REALF?\d+$/.test(compactId)) return "real"
  if (/^SIMF?\d+$/.test(compactId)) return "sim"
  return "unknown"
}
