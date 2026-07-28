"use client"

import { cn } from "@/lib/utils"
import { worldToPercent, type WorldBounds } from "@/lib/coordinate"
import type { MockVehicle } from "@/types/monitoring"
import { MiniMapVehicleMarker } from "./MiniMapVehicleMarker"

/**
 * MiniMap
 *
 * 축소된 평면 관제 지도. 창고 랙/통로/작업 구역 구조 위에 차량 마커를 배치한다.
 * (원래 MockDigitalTwinView 에 있던 평면도 + 좌표 배치 로직을 이곳으로 이동)
 *
 * - location 이 null 인 차량은 표시하지 않는다.
 * - 마커 클릭 시 팝업 없이 onSelectVehicle(vehicleId) 만 호출한다.
 * - STOP / 배터리 등은 표시하지 않는다.
 */
export function MiniMap({
  vehicles,
  selectedVehicleId,
  onSelectVehicle,
  bounds,
  className,
}: {
  vehicles: MockVehicle[]
  selectedVehicleId?: string | null
  onSelectVehicle?: (vehicleId: string) => void
  bounds?: WorldBounds
  className?: string
}) {
  const placedVehicles = vehicles.filter((v) => v.location != null)

  return (
    <section
      className={cn(
        "flex min-h-0 flex-col overflow-hidden rounded-lg border border-slate-700 bg-[#0b1220]",
        className,
      )}
      aria-label="창고 미니맵"
    >
      <header className="flex items-center justify-between border-b border-slate-800 px-3 py-2">
        <span className="text-xs font-semibold text-slate-100">미니맵</span>
        <span className="text-[10px] text-slate-400">Warehouse Map</span>
      </header>

      <div className="relative min-h-0 flex-1">
        <div className="absolute inset-0 overflow-hidden">
          {/* 바닥 그리드 */}
          <div
            className="absolute inset-0 opacity-30"
            style={{
              backgroundImage:
                "linear-gradient(to right, rgba(148,163,184,0.12) 1px, transparent 1px), linear-gradient(to bottom, rgba(148,163,184,0.12) 1px, transparent 1px)",
              backgroundSize: "24px 24px",
            }}
            aria-hidden="true"
          />

          {/* 창고 외곽 + 내부 구조 */}
          <div className="absolute inset-2 rounded border border-slate-600/60">
            <WarehouseLayout />
          </div>

          {/* 차량 마커 */}
          {placedVehicles.map((vehicle) => {
            const pos = worldToPercent(vehicle.location!.x, vehicle.location!.y, bounds)
            return (
              <MiniMapVehicleMarker
                key={vehicle.vehicleId}
                vehicle={vehicle}
                left={pos.left}
                top={pos.top}
                selected={vehicle.vehicleId === selectedVehicleId}
                onSelect={onSelectVehicle}
              />
            )
          })}
        </div>
      </div>
    </section>
  )
}

/** 창고 내부 정적 구조: 랙 / 통로 / 작업 구역 / 적재 공간 */
function WarehouseLayout() {
  return (
    <div className="absolute inset-0">
      <ZoneLabel className="left-[4%] top-[2%]">RACK A</ZoneLabel>
      <RackBlock className="left-[4%] top-[9%] h-[16%] w-[26%]" />
      <RackBlock className="left-[4%] top-[30%] h-[16%] w-[26%]" />
      <RackBlock className="left-[4%] top-[58%] h-[16%] w-[26%]" />
      <RackBlock className="left-[4%] top-[79%] h-[14%] w-[26%]" />

      <Aisle className="left-[33%] top-[6%] h-[88%] w-[6%]" vertical />

      <ZoneLabel className="left-[41%] top-[2%]">RACK B</ZoneLabel>
      <RackBlock className="left-[41%] top-[9%] h-[36%] w-[22%]" />
      <RackBlock className="left-[41%] top-[58%] h-[35%] w-[22%]" />

      <Aisle className="left-[41%] top-[47%] h-[9%] w-[55%]" />

      <ZoneLabel className="right-[3%] top-[2%]">작업</ZoneLabel>
      <WorkZone className="right-[3%] top-[9%] h-[36%] w-[31%]" label="ASSEMBLY" />

      <ZoneLabel className="right-[3%] top-[52%]">적재</ZoneLabel>
      <LoadingZone className="right-[3%] top-[58%] h-[35%] w-[31%]" />
    </div>
  )
}

function RackBlock({ className }: { className?: string }) {
  return (
    <div
      className={cn("absolute rounded-sm border border-slate-500/40 bg-slate-700/30", className)}
      aria-hidden="true"
    >
      <div
        className="size-full opacity-60"
        style={{
          backgroundImage:
            "repeating-linear-gradient(to right, rgba(148,163,184,0.25) 0 1px, transparent 1px 14px), repeating-linear-gradient(to bottom, rgba(148,163,184,0.18) 0 1px, transparent 1px 12px)",
        }}
      />
    </div>
  )
}

function Aisle({ className, vertical }: { className?: string; vertical?: boolean }) {
  return (
    <div className={cn("absolute rounded-sm bg-slate-800/40", className)} aria-hidden="true">
      <div
        className={cn(
          "absolute",
          vertical ? "inset-y-2 left-1/2 w-px -translate-x-1/2" : "inset-x-2 top-1/2 h-px -translate-y-1/2",
        )}
        style={{
          backgroundImage: vertical
            ? "repeating-linear-gradient(to bottom, rgba(234,179,8,0.5) 0 6px, transparent 6px 12px)"
            : "repeating-linear-gradient(to right, rgba(234,179,8,0.5) 0 6px, transparent 6px 12px)",
        }}
      />
    </div>
  )
}

function WorkZone({ className, label }: { className?: string; label: string }) {
  return (
    <div
      className={cn("absolute rounded-sm border border-sky-500/30 bg-sky-500/5", className)}
      aria-hidden="true"
    >
      <div
        className="size-full"
        style={{
          backgroundImage:
            "repeating-linear-gradient(45deg, rgba(56,189,248,0.10) 0 6px, transparent 6px 12px)",
        }}
      />
      <span className="absolute bottom-0.5 right-1 text-[8px] font-medium tracking-wider text-sky-300/70">
        {label}
      </span>
    </div>
  )
}

function LoadingZone({ className }: { className?: string }) {
  return (
    <div
      className={cn("absolute rounded-sm border border-amber-500/30 bg-amber-500/5", className)}
      aria-hidden="true"
    >
      <div className="flex size-full items-end gap-0.5 p-1">
        {Array.from({ length: 6 }).map((_, i) => (
          <div key={i} className="h-full flex-1 rounded-[2px] border border-amber-400/20 bg-amber-400/5" />
        ))}
      </div>
    </div>
  )
}

function ZoneLabel({ className, children }: { className?: string; children: React.ReactNode }) {
  return (
    <span
      className={cn(
        "absolute text-[8px] font-semibold uppercase tracking-widest text-slate-400/80",
        className,
      )}
      aria-hidden="true"
    >
      {children}
    </span>
  )
}

export default MiniMap
