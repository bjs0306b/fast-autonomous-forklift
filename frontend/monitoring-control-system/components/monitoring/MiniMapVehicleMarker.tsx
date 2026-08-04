"use client"

import { cn } from "@/lib/utils"
import { headingToMarkerRotation } from "@/lib/config/warehouseMap"
import { formatNumber } from "@/lib/format"
import { toShortLabel } from "@/lib/vehicleStatus"
import type { DashboardVehicle } from "@/types/monitoring"
import { VEHICLE_STATUS_COLOR, VEHICLE_STATUS_LABEL } from "./vehicle-status"

/**
 * 차량이 실물인지 시뮬인지 구분한다.
 *
 * 백엔드에 `source` 같은 구분 필드가 없어(vehicle 테이블 2컬럼 + 상태) **vehicleId 접두어 관례**로만
 * 판별할 수 있다. 현재 관제 대상은 Isaac 의 `SIM-F01` 한 대이지만, 나중에 실물 차량이 다시
 * 붙을 수 있으므로 `REAL-` 접두어 판정은 남겨 둔다.
 * 접두어가 없는 식별자(FORKLIFT-01 등)는 어느 쪽인지 알 수 없으므로 "unknown" 으로 둔다.
 */
export type VehicleSource = "real" | "sim" | "unknown"

export function resolveVehicleSource(vehicleId: string): VehicleSource {
  const id = vehicleId.trim().toUpperCase()
  if (id.startsWith("REAL-")) return "real"
  if (id.startsWith("SIM-")) return "sim"
  return "unknown"
}

const SOURCE_LABEL: Record<VehicleSource, string> = {
  real: "실물",
  sim: "시뮬",
  unknown: "구분 없음",
}

/**
 * 미니맵 위의 차량 마커 하나.
 *
 * 시각 언어는 `wireframe-monitoring-final.md` §16.10 의 기존 설계를 따른다.
 *   모양 = 출처   실물은 채운 원, 시뮬은 점선 테두리 사각형
 *   색   = 상태   VEHICLE_STATUS_COLOR 10종(기존 토큰, 새 색을 만들지 않는다)
 *   내부 glow    = 선택됨
 * 위 세 축이 이미 점유돼 있어, 초기 위치 표시는 네 번째 축(투명도 + `초기` 배지)으로 구분한다.
 *
 * - 마커 루트에 `-translate-x-1/2 -translate-y-1/2` 를 적용해 **본체 중심이 정확히 차량 좌표**에 온다.
 * - 회전은 화살표 래퍼에만 건다 — ID 라벨까지 돌면 읽을 수 없다.
 * - 라벨은 지도 가장자리에서 잘리지 않도록 좌우 위치를 자동으로 뒤집는다.
 * - transition 은 200ms 로 짧게만 둔다. 길면 마커가 실제 좌표보다 뒤처져 잘못된 위치를 보여 준다.
 */
export function MiniMapVehicleMarker({
  vehicle,
  left,
  top,
  heading,
  pending = false,
  selected,
  onSelect,
}: {
  vehicle: DashboardVehicle
  /** 기준 박스 기준 가로 위치(%) */
  left: number
  /** 기준 박스 기준 세로 위치(%) */
  top: number
  /** 월드 heading(degree). null 이면 화살표를 그리지 않는다. */
  heading: number | null
  /** 실시간 위치 없이 초기 위치로 표시 중인지 */
  pending?: boolean
  selected: boolean
  onSelect?: (vehicleId: string) => void
}) {
  const color = VEHICLE_STATUS_COLOR[vehicle.status] ?? VEHICLE_STATUS_COLOR.UNKNOWN
  const statusLabel = VEHICLE_STATUS_LABEL[vehicle.status] ?? vehicle.status
  const shortLabel = toShortLabel(vehicle.vehicleId)
  const source = resolveVehicleSource(vehicle.vehicleId)
  const hasHeading = typeof heading === "number" && Number.isFinite(heading)
  const location = vehicle.location

  // 가장자리에서 라벨이 잘리지 않도록 붙는 방향을 바꾼다.
  const labelSide = left > 78 ? "right" : left < 22 ? "left" : "center"
  const labelPosition =
    labelSide === "right"
      ? "right-full mr-1 top-1/2 -translate-y-1/2"
      : labelSide === "left"
        ? "left-full ml-1 top-1/2 -translate-y-1/2"
        : "left-1/2 top-full mt-0.5 -translate-x-1/2"

  // hover 시 보여 줄 상세. title 속성이라 별도 팝업 레이어가 없어 미니맵 클릭 동작(선택)을 방해하지 않는다.
  // 배터리는 DashboardVehicle 에 없다(대시보드 응답의 차량 항목은 status/location/currentTask 만 담는다)
  // → 배터리는 VehicleDetailPanel 이 아니라 여기서도 표시할 수 없다. 보고서의 "미확인" 항목 참고.
  const tooltipText = [
    `${vehicle.vehicleId} (${SOURCE_LABEL[source]})`,
    `상태 ${statusLabel}`,
    location?.x != null && location?.y != null
      ? `좌표 ${formatNumber(location.x, 2)}, ${formatNumber(location.y, 2)} m`
      : pending
        ? "좌표 초기 위치(실시간 수신 전)"
        : "좌표 미수신",
    hasHeading ? `heading ${formatNumber(heading, 1)}°` : "heading 미수신",
    location?.speed != null ? `속도 ${formatNumber(location.speed, 2)} m/s` : null,
  ]
    .filter(Boolean)
    .join("\n")

  return (
    <button
      type="button"
      // 전파를 막지 않으면 미니맵 배경의 "선택 해제" 핸들러가 이어서 실행돼, 방금 고른 차량이
      // 같은 클릭으로 즉시 해제된다(선택 → 해제가 한 프레임에 일어나 아무 반응이 없어 보인다).
      onClick={(event) => {
        event.stopPropagation()
        onSelect?.(vehicle.vehicleId)
      }}
      title={tooltipText}
      className="group absolute z-10 -translate-x-1/2 -translate-y-1/2 transition-[left,top] duration-200 ease-linear focus:outline-none"
      style={{ left: `${left}%`, top: `${top}%` }}
      aria-label={`${shortLabel} · ${SOURCE_LABEL[source]} · ${statusLabel}${pending ? " · 초기 위치" : ""}${selected ? " (선택됨)" : ""}`}
      aria-pressed={selected}
      data-testid={`minimap-marker-${vehicle.vehicleId}`}
      data-source={source}
      data-pending={pending}
    >
      {/* 본체 + 화살표. 이 래퍼의 중심이 차량 좌표다. */}
      <span className="relative flex size-8 items-center justify-center">
        {selected ? (
          <span
            className="pointer-events-none absolute size-8 rounded-full motion-safe:animate-pulse"
            style={{ boxShadow: `0 0 0 2px rgba(${color.glow},0.85), 0 0 16px 4px rgba(${color.glow},0.45)` }}
            aria-hidden="true"
          />
        ) : null}

        {/* 방향 화살표: 회전 0에서 위를 향하고, headingToMarkerRotation() 이 월드 heading 을 화면 각도로 바꾼다. */}
        {hasHeading ? (
          <span
            className="pointer-events-none absolute inset-0 transition-transform duration-200 ease-linear"
            style={{ transform: `rotate(${headingToMarkerRotation(heading)}deg)` }}
            aria-hidden="true"
          >
            <span
              className="absolute left-1/2 top-0 size-0 -translate-x-1/2"
              style={{
                borderLeft: "4.5px solid transparent",
                borderRight: "4.5px solid transparent",
                borderBottom: `8px solid ${color.border}`,
              }}
            />
          </span>
        ) : null}

        {/* 차량 본체 — 실물은 원, 시뮬은 점선 사각형(기존 설계 문서의 구분) */}
        <span
          className={cn(
            "relative block transition-opacity",
            source === "sim" ? "size-3.5 rounded-[2px] border-2 border-dashed" : "size-3.5 rounded-full border",
            pending ? "opacity-60" : "opacity-100",
          )}
          style={{
            backgroundColor: source === "sim" ? "transparent" : color.base,
            borderColor: color.border,
          }}
          aria-hidden="true"
        />
      </span>

      {/* ID 라벨 — 회전하지 않으며 가장자리에서는 옆으로 붙는다. */}
      <span
        className={cn(
          "pointer-events-none absolute whitespace-nowrap rounded px-1 py-px font-mono text-[9px] font-semibold leading-none shadow-sm ring-1 ring-black/40",
          labelPosition,
          selected ? "ring-white/70" : "",
        )}
        style={{ backgroundColor: "rgba(15,23,42,0.92)", color: color.border }}
      >
        {shortLabel}
        {source === "sim" ? <span className="ml-0.5 text-slate-400">SIM</span> : null}
        {pending ? <span className="ml-0.5 text-slate-500">초기</span> : null}
      </span>
    </button>
  )
}

export default MiniMapVehicleMarker
