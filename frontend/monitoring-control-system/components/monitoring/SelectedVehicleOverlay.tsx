import { cn } from "@/lib/utils"
import type { SelectedVehicleSummary } from "@/types/monitoring"
import { VEHICLE_STATUS_COLOR, VEHICLE_STATUS_LABEL } from "./vehicle-status"

/**
 * 선택 차량 ID / 상태 / 작업을 화면 상단 우측의 작은 오버레이로 항상 표시한다.
 * (숨기기 버튼 없음)
 */
export function SelectedVehicleOverlay({ vehicle }: { vehicle: SelectedVehicleSummary }) {
  const statusLabel = VEHICLE_STATUS_LABEL[vehicle.status] ?? vehicle.status
  const color = VEHICLE_STATUS_COLOR[vehicle.status]
  const sourceLabel = vehicle.source === "REAL" ? "REAL" : "SIM"

  return (
    <div className="pointer-events-none w-52 rounded-md bg-slate-900/80 p-2.5 shadow-lg ring-1 ring-white/10 backdrop-blur-sm">
      <div className="flex items-center justify-between gap-2">
        <span className="text-[10px] font-medium uppercase tracking-wide text-slate-400">선택 차량</span>
        <span className="rounded bg-white/10 px-1.5 py-0.5 text-[9px] font-semibold text-slate-200">
          {sourceLabel}
        </span>
      </div>

      <div className="mt-1 font-mono text-sm font-semibold text-white">{vehicle.vehicleId}</div>

      <div className="mt-2 flex items-center gap-1.5">
        <span
          className="size-2 rounded-full"
          style={{ backgroundColor: color.base }}
          aria-hidden="true"
        />
        <span
          className={cn(
            "text-xs font-medium",
            vehicle.status === "ESTOP" ? "text-red-300" : "text-slate-200",
          )}
        >
          {statusLabel}
        </span>
      </div>

      {vehicle.currentTask ? (
        <div className="mt-2 truncate rounded bg-white/5 px-2 py-1 text-[11px] text-slate-300">
          작업: {vehicle.currentTask}
        </div>
      ) : null}
    </div>
  )
}

export default SelectedVehicleOverlay
