"use client"

import { CheckCircle2, TriangleAlert, XCircle, X } from "lucide-react"
import { cn } from "@/lib/utils"

/**
 * 안전 명령 결과 알림.
 *
 * 이 프로젝트에는 toast 라이브러리가 없어(확인함) 화면 스타일에 맞는 최소 알림만 둔다.
 *
 * <b>tone 을 세 단계로 나눈 이유</b>: 비상정지는 "명령이 브로커까지 나갔다(PUBLISHED)"와
 * "발행 자체가 실패했다(PUBLISH_FAILED)"가 둘 다 HTTP 201 로 내려온다. 이 둘을 같은 성공 색으로
 * 표시하면 조작자가 정지된 줄 알고 넘어가게 되므로, 반드시 시각·문구를 분리한다.
 * 부분 실패(일부 차량만 발행 성공)도 성공이 아니라 warning 으로 표시한다.
 */
export type CommandNoticeTone = "success" | "warning" | "error"

export interface CommandNoticeState {
  tone: CommandNoticeTone
  message: string
  /** 차량별 실패 목록 등 부가 설명 */
  detail?: string | null
}

const TONE = {
  success: {
    icon: CheckCircle2,
    className: "border-emerald-500/40 bg-emerald-950/50 text-emerald-100",
    iconClass: "text-emerald-400",
  },
  warning: {
    icon: TriangleAlert,
    className: "border-amber-500/40 bg-amber-950/50 text-amber-100",
    iconClass: "text-amber-400",
  },
  error: {
    icon: XCircle,
    className: "border-red-500/40 bg-red-950/50 text-red-100",
    iconClass: "text-red-400",
  },
} as const

export function CommandNotice({
  notice,
  onDismiss,
  className,
}: {
  notice: CommandNoticeState | null
  onDismiss?: () => void
  className?: string
}) {
  if (!notice) return null
  const { icon: Icon, className: toneClass, iconClass } = TONE[notice.tone]

  return (
    <div
      className={cn("flex shrink-0 items-start gap-2 rounded-lg border px-3 py-1.5", toneClass, className)}
      // 실패는 즉시 읽히도록 assertive, 성공은 polite
      role={notice.tone === "success" ? "status" : "alert"}
      aria-live={notice.tone === "success" ? "polite" : "assertive"}
      data-testid="command-notice"
      data-tone={notice.tone}
    >
      <Icon className={cn("mt-0.5 size-4 shrink-0", iconClass)} aria-hidden="true" />
      <div className="min-w-0 flex-1">
        <p className="text-xs font-medium text-pretty">{notice.message}</p>
        {notice.detail ? (
          <p className="mt-0.5 text-[11px] break-words opacity-80">{notice.detail}</p>
        ) : null}
      </div>
      {onDismiss ? (
        <button
          type="button"
          onClick={onDismiss}
          className="shrink-0 rounded p-0.5 opacity-70 transition-opacity hover:opacity-100 focus-visible:ring-2 focus-visible:ring-white/40 focus-visible:outline-none"
          aria-label="알림 닫기"
        >
          <X className="size-3.5" aria-hidden="true" />
        </button>
      ) : null}
    </div>
  )
}

export default CommandNotice
