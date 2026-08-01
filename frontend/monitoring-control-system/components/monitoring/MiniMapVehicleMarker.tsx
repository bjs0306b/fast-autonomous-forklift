"use client"

import { cn } from "@/lib/utils"
import { toShortLabel } from "@/lib/vehicleStatus"
import type { DashboardVehicle } from "@/types/monitoring"
import { VEHICLE_STATUS_COLOR, VEHICLE_STATUS_LABEL } from "./vehicle-status"

export function MiniMapVehicleMarker({
  vehicle,
  left,
  top,
  selected,
  onSelect,
}: {
  vehicle: DashboardVehicle
  left: number
  top: number
  selected: boolean
  onSelect?: (vehicleId: string) => void
}) {
  const color = VEHICLE_STATUS_COLOR[vehicle.status] ?? VEHICLE_STATUS_COLOR.UNKNOWN
  const statusLabel = VEHICLE_STATUS_LABEL[vehicle.status] ?? vehicle.status
  const shortLabel = toShortLabel(vehicle.vehicleId)
  const heading = vehicle.location?.heading ?? null

  return (
    <button
      type="button"
      onClick={() => onSelect?.(vehicle.vehicleId)}
      className="absolute z-10 flex -translate-x-1/2 -translate-y-1/2 flex-col items-center gap-0.5 transition-[left,top] duration-500 ease-out focus:outline-none"
      style={{ left: `${left}%`, top: `${top}%` }}
      aria-label={`${shortLabel} · ${statusLabel}${selected ? " (선택됨)" : ""}`}
      aria-pressed={selected}
    >
      <span
        className={cn(
          "rounded px-1 py-px font-mono text-[9px] font-semibold leading-none shadow-sm",
          selected ? "ring-1 ring-white/70" : "",
        )}
        style={{ backgroundColor: "rgba(15,23,42,0.9)", color: color.border }}
      >
        {shortLabel}
      </span>
      <span className="relative flex items-center justify-center">
        {selected ? (
          <span
            className="pointer-events-none absolute size-7 rounded-full motion-safe:animate-pulse"
            style={{ boxShadow: `0 0 0 2px rgba(${color.glow},0.9), 0 0 14px 3px rgba(${color.glow},0.55)` }}
            aria-hidden="true"
          />
        ) : null}
        {typeof heading === "number" ? (
          <span
            className="pointer-events-none absolute -top-1.5 size-0 transition-transform duration-500 ease-out"
            style={{
              transform: `rotate(${heading}deg)`,
              transformOrigin: "center 12px",
              borderLeft: "3px solid transparent",
              borderRight: "3px solid transparent",
              borderBottom: `5px solid ${color.border}`,
            }}
            aria-hidden="true"
          />
        ) : null}
        <span
          className="relative block size-3.5 rounded-full border"
          style={{ backgroundColor: color.base, borderColor: color.border }}
          aria-hidden="true"
        />
      </span>
    </button>
  )
}

export default MiniMapVehicleMarker
