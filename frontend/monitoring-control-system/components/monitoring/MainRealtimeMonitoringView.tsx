"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { AlertTriangle, Loader2, RefreshCw } from "lucide-react"
import { cn } from "@/lib/utils"
import type {
  RealtimeConnectionStatus,
  SelectedVehicleSummary,
  StreamConnectionStatus,
} from "@/types/monitoring"
import { ConnectionStatusBadge } from "./ConnectionStatusBadge"
import { DigitalTwinVideoLayer } from "./DigitalTwinVideoLayer"
import { FullscreenButton } from "./FullscreenButton"
import { RealtimeConnectionBadge } from "./RealtimeConnectionBadge"
import { SelectedVehicleOverlay } from "./SelectedVehicleOverlay"

/**
 * MainRealtimeMonitoringView
 *
 * 실제 Isaac Sim / 디지털 트윈 3D 영상을 표시할 전용 영역.
 * 프론트에서 창고 평면도나 차량 마커를 직접 그리지 않는다. (그 역할은 MiniMap 담당)
 * 스트림 방식이 확정되기 전에는 DigitalTwinVideoLayer(placeholder)만 표시한다.
 *
 * Three.js / WebGL / 실제 영상 스트리밍은 사용하지 않는다.
 */
export interface MainRealtimeMonitoringViewProps {
  /** 디지털 트윈 영상 스트림 연결 상태 (기본: idle) */
  streamStatus?: StreamConnectionStatus
  /** 연결 실패 상태에서 재시도 버튼 클릭 시 호출 */
  onRetryConnection?: () => void
  /** 화면 위에 작게 표시할 선택 차량 요약 (선택 안 됐으면 null) */
  selectedVehicle?: SelectedVehicleSummary | null
  /** 관제 실시간(STOMP) 연결 상태. 영상 스트림 상태와는 별개 축이다. */
  realtimeStatus?: RealtimeConnectionStatus
  className?: string
}

export function MainRealtimeMonitoringView({
  streamStatus = "idle",
  onRetryConnection,
  selectedVehicle = null,
  realtimeStatus = "connecting",
  className,
}: MainRealtimeMonitoringViewProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const [isFullscreen, setIsFullscreen] = useState(false)

  useEffect(() => {
    const handleChange = () => {
      setIsFullscreen(document.fullscreenElement === containerRef.current)
    }
    document.addEventListener("fullscreenchange", handleChange)
    return () => document.removeEventListener("fullscreenchange", handleChange)
  }, [])

  const toggleFullscreen = useCallback(() => {
    const el = containerRef.current
    if (!el) return
    if (document.fullscreenElement) {
      void document.exitFullscreen()
    } else {
      void el.requestFullscreen?.()
    }
  }, [])

  return (
    <div
      ref={containerRef}
      className={cn(
        "relative min-h-0 w-full flex-1 overflow-hidden rounded-lg border border-slate-700 bg-[#0b1220]",
        className,
      )}
      aria-label="디지털 트윈 실시간 영상 화면"
    >
      {/* 디지털 트윈 영상 영역 (placeholder / 추후 실영상으로 교체) */}
      <DigitalTwinVideoLayer status={streamStatus} />

      {/* 상단 좌측: 화면 정체성 오버레이 */}
      <div className="pointer-events-none absolute left-3 top-3 z-20 flex flex-col gap-0.5">
        <span className="text-xs font-semibold text-slate-100">디지털 트윈 영상</span>
        <span className="text-[10px] text-slate-400">Isaac Sim Stream · 연결 예정</span>
      </div>

      {/* 상단 우측: 상태 배지 + 전체 화면 버튼 + 선택 차량 오버레이 */}
      <div className="absolute right-3 top-3 z-20 flex flex-col items-end gap-2">
        <div className="flex items-center gap-2">
          <RealtimeConnectionBadge status={realtimeStatus} />
          <ConnectionStatusBadge status={streamStatus} />
          <FullscreenButton isFullscreen={isFullscreen} onToggle={toggleFullscreen} />
        </div>
        {selectedVehicle ? <SelectedVehicleOverlay vehicle={selectedVehicle} /> : null}
      </div>

      {/* 연결 중(로딩) 오버레이 */}
      {streamStatus === "connecting" ? (
        <div className="absolute inset-0 z-30 flex items-center justify-center bg-slate-950/45 backdrop-blur-[1px]">
          <div className="flex flex-col items-center gap-3 text-center">
            <Loader2 className="size-8 animate-spin text-sky-400" aria-hidden="true" />
            <p className="text-sm font-medium text-slate-100">영상 스트림 연결 중…</p>
          </div>
        </div>
      ) : null}

      {/* 연결 실패 오버레이 */}
      {streamStatus === "error" ? (
        <div className="absolute inset-0 z-30 flex items-center justify-center bg-slate-950/55 backdrop-blur-[1px]">
          <div className="flex flex-col items-center gap-3 rounded-lg border border-red-500/30 bg-slate-900/90 px-6 py-5 text-center shadow-xl">
            <div className="flex size-11 items-center justify-center rounded-full bg-red-500/15 text-red-400">
              <AlertTriangle className="size-6" aria-hidden="true" />
            </div>
            <div>
              <p className="text-sm font-medium text-slate-100">영상 연결에 실패했습니다</p>
              <p className="mt-1 text-xs text-slate-400">스트림 서버에 연결할 수 없습니다.</p>
            </div>
            {onRetryConnection ? (
              <button
                type="button"
                onClick={onRetryConnection}
                className="inline-flex items-center gap-2 rounded-md bg-slate-100 px-3.5 py-2 text-sm font-medium text-slate-900 transition-colors hover:bg-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sky-400"
              >
                <RefreshCw className="size-4" aria-hidden="true" />
                다시 연결
              </button>
            ) : null}
          </div>
        </div>
      ) : null}
    </div>
  )
}

export default MainRealtimeMonitoringView
