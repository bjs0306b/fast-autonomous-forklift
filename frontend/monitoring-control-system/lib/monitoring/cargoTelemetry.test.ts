import assert from "node:assert/strict"
import test from "node:test"
import { normalizeReportedCargoTelemetry } from "./cargoTelemetry.ts"

test("Isaac 적재 여부와 cargo.h 전체 높이를 보존한다", () => {
  const telemetry = normalizeReportedCargoTelemetry({
    reportedLoaded: true,
    reportedCargoId: " C0007 ",
    reportedCargoHeight: 1.11,
  })

  assert.deepEqual(telemetry, {
    loaded: true,
    reportedCargoId: "C0007",
    reportedCargoHeight: 1.11,
  })
})

test("잘못된 Isaac 화물 높이는 화면 값으로 만들지 않는다", () => {
  assert.equal(normalizeReportedCargoTelemetry({ reportedCargoHeight: 0 }).reportedCargoHeight, null)
  assert.equal(
    normalizeReportedCargoTelemetry({ reportedCargoHeight: Number.NaN }).reportedCargoHeight,
    null,
  )
})
