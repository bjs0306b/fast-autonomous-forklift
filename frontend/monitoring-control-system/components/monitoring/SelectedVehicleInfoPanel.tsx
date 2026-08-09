"use client"

import { cn } from "@/lib/utils"
import type { DashboardVehicle } from "@/types/monitoring"
import { resolveVehicleSource } from "@/lib/monitoring/vehicleSource"
import { VEHICLE_STATUS_COLOR, VEHICLE_STATUS_LABEL } from "./vehicle-status"

/**
 * SelectedVehicleInfoPanel
 *
 * 차량 상세 상단에 들어가는 선택 차량 핵심 요약.
 *
 * 표시 순서는 <b>차량 ID → 차량 상태 → 물건 높이 → 포크 높이 → 적재 여부</b>로 고정한다.
 * 화물 ID 는 적재 여부 아래 보조 정보다.
 *
 * `VehicleDetailPanel` 안에서 정밀 좌표·작업·제어 영역보다 먼저 렌더링한다.
 *
 * 값은 전부 props 의 vehicle 에서만 읽는다. 상태/위치 이벤트가 상위 상태를 갱신하면 자동으로
 * 다시 그려지고, 위치만 바뀐 경우 선택은 그대로 유지된다.
 */
export function SelectedVehicleInfoPanel({
  vehicle,
  className,
}: {
  vehicle: DashboardVehicle | null
  className?: string
}) {
  return (
    <section className={cn("min-h-0", className)} aria-label="선택 차량 정보">
      {vehicle ? <SelectedVehicleFields vehicle={vehicle} /> : <EmptySelection />}
    </section>
  )
}

/**
 * 차량을 아직 클릭하지 않은 상태.
 *
 * 값 자리를 비워 두지 않고 <b>모두 `-`</b> 로 채운다. 빈 칸은 "값을 못 받았다"처럼 보이지만
 * `-` 는 "아직 고르지 않았다"를 뜻한다 — 관제 화면에서 이 둘을 구분하지 못하면 장비 이상을
 * 의심하게 된다.
 *
 * source 배지는 <b>그리지 않는다</b>. 선택된 차량이 없으므로 SIM/REAL 은 물론 "구분 없음"도
 * 표시할 대상이 없다.
 */
function EmptySelection() {
  return (
    <dl className="grid grid-cols-1 gap-1.5 rounded-md bg-white/5 p-1.5 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5">
      <InfoField label="차량 ID">
        <span className="font-mono text-xs font-semibold text-slate-500">-</span>
        <span className="mt-0.5 block text-[10px] text-slate-500">-</span>
      </InfoField>
      <InfoField label="차량 상태">
        <span className="text-xs text-slate-500">-</span>
      </InfoField>
      <InfoField label="물건 높이">
        <span className="text-xs text-slate-500">-</span>
      </InfoField>
      <InfoField label="포크 높이 (M)">
        <span className="text-xs text-slate-500">-</span>
      </InfoField>
      <InfoField label="적재 여부">
        <span className="text-xs text-slate-500">-</span>
      </InfoField>
      <dd className="text-xs text-slate-400 sm:col-span-2 lg:col-span-3 xl:col-span-5">
        미니맵에서 차량을 선택해 주세요.
      </dd>
    </dl>
  )
}

function SelectedVehicleFields({ vehicle }: { vehicle: DashboardVehicle }) {
  const color = VEHICLE_STATUS_COLOR[vehicle.status] ?? VEHICLE_STATUS_COLOR.UNKNOWN
  const statusLabel = VEHICLE_STATUS_LABEL[vehicle.status] ?? "확인 불가"
  const source = resolveVehicleSource(vehicle.vehicleId)
  const displayedCargoHeight = vehicle.cargoHeight ?? vehicle.reportedCargoHeight
  const cargoHeightSource = vehicle.cargoHeight != null ? "AI 측정" : "Isaac 전체 높이"

  return (
    <dl className="grid grid-cols-1 gap-1.5 rounded-md bg-white/5 p-1.5 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5">
      {/* 1. 차량 ID */}
      <InfoField label="차량 ID">
        <div className="flex min-w-0 items-center gap-1.5">
          {/* 말줄임표는 유지하되 hover 로 전체 ID 를 읽을 수 있게 한다 — 차량 ID 는 정지 명령이
              어느 차량으로 나가는지 확인하는 값이라 "보이는 만큼"으로 판단하면 안 된다. */}
          <span
            className="truncate font-mono text-xs font-semibold text-white"
            title={vehicle.vehicleId}
          >
            {vehicle.vehicleId}
          </span>
          {source !== "unknown" ? (
            <span className="rounded bg-white/5 px-1 py-px text-[9px] font-semibold tracking-wide text-slate-300 uppercase">
              {source === "real" ? "REAL" : "SIM"}
            </span>
          ) : null}
          {!vehicle.active ? (
            <span className="rounded bg-slate-500/15 px-1 py-px text-[9px] font-semibold text-slate-300">
              비활성
            </span>
          ) : null}
        </div>
        <span className="mt-0.5 block truncate text-[10px] text-slate-400">{vehicle.name}</span>
      </InfoField>

      {/* 2. 차량 상태 */}
      <InfoField label="차량 상태">
        <span
          className="inline-flex items-center gap-1.5 rounded px-1.5 py-0.5 text-xs font-medium"
          style={{ backgroundColor: `rgba(${color.glow},0.15)`, color: color.border }}
          data-testid="selected-vehicle-status"
          data-status={vehicle.status}
        >
          <span
            className="size-1.5 rounded-full"
            style={{ backgroundColor: color.base }}
            aria-hidden="true"
          />
          {statusLabel}
        </span>
        {vehicle.battery != null ? (
          <span className="mt-1 block font-mono text-[10px] text-emerald-300">
            배터리 {vehicle.battery.toFixed(0)}%
          </span>
        ) : null}
      </InfoField>

      {/* 3. 물건 높이 — AI 실측값을 우선하고, 없으면 Isaac cargo.h(팔레트 포함)를 표시한다. */}
      <InfoField label="물건 높이">
        {displayedCargoHeight != null ? (
          <>
            <span className="font-mono text-base leading-none font-semibold text-slate-100">
              {displayedCargoHeight.toFixed(2)}
              <span className="ml-1 text-[10px] font-normal text-slate-400">m</span>
            </span>
            <span className="mt-1 block text-[9px] text-slate-500">{cargoHeightSource}</span>
          </>
        ) : (
          <span className="text-xs text-slate-400">측정 정보 없음</span>
        )}
      </InfoField>

      {/* 4. 적재 여부 — null 은 "미적재"가 아니라 "확인 불가"다. */}
      <InfoField label="적재 여부">
        <CargoBadge hasCargo={vehicle.hasCargo} />
        {vehicle.hasCargo !== false && (vehicle.reportedCargoId || vehicle.cargoId) ? (
          <span className="mt-1 block truncate font-mono text-[10px] text-slate-400">
            {vehicle.reportedCargoId ?? vehicle.cargoId}
          </span>
        ) : null}
      </InfoField>
    </dl>
  )
}

function CargoBadge({ hasCargo }: { hasCargo: boolean | null }) {
  const { label, className } =
    hasCargo === true
      ? { label: "적재 중", className: "bg-amber-500/15 text-amber-300" }
      : hasCargo === false
        ? { label: "미적재", className: "bg-white/5 text-slate-300" }
        : { label: "확인 불가", className: "bg-white/5 text-slate-400" }

  return (
    <span
      className={cn("inline-flex items-center rounded px-1.5 py-0.5 text-xs font-medium", className)}
      data-testid="selected-vehicle-cargo"
    >
      {label}
    </span>
  )
}

function InfoField({
  label,
  children,
  empty = false,
}: {
  label: string
  children?: React.ReactNode
  empty?: boolean
}) {
  return (
    <div className="min-w-0 px-1.5 py-1">
      <dt className="text-[10px] tracking-wide text-slate-400 uppercase">{label}</dt>
      <dd className={cn("mt-0.5 min-h-5", empty && "text-slate-600")}>{children}</dd>
    </div>
  )
}

export default SelectedVehicleInfoPanel
