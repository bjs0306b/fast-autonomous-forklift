"use client"

import { cn } from "@/lib/utils"
import type { MockVehicle } from "@/types/monitoring"
import { VEHICLE_STATUS_COLOR, VEHICLE_STATUS_LABEL } from "./vehicle-status"

/**
 * MiniMapVehicleMarker
 *
 * 미니맵 위의 개별 차량 마커.
 * - 상태별 색상 표시
 * - heading 방향 화살표
 * - REAL / SIMULATION 구분 (테두리 형태)
 * - 선택된 차량 강조
 * - 클릭 시 팝업 없이 onSelect(vehicleId) 만 호출
 */
export function MiniMapVehicleMarker({
  vehicle,
  left,
  top,
  selected,
  onSelect,
}: {
  vehicle: MockVehicle
  left: number
  top: number
  selected: boolean
  onSelect?: (vehicleId: string) => void
}) {
  const color = VEHICLE_STATUS_COLOR[vehicle.status]
  const statusLabel = VEHICLE_STATUS_LABEL[vehicle.status] ?? vehicle.status
  const isSim = vehicle.source === "SIMULATION"

  return (
    <button
      type="button"
      onClick={() => onSelect?.(vehicle.vehicleId)}
      className="absolute z-10 flex -translate-x-1/2 -translate-y-1/2 flex-col items-center gap-0.5 focus:outline-none"
      style={{ left: `${left}%`, top: `${top}%` }}
      aria-label={`${vehicle.shortLabel} · ${vehicle.source === "REAL" ? "실제" : "시뮬레이션"} · ${statusLabel}${selected ? " (선택됨)" : ""}`}
      aria-pressed={selected}
    >
      {/* ID 라벨 */}
      <span
        className={cn(
          "rounded px-1 py-px font-mono text-[9px] font-semibold leading-none shadow-sm",
          selected ? "ring-1 ring-white/70" : "",
        )}
        style={{ backgroundColor: "rgba(15,23,42,0.9)", color: color.border }}
      >
        {vehicle.shortLabel}
      </span>

      <span className="relative flex items-center justify-center">
        {/* 선택 glow */}
        {selected ? (
          <span
            className="absolute size-7 animate-pulse rounded-full"
            style={{ boxShadow: `0 0 0 2px rgba(${color.glow},0.9), 0 0 14px 3px rgba(${color.glow},0.55)` }}
            aria-hidden="true"
          />
        ) : null}

        {/* heading 방향 화살표. TODO(coordinate): 0도 기준축/회전 방향 미확정 */}
        {typeof vehicle.heading === "number" ? (
          <span
            className="absolute -top-1.5 size-0"
            style={{
              transform: `rotate(${vehicle.heading}deg)`,
              transformOrigin: "center 12px",
              borderLeft: "3px solid transparent",
              borderRight: "3px solid transparent",
              borderBottom: `5px solid ${color.border}`,
            }}
            aria-hidden="true"
          />
        ) : null}

        {/* 마커 본체. REAL=원형, SIMULATION=사각(점선 테두리)로 구분 */}
        <span
          className={cn(
            "relative block size-3.5 border",
            isSim ? "rounded-[2px] border-dashed" : "rounded-full",
          )}
          style={{ backgroundColor: color.base, borderColor: color.border }}
          aria-hidden="true"
        />
      </span>
    </button>
  )
}

export default MiniMapVehicleMarker
