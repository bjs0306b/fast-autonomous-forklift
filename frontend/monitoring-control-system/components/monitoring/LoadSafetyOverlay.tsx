"use client"

import { OctagonAlert, TriangleAlert } from "lucide-react"
import { cn } from "@/lib/utils"
import { isAlertingRiskLevel } from "@/types/loadSafety"
import type { LoadSafetyState } from "@/types/loadSafety"

/**
 * LoadSafetyOverlay
 *
 * 카메라·디지털 트윈 영상 위에 띄우는 적재 위험 경고(prompt63.md 3장 5번).
 *
 * <b>WARNING/DANGER 에서만 표시</b>한다 — 정상·주의까지 오버레이를 띄우면 영상이 상시 가려져
 * 조작자가 경고를 무시하게 된다. 이 기준은 백엔드 {@code LoadSafetyRiskLevel.isAlerting()} 과 같다.
 *
 * 영상 자체를 가리지 않도록 화면 하단에 배치하고 {@code pointer-events-none} 으로 클릭을 통과시킨다 —
 * 경고가 떠 있는 동안에도 전체화면 버튼 등 기존 조작이 막히면 안 된다.
 */
export function LoadSafetyOverlay({
  loadSafety,
  vehicleLabel,
  className,
}: {
  loadSafety: LoadSafetyState | null
  /** 어느 차량의 경고인지 표시(선택 차량명 또는 vehicleId) */
  vehicleLabel?: string | null
  className?: string
}) {
  if (!loadSafety || !isAlertingRiskLevel(loadSafety.riskLevel)) {
    return null
  }

  const danger = loadSafety.riskLevel === "DANGER"
  const Icon = danger ? OctagonAlert : TriangleAlert

  return (
    <div
      className={cn(
        "pointer-events-none absolute inset-x-3 bottom-3 z-30 flex justify-center",
        className,
      )}
      // 경고는 즉시 읽혀야 하므로 assertive. 상태 배지가 아니라 알림이다.
      role="alert"
      aria-live="assertive"
      data-testid="load-safety-overlay"
      data-risk-level={loadSafety.riskLevel}
    >
      <div
        className={cn(
          "flex max-w-2xl items-start gap-3 rounded-lg border px-4 py-3 shadow-xl backdrop-blur-sm",
          danger
            ? "border-red-500/60 bg-red-950/85 text-red-50"
            : "border-orange-500/50 bg-orange-950/80 text-orange-50",
        )}
      >
        <Icon
          className={cn("mt-0.5 size-6 shrink-0", danger ? "text-red-400" : "text-orange-400")}
          aria-hidden="true"
        />
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5">
            <span className="text-sm font-bold tracking-wide">
              {danger ? "적재 위험" : "적재 경고"}
            </span>
            {vehicleLabel ? (
              <span className="font-mono text-[11px] opacity-90">{vehicleLabel}</span>
            ) : null}
            {loadSafety.riskCode ? (
              <span className="rounded bg-black/30 px-1.5 py-0.5 font-mono text-[10px] opacity-90">
                {loadSafety.riskCode}
              </span>
            ) : null}
          </div>
          <p className="mt-0.5 text-xs text-pretty opacity-95">
            {loadSafety.message ?? "적재 상태를 확인해 주세요."}
          </p>
        </div>
      </div>
    </div>
  )
}

export default LoadSafetyOverlay
