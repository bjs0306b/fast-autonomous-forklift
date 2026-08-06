export interface ReportedCargoTelemetry {
  loaded: boolean | null
  reportedCargoId: string | null
  reportedCargoHeight: number | null
}

/** 백엔드 VEHICLE_LOCATION_UPDATED 이벤트의 Isaac 화물 필드를 안전하게 정규화한다. */
export function normalizeReportedCargoTelemetry(
  data: Record<string, unknown>,
): ReportedCargoTelemetry {
  return {
    loaded: typeof data.reportedLoaded === "boolean" ? data.reportedLoaded : null,
    reportedCargoId:
      typeof data.reportedCargoId === "string" && data.reportedCargoId.trim()
        ? data.reportedCargoId.trim()
        : null,
    reportedCargoHeight:
      typeof data.reportedCargoHeight === "number" &&
      Number.isFinite(data.reportedCargoHeight) &&
      data.reportedCargoHeight > 0
        ? data.reportedCargoHeight
        : null,
  }
}
