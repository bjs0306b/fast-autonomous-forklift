import assert from "node:assert/strict"
import test from "node:test"
import { normalizeReportedVehicleStatus } from "./vehicleStatus.ts"

test("Isaac telemetry 의 MOVING 상태를 관제 상태로 보존한다", () => {
  assert.equal(normalizeReportedVehicleStatus("MOVING"), "MOVING")
  assert.equal(normalizeReportedVehicleStatus(" moving "), "MOVING")
})

test("Isaac telemetry 의 HOLDING 상태를 관제 대기로 보존한다", () => {
  assert.equal(normalizeReportedVehicleStatus("HOLDING"), "HOLDING")
})

test("계약 밖 상태는 기존 화면 상태를 지우지 않도록 null 처리한다", () => {
  assert.equal(normalizeReportedVehicleStatus("NAVIGATING"), null)
  assert.equal(normalizeReportedVehicleStatus("UNKNOWN"), null)
  assert.equal(normalizeReportedVehicleStatus(null), null)
})
