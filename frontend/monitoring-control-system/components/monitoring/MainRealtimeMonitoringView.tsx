"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { AlertTriangle, Loader2, RefreshCw } from "lucide-react"
import { cn } from "@/lib/utils"
import type {
  RealtimeConnectionStatus,
  SelectedVehicleSummary,
  WebRtcStatus,
} from "@/types/monitoring"
import { WEBRTC_STATUS_HEADLINE, WEBRTC_STATUS_TEXT } from "@/lib/config/webrtcStatusText"
import { clampOverlayOffset, type Point } from "@/lib/monitoring/draggableOverlay"
import { ConnectionStatusBadge } from "./ConnectionStatusBadge"
import { DigitalTwinVideoLayer } from "./DigitalTwinVideoLayer"
import { FullscreenButton } from "./FullscreenButton"
import { RealtimeConnectionBadge } from "./RealtimeConnectionBadge"
import { SelectedVehicleOverlay } from "./SelectedVehicleOverlay"

/**
 * MainRealtimeMonitoringView
 *
 * 실제 Isaac Sim 디지털 트윈 영상(WebRTC)을 표시하는 전용 영역.
 * 프론트에서 창고 평면도나 차량 마커를 직접 그리지 않는다. (그 역할은 MiniMap 담당)
 *
 * 영상 상태는 이 컴포넌트가 만들지 않는다 — 상위가 실제 WebRTC 이벤트로 판정한 값을 받아
 * 배지·오버레이·문구를 그릴 뿐이다(prompt80).
 *
 * Three.js / WebGL 은 사용하지 않는다.
 */
export interface MainRealtimeMonitoringViewProps {
  /** 디지털 트윈 영상 스트림 연결 상태 (기본: idle) */
  streamStatus?: WebRtcStatus
  /** 영상 연결 실패/끊김 사유 */
  streamError?: string | null
  /** iframe 컨테이너 ref (useIsaacSimStream 이 준다) */
  streamContainerRef: React.RefObject<HTMLDivElement | null>
  /** Isaac Sim MediaMTX 스트림 iframe 요소 ref (load/error 이벤트 관찰용) */
  streamVideoRef: React.RefObject<HTMLIFrameElement | null>
  /** 연결 실패 상태에서 재시도 버튼 클릭 시 호출(자동 재연결과 별개인 수동 즉시 재시도) */
  onRetryConnection?: () => void
  /** 화면 위에 작게 표시할 선택 차량 요약 (선택 안 됐으면 null) */
  selectedVehicle?: SelectedVehicleSummary | null
  /** 관제 실시간(STOMP) 연결 상태. 영상 스트림 상태와는 별개 축이다. */
  realtimeStatus?: RealtimeConnectionStatus
  /**
   * 영상 위에 겹칠 경고 오버레이(적재 위험 등). 이 컴포넌트는 무엇을 띄울지 판단하지 않고
   * 자리만 내어 준다 — 표시 조건은 오버레이 컴포넌트가 스스로 정한다.
   */
  overlay?: React.ReactNode
  /**
   * 영상 컨테이너 **안쪽 오른쪽 위**에 얹는 PIP(AI 측정 영상).
   * 별도 컬럼으로 빼지 않는다 — Isaac Sim 중심 영상이 메인이고, AI 영상은 그 위의 보조 창이다.
   */
  pip?: React.ReactNode
  className?: string
}

export function MainRealtimeMonitoringView({
  streamStatus = "idle",
  streamError = null,
  streamContainerRef,
  streamVideoRef,
  onRetryConnection,
  selectedVehicle = null,
  realtimeStatus = "connecting",
  overlay = null,
  pip = null,
  className,
}: MainRealtimeMonitoringViewProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const pipRef = useRef<HTMLDivElement>(null)
  const [isFullscreen, setIsFullscreen] = useState(false)
  const [pipOffset, setPipOffset] = useState<Point>({ x: 0, y: 0 })
  const [isDraggingPip, setIsDraggingPip] = useState(false)
  const pipOffsetRef = useRef(pipOffset)
  const pipDragRef = useRef<{
    pointerId: number
    pointerStart: Point
    offsetStart: Point
    overlayAtStart: DOMRect
    containerAtStart: DOMRect
  } | null>(null)

  useEffect(() => {
    pipOffsetRef.current = pipOffset
  }, [pipOffset])

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

  const startPipDrag = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    if (event.pointerType === "mouse" && event.button !== 0) return
    if ((event.target as HTMLElement).closest("button, a, input, select, textarea")) return
    const overlay = pipRef.current
    const container = containerRef.current
    if (!overlay || !container) return

    event.preventDefault()
    overlay.setPointerCapture(event.pointerId)
    pipDragRef.current = {
      pointerId: event.pointerId,
      pointerStart: { x: event.clientX, y: event.clientY },
      offsetStart: pipOffsetRef.current,
      overlayAtStart: overlay.getBoundingClientRect(),
      containerAtStart: container.getBoundingClientRect(),
    }
    setIsDraggingPip(true)
  }, [])

  const movePip = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    const drag = pipDragRef.current
    if (!drag || drag.pointerId !== event.pointerId) return
    const next = clampOverlayOffset(
      drag.offsetStart,
      { x: event.clientX - drag.pointerStart.x, y: event.clientY - drag.pointerStart.y },
      drag.overlayAtStart,
      drag.containerAtStart,
    )
    pipOffsetRef.current = next
    setPipOffset(next)
  }, [])

  const endPipDrag = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    if (pipDragRef.current?.pointerId !== event.pointerId) return
    pipDragRef.current = null
    setIsDraggingPip(false)
  }, [])

  return (
    <div
      ref={containerRef}
      className={cn(
        "relative h-full min-h-0 w-full flex-1 overflow-hidden rounded-lg border border-slate-700 bg-[#0b1220]",
        className,
      )}
      aria-label="디지털 트윈 실시간 영상 화면"
    >
      {/* 디지털 트윈 영상 영역 (실 WebRTC 영상 + 미연결 시 placeholder) */}
      <DigitalTwinVideoLayer
        status={streamStatus}
        error={streamError}
        containerRef={streamContainerRef}
        videoRef={streamVideoRef}
      />

      {/* 상단 좌측: 화면 정체성 + 연결 축 상태.
          "연결 예정" 이라는 고정 문구를 실제 상태로 바꿨다 — 연결됐는데도 "연결 예정"이라고
          적혀 있으면 배지와 어긋나 어느 쪽을 믿어야 할지 알 수 없다. */}
      <div className="pointer-events-none absolute left-3 top-3 z-20 flex flex-col items-start gap-2">
        <div className="flex flex-col gap-0.5">
          <span className="text-xs font-semibold text-slate-100">디지털 트윈 영상</span>
          <span className="text-[10px] text-slate-400" data-testid="stream-primary-label">
            Isaac Sim Stream · {WEBRTC_STATUS_TEXT[streamStatus].primary}
          </span>
        </div>
        {/* 선택 차량 요약. PIP 가 오른쪽 위를 차지하므로 왼쪽으로 내려 둔다 —
            둘을 같은 모서리에 두면 서로 가린다. */}
        {selectedVehicle && pip ? <SelectedVehicleOverlay vehicle={selectedVehicle} /> : null}
      </div>

      {/* 상단 우측: 영상/실시간 상태 배지 열. 그 아래가 AI 측정 영상 PIP 자리다. */}
      <div className="absolute right-3 top-3 z-20 flex flex-col items-end gap-2">
        <div className="flex items-center gap-2">
          <RealtimeConnectionBadge status={realtimeStatus} />
          <ConnectionStatusBadge status={streamStatus} />
          <FullscreenButton isFullscreen={isFullscreen} onToggle={toggleFullscreen} />
        </div>
        {selectedVehicle && !pip ? <SelectedVehicleOverlay vehicle={selectedVehicle} /> : null}
      </div>

      {/* AI 측정 영상 PIP — Isaac Sim 영상 컨테이너 기준 absolute, 오른쪽 위 고정.
          z-40 인 이유: 연결 중/실패 오버레이(z-30)가 떠도 AI 영상은 계속 보여야 한다 —
          Isaac 스트림이 끊긴 것과 측정 카메라가 끊긴 것은 별개 축이다.

          폭(뷰포트 기준 3단계, 큰 화면 우선):
            1501px~  clamp(340px, 32%, 470px)  → 1920 에서 약 430px
            ~1500px  clamp(300px, 31%, 410px)
            ~1200px  clamp(250px, 35%, 340px)  + 여백 12px
          16:9 는 PIP 카드 본문(aspect-video)이 유지하므로 여기서는 폭만 정한다.

          top 은 요청받은 16px 대신 배지 열(top-3 + 높이 약 28px) 아래로 내린 값이다.
          16px 로 두면 같은 모서리의 연결 상태 배지·전체화면 버튼을 PIP 가 덮는다.
          right 는 요청대로 16px(좁은 화면 12px)다. */}
      {pip ? (
        <div
          ref={pipRef}
          data-testid="draggable-ai-video"
          title="마우스로 끌어 위치 이동"
          onPointerDown={startPipDrag}
          onPointerMove={movePip}
          onPointerUp={endPipDrag}
          onPointerCancel={endPipDrag}
          style={{ transform: `translate3d(${pipOffset.x}px, ${pipOffset.y}px, 0)` }}
          className={cn(
            "absolute z-40 touch-none select-none",
            isDraggingPip ? "cursor-grabbing" : "cursor-grab",
            "right-4 top-[3.25rem] max-[1200px]:right-3 max-[1200px]:top-12",
            "w-[clamp(340px,32%,470px)] max-[1500px]:w-[clamp(300px,31%,410px)] max-[1200px]:w-[clamp(250px,35%,340px)]",
            // 컨테이너를 넘지 않게 하는 마지막 안전장치(아주 좁은 폭).
            "max-w-[calc(100%-1.5rem)]",
          )}
        >
          {pip}
        </div>
      ) : null}

      {/* 적재 위험 등 경고 오버레이. 연결 중/실패 오버레이(z-30)보다 먼저 두어
          영상이 정상 표시될 때 하단에 겹쳐 보이게 한다. */}
      {overlay}

      {/* 연결 중 / 재연결 중 로딩 오버레이 */}
      {streamStatus === "connecting" || streamStatus === "reconnecting" ? (
        <div className="absolute inset-0 z-30 flex items-center justify-center bg-slate-950/45 backdrop-blur-[1px]">
          <div className="flex flex-col items-center gap-3 text-center">
            <Loader2 className="size-8 animate-spin text-sky-400" aria-hidden="true" />
            <p className="text-sm font-medium text-slate-100">
              {WEBRTC_STATUS_HEADLINE[streamStatus]}…
            </p>
            {streamStatus === "reconnecting" ? (
              <p className="text-xs text-slate-400">연결이 끊겨 자동으로 다시 연결하고 있습니다.</p>
            ) : null}
          </div>
        </div>
      ) : null}

      {/* 연결 실패 오버레이 */}
      {streamStatus === "failed" ? (
        <div className="absolute inset-0 z-30 flex items-center justify-center bg-slate-950/55 backdrop-blur-[1px]">
          <div className="flex flex-col items-center gap-3 rounded-lg border border-red-500/30 bg-slate-900/90 px-6 py-5 text-center shadow-xl">
            <div className="flex size-11 items-center justify-center rounded-full bg-red-500/15 text-red-400">
              <AlertTriangle className="size-6" aria-hidden="true" />
            </div>
            <div>
              <p className="text-sm font-medium text-slate-100">영상 연결에 실패했습니다</p>
              {/* 실패 사유를 그대로 보여 준다 — "연결할 수 없습니다"만으로는 조치할 수 없다. */}
              <p className="mt-1 max-w-xs text-xs break-all text-slate-400">
                {streamError ?? "스트림 서버에 연결할 수 없습니다."}
              </p>
              <p className="mt-1 text-[11px] text-slate-500">3초 뒤 자동으로 다시 연결합니다.</p>
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
