"use client"

import { CirclePause, CirclePlay, Play, RotateCcw } from "lucide-react"
import { cn } from "@/lib/utils"
import type { OperationStateResponse } from "@/lib/api/operationApi"
import { joiningLabel, toOperationControls } from "@/lib/monitoring/operationControls"

/**
 * 운행 제어 바 — 시작 / 일시정지 / 재개 / 종료.
 *
 * 「시작 시그널」이라는 별도 명령은 없다. 차량은 스스로 출발하지 않고 백엔드가 첫 목표를
 * 보내야 움직이므로, **시작을 누르는 것이 곧 첫 목표를 주는 것**이다.
 *
 * 시작을 눌러도 세 대가 한꺼번에 나가지 않는다 — 백엔드가 3초 간격으로 한 대씩 합류시킨다
 * (실물 우선). 그동안 "합류 중 (1/3)" 이 보인다. 이 표시가 없으면 눌렀는데 한 대만 움직이는
 * 것이 고장처럼 보인다.
 *
 * 어떤 버튼을 보일지는 {@link toOperationControls} 가 정한다. 상태 4 × 버튼 4 조합을
 * 컴포넌트 안에 흩어 두면 놓치는 경우가 생기고, 그 버그는 눌러 봐야 안다.
 *
 * 비상정지는 기존 {@code GlobalEmergencyStopBar} 가 맡는다 — 위험도가 다른 조작이라
 * 같은 줄에 두지 않는다.
 */
export function OperationControlBar({
  operation,
  pending = false,
  onStart,
  onPause,
  onResume,
  onReset,
  className,
}: {
  /** 서버가 준 운행 상태. `null` 이면 아직 못 받은 것이라 모든 버튼이 잠긴다 */
  operation: OperationStateResponse | null
  /** 요청 진행 중 여부(중복 클릭 방지) */
  pending?: boolean
  onStart?: () => void
  onPause?: () => void
  onResume?: () => void
  onReset?: () => void
  className?: string
}) {
  const controls = toOperationControls(operation)
  const joining = joiningLabel(operation)

  return (
    <div
      className={cn(
        "flex shrink-0 items-center justify-between gap-2 rounded-lg border border-slate-600/40 bg-slate-900/60 px-3 py-1.5",
        className,
      )}
      role="region"
      aria-label="운행 제어"
    >
      <div className="flex items-center gap-2">
        <span className="text-xs font-medium text-slate-300">운행 상태</span>
        <span
          className={cn(
            "rounded px-1.5 py-0.5 font-mono text-[10px]",
            stateTone(operation?.state),
          )}
          data-testid="operation-state"
        >
          {controls.label}
        </span>
        {joining ? (
          <span
            className="rounded bg-sky-500/15 px-1.5 py-0.5 font-mono text-[10px] text-sky-200"
            data-testid="operation-joining"
          >
            {joining}
          </span>
        ) : null}
      </div>

      <div className="flex shrink-0 items-center gap-2">
        {controls.showStart ? (
          <ControlButton
            testId="operation-start-button"
            onClick={onStart}
            pending={pending}
            tone="start"
            icon={<Play className="size-4" aria-hidden="true" />}
            label="운행 시작"
          />
        ) : null}

        {controls.showPause ? (
          <ControlButton
            testId="operation-pause-button"
            onClick={onPause}
            pending={pending}
            tone="neutral"
            icon={<CirclePause className="size-4" aria-hidden="true" />}
            label="일시정지"
          />
        ) : null}

        {controls.showResume ? (
          <ControlButton
            testId="operation-resume-button"
            onClick={onResume}
            pending={pending}
            tone="start"
            icon={<CirclePlay className="size-4" aria-hidden="true" />}
            label={controls.confirmBeforeResume ? "확인 후 재개" : "재개"}
            title={
              controls.confirmBeforeResume
                ? "비상정지 상태입니다. 현장을 확인한 뒤 눌러 주세요."
                : undefined
            }
          />
        ) : null}

        {controls.showReset ? (
          <ControlButton
            testId="operation-reset-button"
            onClick={onReset}
            pending={pending}
            tone="neutral"
            icon={<RotateCcw className="size-4" aria-hidden="true" />}
            label="운행 종료"
          />
        ) : null}
      </div>
    </div>
  )
}

function ControlButton({
  testId,
  onClick,
  pending,
  tone,
  icon,
  label,
  title,
}: {
  testId: string
  onClick?: () => void
  pending: boolean
  tone: "start" | "neutral"
  icon: React.ReactNode
  label: string
  title?: string
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={pending}
      aria-busy={pending}
      data-testid={testId}
      title={title}
      className={cn(
        "inline-flex shrink-0 items-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-semibold transition-colors focus-visible:ring-2 focus-visible:outline-none",
        pending
          ? "cursor-not-allowed bg-slate-800 text-slate-500"
          : tone === "start"
            ? "bg-emerald-600 text-white hover:bg-emerald-500 focus-visible:ring-emerald-300"
            : "bg-slate-700 text-slate-100 hover:bg-slate-600 focus-visible:ring-slate-400",
      )}
    >
      {icon}
      {pending ? "전송 중..." : label}
    </button>
  )
}

function stateTone(state: string | undefined): string {
  switch (state) {
    case "RUNNING":
      return "bg-emerald-500/15 text-emerald-200"
    case "PAUSED":
      return "bg-amber-500/15 text-amber-200"
    case "ESTOPPED":
      return "bg-red-500/15 text-red-200"
    default:
      return "bg-slate-500/15 text-slate-300"
  }
}

export default OperationControlBar
