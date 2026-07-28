"use client"

import { Ban, OctagonAlert } from "lucide-react"
import { cn } from "@/lib/utils"
import { formatClockTime, formatNumber } from "@/lib/format"
import type { DashboardVehicle } from "@/types/monitoring"
import { VEHICLE_STATUS_COLOR, VEHICLE_STATUS_LABEL, isAlertStatus } from "./vehicle-status"

/**
 * VehicleDetailPanel
 *
 * 미니맵에서 선택한 차량의 상세 정보를 표시하고,
 * 개별 STOP / EMERGENCY STOP 제어 버튼을 제공한다.
 *
 * 표시 값은 전부 props 로 내려온 selectedVehicle 에서만 읽는다 —
 * 상태/위치 WebSocket 이벤트가 도착하면 상위 상태가 갱신되어 이 패널도 자동으로 다시 그려진다.
 *
 * NOTE(FR-402-1): STOP / EMERGENCY STOP 버튼은 아직 실제 API 에 연결하지 않는다(이번 범위 밖).
 */
export function VehicleDetailPanel({
  vehicle,
  onStop,
  onEmergencyStop,
  className,
}: {
  vehicle: DashboardVehicle | null
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
          <p className="text-xs text-pretty text-slate-400">
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
  vehicle: DashboardVehicle
  onStop?: (vehicleId: string) => void
  onEmergencyStop?: (vehicleId: string) => void
}) {
  const color = VEHICLE_STATUS_COLOR[vehicle.status] ?? VEHICLE_STATUS_COLOR.UNKNOWN
  const statusLabel = VEHICLE_STATUS_LABEL[vehicle.status] ?? vehicle.status
  const loc = vehicle.location
  const task = vehicle.currentTask

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto p-3">
      {/* 헤더: 차량명 + source */}
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="truncate text-sm font-semibold text-white">{vehicle.name}</div>
          <div className="truncate font-mono text-[11px] text-slate-400">{vehicle.vehicleId}</div>
        </div>
        <div className="flex shrink-0 flex-col items-end gap-1">
          <span
            className={cn(
              "rounded px-1.5 py-0.5 text-[10px] font-semibold",
              vehicle.source === "REAL"
                ? "bg-emerald-500/15 text-emerald-300"
                : "bg-sky-500/15 text-sky-300",
            )}
          >
            {vehicle.source}
          </span>
          {!vehicle.active ? (
            <span className="rounded bg-slate-500/15 px-1.5 py-0.5 text-[10px] font-semibold text-slate-300">
              비활성
            </span>
          ) : null}
        </div>
      </div>

      {/* 상태 */}
      <div className="flex items-center justify-between gap-2 rounded-md bg-white/5 px-2.5 py-2">
        <div className="flex items-center gap-2">
          <span
            className="size-2.5 rounded-full"
            style={{ backgroundColor: color.base }}
            aria-hidden="true"
          />
          <span
            className={cn(
              "text-sm font-medium",
              isAlertStatus(vehicle.status) ? "text-red-300" : "text-slate-100",
            )}
          >
            {statusLabel}
          </span>
        </div>
        <span className="font-mono text-[10px] text-slate-400">
          {formatClockTime(vehicle.lastUpdatedAt)}
        </span>
      </div>

      {/* 위치 */}
      <div>
        <div className="mb-1 flex items-center justify-between">
          <span className="text-[10px] font-medium tracking-wide text-slate-400 uppercase">위치</span>
          {loc ? (
            <span className="rounded bg-white/5 px-1.5 py-0.5 font-mono text-[9px] text-slate-400">
              {loc.source} · {loc.frameId ?? "—"}
            </span>
          ) : null}
        </div>

        {loc ? (
          <dl className="grid grid-cols-2 gap-2 text-xs">
            <DetailField label="X (m)" value={formatNumber(loc.x, 2)} mono />
            <DetailField label="Y (m)" value={formatNumber(loc.y, 2)} mono />
            <DetailField
              label="Heading (°)"
              value={loc.heading != null ? formatNumber(loc.heading, 1) : "—"}
              mono
            />
            <DetailField
              label="Speed (m/s)"
              value={loc.speed != null ? formatNumber(loc.speed, 2) : "—"}
              mono
            />
            <DetailField label="위치 생성" value={formatClockTime(loc.messageAt)} mono />
            <DetailField label="위치 수신" value={formatClockTime(loc.receivedAt)} mono />
          </dl>
        ) : (
          <p className="rounded-md bg-white/5 px-2.5 py-2 text-xs text-slate-400">
            위치 미수신 — 미니맵에 표시되지 않습니다.
          </p>
        )}
      </div>

      {/* 현재 작업 */}
      <div>
        <div className="mb-1 text-[10px] font-medium tracking-wide text-slate-400 uppercase">
          현재 작업
        </div>
        {task ? (
          <dl className="grid grid-cols-2 gap-2 text-xs">
            <DetailField label="Task" value={task.taskId} mono className="col-span-2" />
            <DetailField label="작업 상태" value={task.status} />
            <DetailField label="명령 상태" value={task.commandStatus ?? "—"} />
          </dl>
        ) : (
          <p className="rounded-md bg-white/5 px-2.5 py-2 text-xs text-slate-400">
            진행 중인 작업이 없습니다.
          </p>
        )}
      </div>

      {/* 제어 버튼 */}
      <div className="mt-auto flex flex-col gap-2 pt-1">
        <button
          type="button"
          onClick={() => onStop?.(vehicle.vehicleId)}
          className="inline-flex items-center justify-center gap-2 rounded-md border border-slate-600 bg-slate-800 px-3 py-2 text-sm font-medium text-slate-100 transition-colors hover:bg-slate-700 focus-visible:ring-2 focus-visible:ring-sky-400 focus-visible:outline-none"
        >
          <Ban className="size-4" aria-hidden="true" />
          STOP
        </button>
        <button
          type="button"
          onClick={() => onEmergencyStop?.(vehicle.vehicleId)}
          className="inline-flex items-center justify-center gap-2 rounded-md bg-red-600 px-3 py-2 text-sm font-semibold text-white transition-colors hover:bg-red-500 focus-visible:ring-2 focus-visible:ring-red-300 focus-visible:outline-none"
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
      <dt className="text-[10px] tracking-wide text-slate-400 uppercase">{label}</dt>
      <dd className={cn("mt-0.5 truncate text-slate-100", mono && "font-mono")}>{value}</dd>
    </div>
  )
}

export default VehicleDetailPanel
