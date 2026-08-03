"use client"

import { Ban, Loader2, OctagonAlert } from "lucide-react"
import { cn } from "@/lib/utils"
import { formatClockTime, formatNumber } from "@/lib/format"
import type { DashboardVehicle } from "@/types/monitoring"
import { SelectedVehicleInfoPanel } from "./SelectedVehicleInfoPanel"

/**
 * VehicleDetailPanel
 *
 * 미니맵에서 선택한 차량의 상세 정보를 표시하고,
 * 핵심 상태, 정밀 좌표, 작업 정보와 일반 STOP/개별 EMERGENCY STOP 제어를 제공한다.
 *
 * 표시 값은 전부 props 로 내려온 selectedVehicle 에서만 읽는다 —
 * 상태/위치 WebSocket 이벤트가 도착하면 상위 상태가 갱신되어 이 패널도 자동으로 다시 그려진다.
 *
 * NOTE(FR-503): EMERGENCY STOP 버튼은 실제 API 에 연결돼 있다.
 *
 * 일반 STOP과 EMERGENCY STOP은 기능이 다른 기존 API를 그대로 사용하며 위험 제어 영역에 모은다.
 */
export function VehicleDetailPanel({
  vehicle,
  onStop,
  onEmergencyStop,
  stopPending = false,
  emergencyStopPending = false,
  className,
}: {
  vehicle: DashboardVehicle | null
  onStop?: (vehicleId: string) => void
  onEmergencyStop?: (vehicleId: string) => void
  /** 이 차량의 일반 정지 요청이 진행 중인지(중복 클릭 방지) */
  stopPending?: boolean
  /** 이 차량의 비상정지 요청이 진행 중인지(중복 클릭 방지) */
  emergencyStopPending?: boolean
  className?: string
}) {
  return (
    <section
      className={cn(
        "flex h-full min-h-0 flex-col overflow-hidden rounded-lg border border-slate-700 bg-[#0b1220]",
        className,
      )}
      aria-label="선택 차량 상세 정보"
    >
      <header className="flex shrink-0 items-center justify-between border-b border-slate-800 px-3 py-1.5">
        <span className="text-xs font-semibold text-slate-100">차량 상세</span>
      </header>

      {vehicle ? (
        <VehicleDetailContent
          vehicle={vehicle}
          onStop={onStop}
          onEmergencyStop={onEmergencyStop}
          stopPending={stopPending}
          emergencyStopPending={emergencyStopPending}
        />
      ) : (
        <div className="flex flex-1 items-center justify-center p-4 text-center">
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
  stopPending,
  emergencyStopPending,
}: {
  vehicle: DashboardVehicle
  onStop?: (vehicleId: string) => void
  onEmergencyStop?: (vehicleId: string) => void
  stopPending: boolean
  emergencyStopPending: boolean
}) {
  const loc = vehicle.location
  const task = vehicle.currentTask
  const positionLabel =
    loc?.x != null && loc?.y != null
      ? `X ${formatNumber(loc.x, 2)} · Y ${formatNumber(loc.y, 2)}`
      : "-"

  // 이미 ESTOP 상태면 재발행을 막는다. 이 판단은 오직 차량이 보고한 status 로만 한다 —
  // 명령 발행 성공(PUBLISHED)으로는 절대 ESTOP 으로 간주하지 않는다.
  const alreadyEstopped = vehicle.status === "ESTOP"
  const stopDisabled = stopPending || emergencyStopPending || !onStop
  const estopDisabled = alreadyEstopped || emergencyStopPending || stopPending || !onEmergencyStop
  const estopLabel = emergencyStopPending
    ? "명령 전송 중..."
    : alreadyEstopped
      ? "비상정지 상태"
      : `${vehicle.vehicleId} 비상정지`

  return (
    <div className="flex min-h-0 flex-1 flex-col p-2">
      {/* 상세 정보만 내부 스크롤을 허용한다. 제어 버튼은 항상 패널 하단에 남는다. */}
      <div className="min-h-0 flex-1 space-y-2 overflow-y-auto overscroll-contain pr-0.5">
        {/* ID · 상태 · 화물 높이 · 적재 여부를 반응형 핵심 그리드로 한 번만 표시한다. */}
        <SelectedVehicleInfoPanel vehicle={vehicle} />

        {/* X/Y는 0을 포함한 두 값이 모두 있을 때만 하나의 위치 칸에 표시한다. */}
        <div>
          <dl className="grid grid-cols-1 gap-1.5 text-xs sm:grid-cols-2 xl:grid-cols-4">
            <DetailField label="위치 (m)" value={positionLabel} mono />
            <DetailField
              label="Heading (°)"
              value={loc?.heading != null ? formatNumber(loc.heading, 1) : "—"}
              mono
            />
            <DetailField
              label="Speed (m/s)"
              value={loc?.speed != null ? formatNumber(loc.speed, 2) : "—"}
              mono
            />
            <DetailField
              label="최근 수신 시간"
              value={formatClockTime(loc?.receivedAt ?? null)}
              mono
            />
          </dl>
        </div>

        {/* 현재 작업 */}
        <div>
          <div className="mb-1 text-[10px] font-medium tracking-wide text-slate-400 uppercase">
            현재 작업
          </div>
          {task ? (
            <dl className="grid grid-cols-1 gap-1.5 text-xs sm:grid-cols-2">
              <DetailField label="Task" value={task.taskId} mono />
              <DetailField label="작업 상태" value={task.status} />
            </dl>
          ) : (
            <p className="rounded-md bg-white/5 px-2 py-1.5 text-xs text-slate-400">
              진행 중인 작업이 없습니다.
            </p>
          )}
        </div>
      </div>

      {/* 제어 버튼은 스크롤 영역 밖의 고정 행이다. */}
      <div className="mt-2 grid shrink-0 grid-cols-1 gap-1.5 border-t border-slate-800 pt-2 sm:grid-cols-2">
        <button
          type="button"
          onClick={() => onStop?.(vehicle.vehicleId)}
          disabled={stopDisabled}
          aria-label={`${vehicle.vehicleId} 차량 일반 정지`}
          aria-busy={stopPending}
          data-testid="selected-vehicle-stop-button"
          className={cn(
            "inline-flex items-center justify-center gap-1.5 rounded-md border px-3 py-1.5 text-xs font-semibold transition-colors focus-visible:ring-2 focus-visible:ring-amber-300 focus-visible:outline-none",
            stopDisabled
              ? "cursor-not-allowed border-slate-700 bg-slate-800/50 text-slate-500"
              : "border-amber-500/50 bg-amber-950/40 text-amber-100 hover:bg-amber-900/50",
          )}
        >
          {stopPending ? (
            <Loader2 className="size-4 animate-spin" aria-hidden="true" />
          ) : (
            <Ban className="size-4" aria-hidden="true" />
          )}
          {stopPending ? "정지 명령 전송 중" : "일반 정지"}
        </button>
        <button
          type="button"
          onClick={() => onEmergencyStop?.(vehicle.vehicleId)}
          disabled={estopDisabled}
          aria-busy={emergencyStopPending}
          data-testid="vehicle-estop-button"
          className={cn(
            "inline-flex items-center justify-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-semibold text-white transition-colors focus-visible:ring-2 focus-visible:ring-red-300 focus-visible:outline-none",
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
    <div className={cn("rounded-md bg-white/5 px-2 py-1", className)}>
      <dt className="text-[10px] tracking-wide text-slate-400 uppercase">{label}</dt>
      <dd className={cn("truncate text-slate-100", mono && "font-mono")}>{value}</dd>
    </div>
  )
}

export default VehicleDetailPanel
