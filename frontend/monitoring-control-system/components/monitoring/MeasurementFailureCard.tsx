"use client"

import { AlertTriangle, PackageX } from "lucide-react"
import { cn } from "@/lib/utils"
import { getMeasurementFailureView } from "@/lib/monitoring/measurementFailure"
import type { DashboardFailure } from "@/types/monitoring"

/**
 * MeasurementFailureCard
 *
 * 선택 차량의 마지막 실패를 원인과 조치 안내로 나눠 보여준다.
 *
 * 작업자가 화면만 보고 바로 행동할 수 있어야 하므로 <b>원인 한 줄 + 조치 한 줄</b>을 항상 분리한다.
 * 실패가 없으면 아무것도 렌더링하지 않아 기존 UI 가 그대로 유지된다.
 *
 * 문구는 전부 `lib/monitoring/measurementFailure.ts` 가 만든다 — 여기에 조건문을 두지 않는다.
 */
export function MeasurementFailureCard({
  failure,
  className,
}: {
  failure: DashboardFailure | null | undefined
  className?: string
}) {
  const view = getMeasurementFailureView(failure)
  if (!view) return null

  const warning = view.severity === "warning"
  const Icon = warning ? PackageX : AlertTriangle

  return (
    <section
      className={cn(
        "rounded-md border px-2 py-1.5",
        warning
          ? "border-amber-500/50 bg-amber-950/30"
          : "border-red-500/50 bg-red-950/30",
        className,
      )}
      role="alert"
      aria-label="작업 실패 원인"
      data-testid="measurement-failure-card"
      data-severity={view.severity}
    >
      <div className="flex min-w-0 items-start gap-1.5">
        <Icon
          className={cn("mt-px size-4 shrink-0", warning ? "text-amber-300" : "text-red-300")}
          aria-hidden="true"
        />
        <div className="min-w-0 flex-1">
          <p
            className={cn(
              "text-xs font-semibold",
              warning ? "text-amber-200" : "text-red-200",
            )}
          >
            {view.title}
          </p>
          {/* 긴 문구는 줄바꿈되어야 한다 — truncate 를 쓰면 조치 안내가 잘려 쓸모없어진다. */}
          <p className="mt-0.5 text-xs text-pretty text-slate-100">{view.description}</p>
          {view.action ? (
            <p className="mt-0.5 text-[11px] text-pretty text-slate-300">{view.action}</p>
          ) : null}

          {view.details.length > 0 ? (
            <dl className="mt-1 flex flex-wrap gap-x-3 gap-y-0.5">
              {view.details.map((detail) => (
                <div key={detail.label} className="flex items-baseline gap-1">
                  <dt className="text-[10px] text-slate-400">{detail.label}</dt>
                  <dd className="font-mono text-[11px] text-slate-100">{detail.value}</dd>
                </div>
              ))}
            </dl>
          ) : null}
        </div>
      </div>
    </section>
  )
}

export default MeasurementFailureCard
