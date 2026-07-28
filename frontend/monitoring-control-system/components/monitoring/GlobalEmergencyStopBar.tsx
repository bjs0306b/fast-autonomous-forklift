"use client"

import { OctagonAlert } from "lucide-react"
import { cn } from "@/lib/utils"

/**
 * GlobalEmergencyStopBar
 *
 * 활성 차량 전체에 대한 비상정지 제어 바.
 *
 * NOTE(FR-503): 실제 API(`POST /api/vehicles/commands/emergency-stop-all`)에 연결돼 있다.
 * 확인 다이얼로그와 요청 상태 관리는 상위(MonitoringPage)가 담당하고,
 * 이 컴포넌트는 활성 차량 수 표시와 버튼 상태만 책임진다.
 */
export function GlobalEmergencyStopBar({
  onTriggerAll,
  activeVehicleCount = 0,
  pending = false,
  className,
}: {
  onTriggerAll?: () => void
  /** 명령 대상이 되는 활성 차량 수(0대면 버튼 비활성) */
  activeVehicleCount?: number
  /** 전체 비상정지 요청 진행 중 여부(중복 클릭 방지) */
  pending?: boolean
  className?: string
}) {
  const disabled = pending || activeVehicleCount === 0

  return (
    <div
      className={cn(
        "flex items-center justify-between gap-3 rounded-lg border border-red-500/30 bg-red-950/40 px-3 py-2",
        className,
      )}
      role="region"
      aria-label="전체 비상정지"
    >
      <div className="flex items-center gap-2 text-red-200">
        <OctagonAlert className="size-4 shrink-0" aria-hidden="true" />
        <span className="text-xs font-medium text-balance">
          비상 시 전체 차량을 즉시 정지시킵니다.
        </span>
        <span
          className="rounded bg-red-500/15 px-1.5 py-0.5 font-mono text-[10px] text-red-200"
          data-testid="global-estop-target-count"
        >
          대상 {activeVehicleCount}대
        </span>
      </div>
      <button
        type="button"
        onClick={onTriggerAll}
        disabled={disabled}
        aria-busy={pending}
        data-testid="global-estop-button"
        className={cn(
          "inline-flex shrink-0 items-center gap-2 rounded-md px-4 py-2 text-sm font-bold tracking-wide text-white uppercase shadow-sm transition-colors focus-visible:ring-2 focus-visible:ring-red-300 focus-visible:outline-none",
          disabled ? "cursor-not-allowed bg-red-900/60 text-red-200/70" : "bg-red-600 hover:bg-red-500",
        )}
        title={activeVehicleCount === 0 ? "대상 활성 차량이 없습니다" : undefined}
      >
        <OctagonAlert className="size-4" aria-hidden="true" />
        {pending ? "전송 중..." : "전체 정지"}
      </button>
    </div>
  )
}

export default GlobalEmergencyStopBar
