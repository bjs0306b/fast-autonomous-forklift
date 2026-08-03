"use client"

import { RefreshCw } from "lucide-react"
import { cn } from "@/lib/utils"
import { worldToPercent, type WorldBounds } from "@/lib/coordinate"
import {
  INITIAL_VEHICLE_POSES,
  WAREHOUSE_INVERT_Y,
  WAREHOUSE_MAP_IMAGE_SIZE,
  WAREHOUSE_WORLD_BOUNDS,
} from "@/lib/config/warehouseMap"
import type { DashboardVehicle, RealtimeConnectionStatus } from "@/types/monitoring"
import { MiniMapVehicleMarker } from "./MiniMapVehicleMarker"
import { WarehouseMapSvg } from "./WarehouseMapSvg"

/** 미니맵에 그릴 수 있는 차량(위치 객체와 x/y가 모두 있는 차량)인지 판별한다. */
export function isVehicleWithRenderableLocation(
  vehicle: DashboardVehicle,
): vehicle is DashboardVehicle & { location: { x: number; y: number } & DashboardVehicle["location"] } {
  const location = vehicle.location
  return location != null && typeof location.x === "number" && typeof location.y === "number"
}

/** 마커 하나를 그리는 데 필요한 최소 정보. 실시간 위치와 초기 위치를 같은 형태로 맞춘다. */
export interface PlacedVehicle {
  vehicle: DashboardVehicle
  x: number
  y: number
  heading: number | null
  /** 실시간 위치가 아직 없어 초기 위치로 그린 마커인지. */
  pending: boolean
}

/**
 * 실시간 위치가 있으면 그 좌표를, 없으면 설정된 초기 위치를 쓴다.
 * 둘 다 없는 차량은 미니맵에 표시하지 않는다 — 위치를 모르는 차량을 임의 지점에 찍지 않는다.
 */
export function toPlacedVehicles(vehicles: DashboardVehicle[]): PlacedVehicle[] {
  const placed: PlacedVehicle[] = []
  for (const vehicle of vehicles) {
    if (isVehicleWithRenderableLocation(vehicle)) {
      placed.push({
        vehicle,
        x: vehicle.location.x,
        y: vehicle.location.y,
        heading: vehicle.location.heading,
        pending: false,
      })
      continue
    }
    const initial = INITIAL_VEHICLE_POSES[vehicle.vehicleId]
    if (initial) {
      placed.push({
        vehicle,
        x: initial.x,
        y: initial.y,
        heading: initial.heading,
        pending: true,
      })
    }
  }
  return placed
}

const REALTIME_BADGE: Record<RealtimeConnectionStatus, { label: string; className: string }> = {
  connected: { label: "실시간", className: "border-emerald-500/40 bg-emerald-950/50 text-emerald-200" },
  connecting: { label: "연결 중", className: "border-sky-500/40 bg-sky-950/50 text-sky-200" },
  disconnected: { label: "연결 끊김", className: "border-slate-500/40 bg-slate-800/60 text-slate-300" },
  error: { label: "연결 오류", className: "border-red-500/40 bg-red-950/40 text-red-200" },
}

/**
 * MiniMap
 *
 * 창고 평면도(SVG) 위에 차량 마커를 절대 위치로 얹는다.
 *
 * - 배경은 `WarehouseMapSvg` — `isaac_sim/nav2/maps/obstacles.txt` 의 실제 장애물 좌표로 그린다.
 *   PNG 를 쓰지 않는 이유는 그 컴포넌트 주석 참고.
 * - **배경과 마커가 같은 박스를 공유한다.** 컨테이너 비율을 맵 비율(20:30)로 고정하고 그 박스
 *   안에서만 퍼센트 좌표를 계산하므로, 패널 크기가 변해도 마커가 배경과 어긋나지 않는다.
 * - **차량이 0대여도 배경은 항상 그린다.** 지도가 사라지면 "이미지 문제인가?"로 오해하기 쉽다.
 * - 마커 클릭 시 팝업 없이 onSelectVehicle(vehicleId) 만 호출한다(기존 동작 유지).
 * - 통합 관제 패널의 상단 지도 영역으로 사용된다. 지도 기준 박스의 비율과 좌표 변환은 그대로
 *   유지하므로 패널이 넓어져도 지도가 왜곡되지 않는다.
 */
export function MiniMap({
  vehicles,
  selectedVehicleId,
  onSelectVehicle,
  realtimeStatus,
  onRefresh,
  bounds = WAREHOUSE_WORLD_BOUNDS,
  invertY = WAREHOUSE_INVERT_Y,
  className,
}: {
  vehicles: DashboardVehicle[]
  selectedVehicleId?: string | null
  onSelectVehicle?: (vehicleId: string) => void
  /** STOMP 연결 상태. 주면 헤더에 배지로 표시한다. */
  realtimeStatus?: RealtimeConnectionStatus
  /** 차량이 0대일 때 보여 줄 재조회 동작. 없으면 버튼을 숨긴다. */
  onRefresh?: () => void
  bounds?: WorldBounds
  invertY?: boolean
  className?: string
}) {
  const placedVehicles = toPlacedVehicles(vehicles)
  const liveCount = placedVehicles.filter((placed) => !placed.pending).length
  const badge = realtimeStatus ? REALTIME_BADGE[realtimeStatus] : null

  return (
    <section
      className={cn(
        // 단독/세로 배치에서는 읽을 수 있는 최소 높이를 확보한다. 데스크톱 통합 패널에서는
        // 호출부의 min-h-0/flex 비율이 이 값을 덮어써 남은 viewport 높이에 맞춰 줄어든다.
        "flex h-full min-h-[280px] flex-col overflow-hidden rounded-lg border border-slate-700 bg-[#0b1220] md:min-h-[360px] lg:min-h-[420px]",
        className,
      )}
      aria-label="창고 미니맵"
    >
      <header className="flex shrink-0 items-center justify-between gap-2 border-b border-slate-800 px-3 py-1.5">
        <span className="text-xs font-semibold text-slate-100">미니맵</span>
        <div className="flex items-center gap-1.5">
          {/* 위치 수신은 정보 배지다 — 0/N 이어도 오류처럼 붉게 표시하지 않는다. */}
          <span
            className="inline-flex items-center gap-1 rounded border border-slate-600/60 bg-slate-800/60 px-1.5 py-0.5 text-[10px] font-medium text-slate-300"
            title="실시간 위치를 받은 차량 수 / 전체 차량 수"
          >
            위치 수신
            <span className="font-mono text-slate-100">
              {liveCount}/{vehicles.length}
            </span>
          </span>
          {badge ? (
            <span
              className={cn(
                "inline-flex items-center gap-1 rounded border px-1.5 py-0.5 text-[10px] font-medium",
                badge.className,
              )}
              aria-live="polite"
            >
              <span className="size-1.5 rounded-full bg-current opacity-80" aria-hidden="true" />
              {badge.label}
            </span>
          ) : null}
        </div>
      </header>

      {/* 통합 패널의 지도 본문. */}
      <div className="min-h-0 flex-1 overflow-hidden p-1.5">
        {/* 지도 자체는 전체 너비 영역 안에서 비율을 지키며 커진다(가로로 늘리지 않는다). */}
        <div className="relative h-full min-h-0 overflow-hidden">
        {/* 지도 스테이지: 지도 영역 전체를 덮고 그 안에서 지도를 가운데 정렬한다.
            absolute 로 깔아야 부모 높이 계산에 지도가 영향을 주지 않아, 지도가 스스로를 작게 만드는
            순환(높이 → 비율 → 높이)이 생기지 않는다. */}
        <div className="absolute inset-0 flex items-center justify-center p-1">
          {/* 배경과 마커가 공유하는 단 하나의 기준 박스.
              h-full + aspectRatio 로 세로를 가득 쓰고, max-w-full 이 가로를 넘칠 때만 줄인다(= contain).
              고정 px 크기를 쓰지 않으므로 카드가 커지면 지도도 그대로 커진다. */}
          <div
            className="relative h-full max-h-full max-w-full"
            style={{
              aspectRatio: `${WAREHOUSE_MAP_IMAGE_SIZE.width} / ${WAREHOUSE_MAP_IMAGE_SIZE.height}`,
            }}
          >
            <WarehouseMapSvg className="absolute inset-0 size-full rounded-sm" />

            {placedVehicles.map((placed) => {
              const pos = worldToPercent(placed.x, placed.y, { bounds, invertY })
              return (
                <MiniMapVehicleMarker
                  key={placed.vehicle.vehicleId}
                  vehicle={placed.vehicle}
                  left={pos.left}
                  top={pos.top}
                  heading={placed.heading}
                  pending={placed.pending}
                  selected={placed.vehicle.vehicleId === selectedVehicleId}
                  onSelect={onSelectVehicle}
                />
              )
            })}

            {/* 차량이 0대여도 지도는 남기고, 안내만 위에 얹는다. */}
            {vehicles.length === 0 ? (
              <div className="absolute inset-x-0 bottom-3 flex flex-col items-center gap-2 px-3">
                <p className="rounded bg-slate-900/85 px-2 py-1 text-center text-[10px] text-slate-300 ring-1 ring-slate-700">
                  등록된 활성 차량이 없습니다
                </p>
                {onRefresh ? (
                  <button
                    type="button"
                    onClick={onRefresh}
                    className="inline-flex items-center gap-1 rounded-md border border-slate-600 bg-slate-800/90 px-2 py-1 text-[10px] font-medium text-slate-100 transition-colors hover:bg-slate-700 focus-visible:ring-2 focus-visible:ring-sky-400 focus-visible:outline-none"
                  >
                    <RefreshCw className="size-3" aria-hidden="true" />
                    새로고침
                  </button>
                ) : null}
              </div>
            ) : null}
          </div>
        </div>

        {/* 개발 모드 전용 진단 정보. 운영 번들에서는 이 분기 자체가 제거된다.
            지도 열 안의 남는 여백(지도는 비율 고정이라 열보다 좁을 수 있다)에 둔다 —
            지도를 가리면 정작 확인하려던 마커 위치가 안 보인다. */}
        {process.env.NODE_ENV === "development" ? (
          <div className="pointer-events-none absolute right-1 top-1 max-w-[38%] overflow-hidden rounded bg-slate-950/85 px-1.5 py-1 font-mono text-[8px] leading-tight text-slate-400 ring-1 ring-slate-700/60">
            <div>
              x[{bounds.minX},{bounds.maxX}] y[{bounds.minY},{bounds.maxY}] invY={String(invertY)}
            </div>
            <div>
              live={liveCount}/{vehicles.length} ws={realtimeStatus ?? "-"}
            </div>
            {placedVehicles.map((placed) => {
              const pos = worldToPercent(placed.x, placed.y, { bounds, invertY })
              return (
                <div key={`dbg-${placed.vehicle.vehicleId}`}>
                  {placed.vehicle.vehicleId} ({placed.x.toFixed(1)},{placed.y.toFixed(1)}) h=
                  {placed.heading == null ? "-" : placed.heading.toFixed(0)} L{pos.left.toFixed(1)} T
                  {pos.top.toFixed(1)}
                  {placed.pending ? " init" : ""}
                </div>
              )
            })}
          </div>
        ) : null}
        </div>

      </div>
    </section>
  )
}

export default MiniMap
