import { Loader2, Radio, Wifi, WifiOff } from "lucide-react"
import { cn } from "@/lib/utils"
import type { StreamConnectionStatus } from "@/types/monitoring"

/**
 * 영상 연결 상태를 화면을 가리지 않는 작은 배지로 표시한다.
 * 연결 전(idle)에는 "영상 연동 대기 / Mock Digital Twin" 뉘앙스를 준다.
 */
const BADGE: Record<
  StreamConnectionStatus,
  { label: string; icon: typeof Wifi; dot: string; className: string }
> = {
  idle: {
    label: "영상 연동 대기 · Mock",
    icon: Radio,
    dot: "bg-amber-400",
    className: "bg-slate-900/80 text-slate-200 ring-1 ring-white/10",
  },
  connecting: {
    label: "Isaac Sim 연결 중",
    icon: Loader2,
    dot: "bg-sky-400",
    className: "bg-slate-900/80 text-slate-200 ring-1 ring-white/10",
  },
  connected: {
    label: "영상 연결됨",
    icon: Wifi,
    dot: "bg-emerald-400",
    className: "bg-emerald-950/70 text-emerald-100 ring-1 ring-emerald-400/30",
  },
  error: {
    label: "영상 연결 실패",
    icon: WifiOff,
    dot: "bg-red-400",
    className: "bg-red-950/70 text-red-100 ring-1 ring-red-400/30",
  },
}

export function ConnectionStatusBadge({ status }: { status: StreamConnectionStatus }) {
  const { label, icon: Icon, dot, className } = BADGE[status]
  return (
    <div
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[11px] font-medium shadow-sm backdrop-blur-sm",
        className,
      )}
      role="status"
      aria-live="polite"
    >
      <span className={cn("size-1.5 rounded-full", dot, status === "connecting" && "animate-pulse")} aria-hidden="true" />
      <Icon className={cn("size-3", status === "connecting" && "animate-spin")} aria-hidden="true" />
      <span>{label}</span>
    </div>
  )
}

export default ConnectionStatusBadge
