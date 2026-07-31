import { Loader2, Radio, RotateCw, Wifi, WifiOff } from "lucide-react"
import { cn } from "@/lib/utils"
import type { WebRtcStatus } from "@/types/monitoring"
import { WEBRTC_STATUS_TEXT } from "@/lib/config/webrtcStatusText"

/**
 * 영상(WebRTC) 연결 상태를 화면을 가리지 않는 작은 배지로 표시한다.
 *
 * 문구는 {@link WEBRTC_STATUS_TEXT} 한 곳에서 온다 — 여기서 따로 만들지 않는다.
 * 상태는 실제 WebRTC 이벤트로만 바뀌므로, 이 배지가 "영상 연결됨"이면 실제로 영상이
 * 재생 중이라는 뜻이다(prompt80 16항).
 */
const BADGE: Record<
  WebRtcStatus,
  { icon: typeof Wifi; dot: string; className: string; spin?: boolean }
> = {
  idle: {
    icon: Radio,
    dot: "bg-slate-400",
    className: "bg-slate-900/80 text-slate-200 ring-1 ring-white/10",
  },
  connecting: {
    icon: Loader2,
    dot: "bg-sky-400",
    className: "bg-slate-900/80 text-slate-200 ring-1 ring-white/10",
    spin: true,
  },
  connected: {
    icon: Wifi,
    dot: "bg-emerald-400",
    className: "bg-emerald-950/70 text-emerald-100 ring-1 ring-emerald-400/30",
  },
  reconnecting: {
    icon: RotateCw,
    dot: "bg-amber-400",
    className: "bg-amber-950/70 text-amber-100 ring-1 ring-amber-400/30",
    spin: true,
  },
  disconnected: {
    icon: WifiOff,
    dot: "bg-slate-400",
    className: "bg-slate-900/80 text-slate-200 ring-1 ring-white/10",
  },
  failed: {
    icon: WifiOff,
    dot: "bg-red-400",
    className: "bg-red-950/70 text-red-100 ring-1 ring-red-400/30",
  },
}

export function ConnectionStatusBadge({ status }: { status: WebRtcStatus }) {
  const { icon: Icon, dot, className, spin } = BADGE[status]
  return (
    <div
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[11px] font-medium shadow-sm backdrop-blur-sm",
        className,
      )}
      role="status"
      aria-live="polite"
      data-testid="stream-status-badge"
      data-status={status}
    >
      <span className={cn("size-1.5 rounded-full", dot, spin && "animate-pulse")} aria-hidden="true" />
      <Icon className={cn("size-3", spin && "animate-spin")} aria-hidden="true" />
      <span>{WEBRTC_STATUS_TEXT[status].stream}</span>
    </div>
  )
}

export default ConnectionStatusBadge
