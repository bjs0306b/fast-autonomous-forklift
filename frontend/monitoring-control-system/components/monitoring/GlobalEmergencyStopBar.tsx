"use client"

import { OctagonAlert } from "lucide-react"
import { cn } from "@/lib/utils"

/**
 * GlobalEmergencyStopBar
 *
 * 전체 차량에 대한 비상정지 제어 바. 관제 화면 최상단에 고정 배치한다.
 * 실제 명령 전송은 연동 단계에서 구현한다. (지금은 onTriggerAll 콜백만 노출)
 */
export function GlobalEmergencyStopBar({
  onTriggerAll,
  className,
}: {
  onTriggerAll?: () => void
  className?: string
}) {
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
      </div>
      <button
        type="button"
        onClick={onTriggerAll}
        className="inline-flex shrink-0 items-center gap-2 rounded-md bg-red-600 px-4 py-2 text-sm font-bold uppercase tracking-wide text-white shadow-sm transition-colors hover:bg-red-500 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-red-300"
      >
        <OctagonAlert className="size-4" aria-hidden="true" />
        전체 정지
      </button>
    </div>
  )
}

export default GlobalEmergencyStopBar
