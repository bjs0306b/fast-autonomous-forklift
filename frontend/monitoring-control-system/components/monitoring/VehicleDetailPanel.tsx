"use client"

import { Ban, OctagonAlert } from "lucide-react"
import { cn } from "@/lib/utils"
import type { MockVehicle } from "@/types/monitoring"
import { VEHICLE_STATUS_COLOR, VEHICLE_STATUS_LABEL } from "./vehicle-status"

/**
 * VehicleDetailPanel
 *
 * 미니맵에서 선택한 차량의 상세 정보를 표시하고,
 * 개별 STOP / EMERGENCY STOP 제어 버튼을 제공한다.
 * (선택된 차량이 없으면 안내 문구만 표시)
 */
export function VehicleDetailPanel({
  vehicle,
  onStop,
  onEmergencyStop,
  className,
}: {
  vehicle: MockVehicle | null
  onStop?: (vehicleId: string) => void
  onEmergencyStop?: (vehicleId: string) => void
  className?: string
}) {
  return (
    <section
      className={cn(
        "flex min-h-0 flex-col overflow-hidden rounded-lg border border-slate-700 bg-[#0b1220]",
        className,
      )}
      aria-label="선택 차량 상세 정보"
    >
      <header className="flex items-center justify-between border-b border-slate-800 px-3 py-2">
        <span className="text-xs font-semibold text-slate-100">차량 상세</span>
        <span className="text-[10px] text-slate-400">Vehicle Detail</span>
      </header>

      {vehicle ? (
        <VehicleDetailContent
          vehicle={vehicle}
          onStop={onStop}
          onEmergencyStop={onEmergencyStop}
        />
      ) : (
        <div className="flex flex-1 items-center justify-center p-6 text-center">
          <p className="text-xs text-slate-400 text-pretty">
            미니맵에서 차량을 선택하면 상세 정보가 표시됩니다.
          </p>
        </div>
      )}
    </section>
  )
}

function VehicleDetailContent({
  vehicle,
  onStop,
  onEmergencyStop,
}: {
  vehicle: MockVehicle
  onStop?: (vehicleId: string) => void
  onEmergencyStop?: (vehicleId: string) => void
}) {
  const color = VEHICLE_STATUS_COLOR[vehicle.status]
  const statusLabel = VEHICLE_STATUS_LABEL[vehicle.status] ?? vehicle.status
  const loc = vehicle.location

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto p-3">
      {/* 헤더: 차량명 + source */}
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="truncate text-sm font-semibold text-white">
            {vehicle.name ?? vehicle.shortLabel}
          </div>
          <div className="truncate font-mono text-[11px] text-slate-400">{vehicle.vehicleId}</div>
        </div>
        <span
          className={cn(
            "shrink-0 rounded px-1.5 py-0.5 text-[10px] font-semibold",
            vehicle.source === "REAL"
              ? "bg-emerald-500/15 text-emerald-300"
              : "bg-sky-500/15 text-sky-300",
          )}
        >
          {vehicle.source}
        </span>
      </div>

      {/* 상태 */}
      <div className="flex items-center gap-2 rounded-md bg-white/5 px-2.5 py-2">
        <span className="size-2.5 rounded-full" style={{ backgroundColor: color.base }} aria-hidden="true" />
        <span
          className={cn(
            "text-sm font-medium",
            vehicle.status === "ESTOP" ? "text-red-300" : "text-slate-100",
          )}
        >
          {statusLabel}
        </span>
      </div>

      {/* 속성 그리드 */}
      <dl className="grid grid-cols-2 gap-2 text-xs">
        <DetailField label="X" value={loc ? loc.x.toFixed(1) : "—"} mono />
        <DetailField label="Y" value={loc ? loc.y.toFixed(1) : "—"} mono />
        <DetailField
          label="Heading"
          value={typeof vehicle.heading === "number" ? `${vehicle.heading}°` : "—"}
          mono
        />
        <DetailField
          label="Speed"
          value={typeof vehicle.speed === "number" ? `${vehicle.speed}` : "—"}
          mono
        />
        <DetailField
          label="현재 작업"
          value={vehicle.currentTask ?? "—"}
          className="col-span-2"
        />
      </dl>

      {/* 제어 버튼 */}
      <div className="mt-auto flex flex-col gap-2 pt-1">
        <button
          type="button"
          onClick={() => onStop?.(vehicle.vehicleId)}
          className="inline-flex items-center justify-center gap-2 rounded-md border border-slate-600 bg-slate-800 px-3 py-2 text-sm font-medium text-slate-100 transition-colors hover:bg-slate-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sky-400"
        >
          <Ban className="size-4" aria-hidden="true" />
          STOP
        </button>
        <button
          type="button"
          onClick={() => onEmergencyStop?.(vehicle.vehicleId)}
          className="inline-flex items-center justify-center gap-2 rounded-md bg-red-600 px-3 py-2 text-sm font-semibold text-white transition-colors hover:bg-red-500 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-red-300"
        >
          <OctagonAlert className="size-4" aria-hidden="true" />
          EMERGENCY STOP
        </button>
      </div>
    </div>
  )
}

function DetailField({
  label,
  value,
  mono,
  className,
}: {
  label: string
  value: string
  mono?: boolean
  className?: string
}) {
  return (
    <div className={cn("rounded-md bg-white/5 px-2.5 py-1.5", className)}>
      <dt className="text-[10px] uppercase tracking-wide text-slate-400">{label}</dt>
      <dd className={cn("mt-0.5 truncate text-slate-100", mono && "font-mono")}>{value}</dd>
    </div>
  )
}

export default VehicleDetailPanel
