"use client"

import { RefreshCw } from "lucide-react"
import { cn } from "@/lib/utils"
import { worldToPercent, type WorldBounds } from "@/lib/coordinate"
import {
  INITIAL_VEHICLE_POSES,
  WAREHOUSE_INVERT_Y,
  WAREHOUSE_MAP_HORIZONTAL_DISPLAY_SCALE,
  WAREHOUSE_MAP_IMAGE_SIZE,
  WAREHOUSE_WORLD_BOUNDS,
} from "@/lib/config/warehouseMap"
import type { DashboardVehicle, RealtimeConnectionStatus } from "@/types/monitoring"
import type { VehiclePathSnapshot, VehiclePathWaypoint } from "@/types/websocket"
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
 * - **배경과 마커가 같은 박스를 공유한다.** 표시 박스는 원본보다 가로로 15% 넓지만 배경 SVG와
 *   마커 모두 같은 퍼센트 기준을 사용하므로, 패널 크기나 표시 비율이 변해도 서로 어긋나지 않는다.
 * - **차량이 0대여도 배경은 항상 그린다.** 지도가 사라지면 "이미지 문제인가?"로 오해하기 쉽다.
 * - 마커 클릭 시 팝업 없이 onSelectVehicle(vehicleId) 만 호출한다(기존 동작 유지).
 * - **지도 빈 영역을 클릭하면 선택을 해제한다**(onClearSelection). 지도 본문 전체가 해제 영역이고,
 *   그 위에 얹힌 마커·새로고침 버튼은 각자 클릭 전파를 막아 예외가 된다. 헤더(제목·배지)는 해제
 *   영역 밖이라 배지를 눌러도 선택이 풀리지 않는다.
 * - 통합 관제 패널의 상단 지도 영역으로 사용된다. 지도 기준 박스의 비율과 좌표 변환은 그대로
 *   유지하므로 패널이 넓어져도 지도가 왜곡되지 않는다.
 */
export function MiniMap({
  vehicles,
  selectedVehicleId,
  onSelectVehicle,
  onClearSelection,
  realtimeStatus,
  onRefresh,
  path,
  visitedPath = [],
  bounds = WAREHOUSE_WORLD_BOUNDS,
  invertY = WAREHOUSE_INVERT_Y,
  className,
}: {
  vehicles: DashboardVehicle[]
  selectedVehicleId?: string | null
  onSelectVehicle?: (vehicleId: string) => void
  /** 지도 빈 영역 클릭. 선택 해제용이며, 주지 않으면 배경 클릭이 아무 일도 하지 않는다. */
  onClearSelection?: () => void
  /** STOMP 연결 상태. 주면 헤더에 배지로 표시한다. */
  realtimeStatus?: RealtimeConnectionStatus
  /** 차량이 0대일 때 보여 줄 재조회 동작. 없으면 버튼을 숨긴다. */
  onRefresh?: () => void
  /** 실시간 경로 토픽에서 받은 선택 차량의 현재 계획 경로. */
  path?: VehiclePathSnapshot | null
  /** 현재 페이지가 위치 이벤트로 누적한 최근 이동 궤적(최대 개수는 상위에서 제한). */
  visitedPath?: VehiclePathWaypoint[]
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
        // 높이는 사용 위치가 소유한다. 자체 최소 높이를 두면 부모가 줄어들어도 지도가 버티면서
        // 상세 패널과 하단 제어 버튼을 밀어낼 수 있으므로, 여기서는 부모 높이에 정확히 맞춘다.
        "flex h-full min-h-0 min-w-0 flex-col overflow-hidden rounded-lg border border-slate-700 bg-[#0b1220]",
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
        {/* 지도와 마커가 공유하는 표시 박스를 함께 넓힌다. */}
        <div className="relative h-full min-h-0 overflow-hidden">
        {/* 지도 스테이지: 지도 영역 전체를 덮고 그 안에서 지도를 가운데 정렬한다.
            absolute 로 깔아야 부모 높이 계산에 지도가 영향을 주지 않아, 지도가 스스로를 작게 만드는
            순환(높이 → 비율 → 높이)이 생기지 않는다.

            선택 해제 영역이기도 하다. 지도 배경 SVG(랙·바닥·경로)와 지도 좌우 여백이 모두 이
            div 안에 있으므로, 그 어디를 눌러도 버블링으로 여기까지 올라와 해제된다. 반대로
            해제되면 안 되는 요소(차량 마커, 새로고침 버튼)는 자기 쪽에서 전파를 끊는다 —
            여기서 target === currentTarget 으로 걸러 내면 배경 SVG 클릭이 해제되지 않는다.

            role="presentation": 이 div 는 컨트롤이 아니라 "빈 곳"이다. 키보드 대체 동작은 두지
            않았다(포인터 전용). 키보드 사용자는 마커에서 Tab/Enter 로 선택을 바꾸면 된다. */}
        <div
          className="absolute inset-0 flex items-center justify-center p-1"
          role="presentation"
          onClick={onClearSelection}
        >
          {/* 배경과 마커가 공유하는 단 하나의 기준 박스.
              원본 400:600 비율의 가로만 15% 넓혀 좌우 빈 공간을 줄인다. SVG와 마커가 모두 이
              박스를 기준으로 렌더링되므로 시각 비율을 바꿔도 위치 정렬은 유지된다. */}
          <div
            className="relative h-full max-h-full max-w-full"
            style={{
              aspectRatio: `${WAREHOUSE_MAP_IMAGE_SIZE.width * WAREHOUSE_MAP_HORIZONTAL_DISPLAY_SCALE} / ${WAREHOUSE_MAP_IMAGE_SIZE.height}`,
            }}
          >
            <WarehouseMapSvg className="absolute inset-0 size-full rounded-sm" />

            <RouteOverlay path={path} visitedPath={visitedPath} bounds={bounds} invertY={invertY} />

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
                    // 컨트롤 버튼이므로 배경 클릭(선택 해제)으로 번지지 않게 막는다.
                    onClick={(event) => {
                      event.stopPropagation()
                      onRefresh()
                    }}
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

function RouteOverlay({
  path,
  visitedPath,
  bounds,
  invertY,
}: {
  path?: VehiclePathSnapshot | null
  visitedPath: VehiclePathWaypoint[]
  bounds: WorldBounds
  invertY: boolean
}) {
  const toPoint = (point: VehiclePathWaypoint) => {
    const percent = worldToPercent(point.x, point.y, { bounds, invertY })
    return `${percent.left},${percent.top}`
  }
  const planned = path?.waypoints ?? []
  if (planned.length === 0 && visitedPath.length < 2 && !path?.goal) return null

  return (
    <svg
      className="pointer-events-none absolute inset-0 z-[1] size-full overflow-visible"
      viewBox="0 0 100 100"
      preserveAspectRatio="none"
      aria-hidden="true"
    >
      {visitedPath.length >= 2 ? (
        <polyline
          points={visitedPath.map(toPoint).join(" ")}
          fill="none"
          stroke="#38bdf8"
          strokeWidth="0.7"
          strokeDasharray="1.5 1"
          vectorEffect="non-scaling-stroke"
          opacity="0.65"
        />
      ) : null}
      {planned.length >= 2 ? (
        <polyline
          points={planned.map(toPoint).join(" ")}
          fill="none"
          stroke="#a78bfa"
          strokeWidth="1"
          vectorEffect="non-scaling-stroke"
          opacity="0.9"
        />
      ) : null}
      {planned[0] ? (
        <circle
          cx={worldToPercent(planned[0].x, planned[0].y, { bounds, invertY }).left}
          cy={worldToPercent(planned[0].x, planned[0].y, { bounds, invertY }).top}
          r="1.2"
          fill="#22c55e"
          vectorEffect="non-scaling-stroke"
        />
      ) : null}
      {path?.goal ? (
        <circle
          cx={worldToPercent(path.goal.x, path.goal.y, { bounds, invertY }).left}
          cy={worldToPercent(path.goal.x, path.goal.y, { bounds, invertY }).top}
          r="1.7"
          fill="#f59e0b"
          stroke="#fef3c7"
          strokeWidth="0.5"
          vectorEffect="non-scaling-stroke"
        />
      ) : null}
    </svg>
  )
}

export default MiniMap
