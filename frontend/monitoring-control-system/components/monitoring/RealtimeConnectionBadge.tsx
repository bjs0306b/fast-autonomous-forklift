import { Loader2, Wifi, WifiOff, TriangleAlert } from "lucide-react"
import { cn } from "@/lib/utils"
import type { RealtimeConnectionStatus } from "@/types/monitoring"

/**
 * 관제 실시간(STOMP) 연결 상태 배지.
 * 디지털 트윈 "영상" 연결 상태(ConnectionStatusBadge)와는 다른 축이라 컴포넌트를 분리했다.
 *
 * 색상만으로 구분하지 않고 텍스트 라벨을 항상 함께 표시한다(접근성).
 */
const BADGE: Record<
  RealtimeConnectionStatus,
  { label: string; icon: typeof Wifi; dot: string; className: string }
> = {
  connecting: {
    label: "실시간 연결 중",
    icon: Loader2,
    dot: "bg-sky-400",
    className: "bg-slate-900/80 text-slate-200 ring-1 ring-white/10",
  },
  connected: {
    label: "실시간 연결됨",
    icon: Wifi,
    dot: "bg-emerald-400",
    className: "bg-emerald-950/70 text-emerald-100 ring-1 ring-emerald-400/30",
  },
  disconnected: {
    label: "실시간 연결 끊김",
    icon: WifiOff,
    dot: "bg-amber-400",
    className: "bg-amber-950/60 text-amber-100 ring-1 ring-amber-400/30",
  },
  error: {
    label: "실시간 연결 오류",
    icon: TriangleAlert,
    dot: "bg-red-400",
    className: "bg-red-950/70 text-red-100 ring-1 ring-red-400/30",
  },
}

export function RealtimeConnectionBadge({
  status,
  className,
}: {
  status: RealtimeConnectionStatus
  className?: string
}) {
  const { label, icon: Icon, dot, className: toneClassName } = BADGE[status]
  return (
    <div
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[11px] font-medium shadow-sm backdrop-blur-sm",
        toneClassName,
        className,
      )}
      role="status"
      aria-live="polite"
    >
      <span
        className={cn("size-1.5 rounded-full", dot, status === "connecting" && "animate-pulse")}
        aria-hidden="true"
      />
      <Icon
        className={cn("size-3", status === "connecting" && "animate-spin")}
        aria-hidden="true"
      />
      <span>{label}</span>
    </div>
  )
}

export default RealtimeConnectionBadge
