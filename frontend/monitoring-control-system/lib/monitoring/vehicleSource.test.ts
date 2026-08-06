import assert from "node:assert/strict"
import test from "node:test"
import { resolveVehicleSource } from "./vehicleSource.ts"

test("백엔드 정규 ID와 Isaac 외부 ID를 모두 시뮬 차량으로 분류한다", () => {
  for (const vehicleId of ["SIM-F01", "SIM_F02", "sim03", "SIM01", "sim-02"]) {
    assert.equal(resolveVehicleSource(vehicleId), "sim", vehicleId)
  }
})

test("실물 ID와 출처를 알 수 없는 ID는 기존 구분을 유지한다", () => {
  assert.equal(resolveVehicleSource("REAL-F01"), "real")
  assert.equal(resolveVehicleSource("FORKLIFT-01"), "unknown")
  assert.equal(resolveVehicleSource("SIM-FLOW"), "unknown")
})
