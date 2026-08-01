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
 * NOTE(FR-503): EMERGENCY STOP 버튼은 실제 API 에 연결돼 있다.
 *
 * NOTE(STOP): 일반 STOP 은 백엔드 API(`POST /api/vehicles/{id}/commands/stop`)가 존재하지만
 * 프론트 연결 계약이 확정되지 않아 <b>비활성</b>으로 둔다. 이전에는 활성 버튼이 console.log 만
 * 실행해 "눌렀으니 정지했다"는 오해를 줄 수 있었다. onStop prop 자체를 두지 않는 이유는,
 * 호출되지 않는 prop 이 남아 있으면 다음 사람이 "연결돼 있다"고 오해하기 때문이다.
 * STOP 을 EMERGENCY STOP API 로 대체 연결하지 않는다 — 통상 정지와 비상 정지는 별개 명령이다.
 */
export function VehicleDetailPanel({
  vehicle,
  onEmergencyStop,
  emergencyStopPending = false,
  className,
}: {
  vehicle: DashboardVehicle | null
  onEmergencyStop?: (vehicleId: string) => void
  /** 이 차량의 비상정지 요청이 진행 중인지(중복 클릭 방지) */
  emergencyStopPending?: boolean
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
          onEmergencyStop={onEmergencyStop}
          emergencyStopPending={emergencyStopPending}
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
  onEmergencyStop,
  emergencyStopPending,
}: {
  vehicle: DashboardVehicle
  onEmergencyStop?: (vehicleId: string) => void
  emergencyStopPending: boolean
}) {
  const color = VEHICLE_STATUS_COLOR[vehicle.status] ?? VEHICLE_STATUS_COLOR.UNKNOWN
  const statusLabel = VEHICLE_STATUS_LABEL[vehicle.status] ?? vehicle.status
  const loc = vehicle.location
  const task = vehicle.currentTask

  // 이미 ESTOP 상태면 재발행을 막는다. 이 판단은 오직 차량이 보고한 status 로만 한다 —
  // 명령 발행 성공(PUBLISHED)으로는 절대 ESTOP 으로 간주하지 않는다.
  const alreadyEstopped = vehicle.status === "ESTOP"
  const estopDisabled = alreadyEstopped || emergencyStopPending
  const estopLabel = emergencyStopPending
    ? "명령 전송 중..."
    : alreadyEstopped
      ? "비상정지 상태"
      : `${vehicle.vehicleId} 비상정지`

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto p-3">
      {/* 차량명 */}
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="truncate text-sm font-semibold text-white">{vehicle.name}</div>
          <div className="truncate font-mono text-[11px] text-slate-400">{vehicle.vehicleId}</div>
        </div>
        <div className="flex shrink-0 flex-col items-end gap-1">
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
            // 차량이 보고한 상태만 노출한다(명령 발행 결과와 분리). 검증에서 버튼 문구와 혼동하지 않도록 식별자를 둔다.
            data-testid="vehicle-status-label"
            data-status={vehicle.status}
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
              {loc.frameId ?? "—"}
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
          </dl>
        ) : (
          <p className="rounded-md bg-white/5 px-2.5 py-2 text-xs text-slate-400">
            진행 중인 작업이 없습니다.
          </p>
        )}
      </div>

      {/* 제어 버튼 */}
      <div className="mt-auto flex flex-col gap-2 pt-1">
        {/* 일반 STOP: 실제 API 가 없어 비활성이다.
            onClick 핸들러를 아예 두지 않는다 — disabled 만으로도 클릭·키보드 실행이 막히지만,
            "누르면 무언가 실행된다"는 오해를 코드 수준에서도 남기지 않기 위해서다. */}
        <button
          type="button"
          disabled
          aria-disabled="true"
          data-testid="vehicle-stop-button"
          className="inline-flex cursor-not-allowed items-center justify-center gap-2 rounded-md border border-slate-700 bg-slate-800/50 px-3 py-2 text-sm font-medium text-slate-400 focus-visible:ring-2 focus-visible:ring-sky-400 focus-visible:outline-none"
          title="일반 정지 기능은 아직 연결되지 않았습니다"
        >
          <Ban className="size-4" aria-hidden="true" />
          STOP 준비 중
        </button>
        <p className="-mt-1 text-[10px] leading-snug text-pretty text-slate-400">
          일반 정지 기능은 아직 연결되지 않았습니다. EMERGENCY STOP은 비상 상황에서만 사용하세요.
        </p>
        <button
          type="button"
          onClick={() => onEmergencyStop?.(vehicle.vehicleId)}
          disabled={estopDisabled}
          aria-busy={emergencyStopPending}
          data-testid="vehicle-estop-button"
          className={cn(
            "inline-flex items-center justify-center gap-2 rounded-md px-3 py-2 text-sm font-semibold text-white transition-colors focus-visible:ring-2 focus-visible:ring-red-300 focus-visible:outline-none",
            estopDisabled
              ? "cursor-not-allowed bg-red-900/60 text-red-200/70"
              : "bg-red-600 hover:bg-red-500",
          )}
          title={alreadyEstopped ? "이미 비상정지 상태입니다" : undefined}
        >
          <OctagonAlert className="size-4" aria-hidden="true" />
          {estopLabel}
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
