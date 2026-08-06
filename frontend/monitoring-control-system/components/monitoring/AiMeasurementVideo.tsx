"use client"

import { useEffect } from "react"
import { ArrowLeft, Expand, Loader2, RefreshCw, Video, VideoOff } from "lucide-react"
import { cn } from "@/lib/utils"

export type AiVideoConnectionStatus = "CONNECTING" | "CONNECTED" | "DISCONNECTED" | "ERROR"

const STATUS_STYLE: Record<AiVideoConnectionStatus, { label: string; className: string }> = {
  CONNECTING: { label: "연결 중", className: "border-sky-500/40 bg-sky-950/50 text-sky-200" },
  CONNECTED: { label: "연결됨", className: "border-emerald-500/40 bg-emerald-950/50 text-emerald-200" },
  DISCONNECTED: { label: "연결 대기", className: "border-slate-600 bg-slate-800/70 text-slate-300" },
  ERROR: { label: "연결 끊김", className: "border-red-500/40 bg-red-950/50 text-red-200" },
}

export function AiMeasurementVideo({
  streamUrl,
  connectionStatus,
  active,
  fullscreen = false,
  pip = false,
  retryKey = 0,
  lastFrameReceivedAt,
  onConnectionStatusChange,
  onFrameLoaded,
  onOpenFullscreen,
  onCloseFullscreen,
  onRetry,
  className,
}: {
  streamUrl: string | null
  connectionStatus: AiVideoConnectionStatus
  active: boolean
  fullscreen?: boolean
  /**
   * Isaac Sim 영상 컨테이너 **안쪽 오른쪽 위**에 얹히는 작은 카드로 그린다.
   * 위치·크기는 이 컴포넌트가 정하지 않는다 — 감싸는 쪽(MainRealtimeMonitoringView)이 정하고,
   * 여기서는 "좁은 폭에서도 읽히는 밀도"만 책임진다.
   */
  pip?: boolean
  retryKey?: number
  lastFrameReceivedAt?: string | null
  onConnectionStatusChange: (status: AiVideoConnectionStatus) => void
  onFrameLoaded?: (receivedAt: string) => void
  onOpenFullscreen?: () => void
  onCloseFullscreen?: () => void
  onRetry?: () => void
  className?: string
}) {
  useEffect(() => {
    if (!active) return
    onConnectionStatusChange(streamUrl ? "CONNECTING" : "DISCONNECTED")
  }, [active, onConnectionStatusChange, retryKey, streamUrl])

  const status = STATUS_STYLE[connectionStatus]
  const showStream = active && Boolean(streamUrl)

  return (
    <section
      className={cn(
        "flex h-full min-h-[360px] min-w-0 flex-col overflow-hidden rounded-lg border border-slate-700 bg-[#0b1220]",
        fullscreen && "min-h-0",
        // PIP: Isaac Sim 영상 위에 얹히므로 높이를 스스로 정하지 않고(16:9 본문이 정한다)
        // 배경을 살짝 비쳐 두어 "메인 영상 위의 보조 창"으로 읽히게 한다.
        pip && "h-auto min-h-0 border-slate-600/80 bg-slate-950/85 shadow-lg shadow-black/40 backdrop-blur-sm",
        className,
      )}
      aria-label="AI 측정 영상"
    >
      <header
        className={cn(
          "flex shrink-0 items-center justify-between gap-3 border-b border-slate-700 px-3 py-2",
          // PIP 헤더 높이 ≈ 35px (버튼 22px + py-1.5 12px + border 1px).
          pip && "gap-2 border-slate-600/70 px-2.5 py-1.5",
        )}
      >
        <div className={cn("flex min-w-0 items-center gap-2.5", pip && "gap-2")}>
          {fullscreen && onCloseFullscreen ? (
            <button
              type="button"
              onClick={onCloseFullscreen}
              aria-label="통합 관제로 돌아가기"
              className="inline-flex shrink-0 items-center gap-1.5 rounded-md border border-slate-600 bg-slate-800 px-2.5 py-1.5 text-xs font-medium text-slate-100 hover:bg-slate-700 focus-visible:ring-2 focus-visible:ring-sky-400 focus-visible:outline-none"
            >
              <ArrowLeft className="size-3.5" aria-hidden="true" />
              통합 관제로 돌아가기
            </button>
          ) : null}
          <div className="min-w-0">
            {/* PIP 도 제목은 본 카드와 같은 14px 를 쓴다 — 폭이 340px 이상이라 잘리지 않는다. */}
            <h2 className="truncate text-sm font-semibold text-slate-100">AI 측정 영상</h2>
            {/* 보조 문구는 PIP 에서 생략한다 — 한 줄 더 쌓이면 헤더가 38px 를 넘는다. */}
            {pip ? null : (
              <p className="truncate text-[10px] text-slate-400">
                {fullscreen ? "화물·팔레트 측정 카메라" : "화물·팔레트 실시간 측정 영상"}
              </p>
            )}
          </div>
        </div>
        <div className={cn("flex shrink-0 items-center gap-2", pip && "gap-1")}>
          {fullscreen && lastFrameReceivedAt ? (
            <span className="hidden text-[10px] text-slate-400 sm:inline">
              최근 프레임 {formatTime(lastFrameReceivedAt)}
            </span>
          ) : null}
          <span
            className={cn(
              // 배지는 PIP 에서도 본 카드와 같은 크기를 유지한다(prompt173).
              "inline-flex items-center gap-1 rounded border px-2 py-0.5 text-[10px] font-semibold",
              status.className,
            )}
            aria-live="polite"
          >
            <span className="size-1.5 rounded-full bg-current" aria-hidden="true" />
            {status.label}
          </span>
          {!fullscreen && onOpenFullscreen ? (
            <button
              type="button"
              onClick={onOpenFullscreen}
              aria-label="AI 측정 영상 전체 보기"
              title={pip ? "AI 측정 영상 전체 보기" : undefined}
              className={cn(
                "inline-flex items-center gap-1.5 rounded-md border border-slate-600 bg-slate-800 px-2.5 py-1.5 text-xs font-medium text-slate-100 hover:bg-slate-700 focus-visible:ring-2 focus-visible:ring-sky-400 focus-visible:outline-none",
                pip && "gap-0 p-1",
              )}
            >
              <Expand className="size-3.5" aria-hidden="true" />
              {/* PIP 에서는 아이콘만 — 라벨까지 넣을 가로 여유가 없다. */}
              {pip ? null : "전체 보기"}
            </button>
          ) : null}
        </div>
      </header>

      <div
        className={cn(
          "relative min-h-0 flex-1 overflow-hidden bg-black",
          // PIP 는 높이를 흘려받지 않는다 — 16:9 로 스스로 정해 Isaac 영상을 덜 가린다.
          pip && "aspect-video flex-none",
        )}
      >
        {showStream ? (
          /*
           * MediaMTX 의 WebRTC 재생 페이지를 그대로 띄운다(Orin 카메라 → ffmpeg H.264 → RTSP →
           * MediaMTX → WebRTC/UDP). URL 이 없으면 요소 자체를 만들지 않는다.
           *
           * ⚠️ **예전에는 `<img>` + MJPEG 이었다.** 바꾼 이유는 지연이다 — MJPEG 은 TCP 라
           *    패킷 유실 시 밀린 지연이 스스로 회복되지 않고, 프레임 간 압축이 없어 대역폭도
           *    3배 이상 썼다(실측 6.8Mbps → 2Mbps).
           *
           * ⚠️ **`onLoad` 는 "페이지가 떴다"까지만 보장한다.** iframe 은 다른 오리진이라 부모가
           *    그 안의 <video> 재생 상태를 볼 수 없다. 그래서 CONNECTED 가 곧 "영상이 나온다"는
           *    뜻은 아니다 — 송출(ffmpeg)이 죽어도 MediaMTX 페이지 자체는 뜬다.
           *    IsaacSimStream.tsx 상단 주석에 같은 한계와 그 대응(도달성 사전 확인)을 적어 두었다.
           */
          <iframe
            key={retryKey}
            src={streamUrl ?? undefined}
            title="화물·팔레트 AI 측정 카메라 실시간 영상"
            allow="autoplay; fullscreen"
            className="absolute inset-0 size-full border-0"
            onLoad={() => {
              onConnectionStatusChange("CONNECTED")
              onFrameLoaded?.(new Date().toISOString())
            }}
            onError={() => onConnectionStatusChange("ERROR")}
          />
        ) : null}

        {connectionStatus !== "CONNECTED" || !showStream ? (
          <div className={cn("absolute inset-0 flex items-center justify-center bg-slate-950/92 px-6 text-center", pip && "px-4")}>
            <div className={cn("flex max-w-md flex-col items-center gap-3", pip && "gap-2")}>
              {connectionStatus === "CONNECTING" && streamUrl ? (
                <Loader2 className={cn("size-9 animate-spin text-sky-400", pip && "size-7")} aria-hidden="true" />
              ) : connectionStatus === "ERROR" ? (
                <VideoOff className={cn("size-9 text-red-400", pip && "size-7")} aria-hidden="true" />
              ) : (
                <Video className={cn("size-9 text-slate-500", pip && "size-7")} aria-hidden="true" />
              )}
              <div>
                <p className={cn("text-sm font-semibold text-slate-100", pip && "text-[13px]")}>
                  {connectionStatus === "ERROR" ? "AI 측정 영상 연결 끊김" : streamUrl ? "AI 측정 영상 연결 중" : "AI 측정 영상 연결 대기"}
                </p>
                <p className={cn("mt-1 text-xs text-slate-400", pip && "mt-1 text-[11px]")}>
                  {connectionStatus === "ERROR" ? "영상 스트림 연결 상태를 확인하세요." : streamUrl ? "측정 카메라 영상의 첫 프레임을 기다리고 있습니다." : "측정 카메라가 연결되면 영상이 표시됩니다."}
                </p>
              </div>
              {connectionStatus === "ERROR" && onRetry ? (
                <button
                  type="button"
                  onClick={onRetry}
                  aria-label="AI 측정 영상 다시 연결"
                  className={cn(
                    "inline-flex items-center gap-1.5 rounded-md bg-slate-100 px-3 py-2 text-xs font-medium text-slate-900 hover:bg-white focus-visible:ring-2 focus-visible:ring-sky-400 focus-visible:outline-none",
                    pip && "gap-1 px-2.5 py-1.5 text-[11px]",
                  )}
                >
                  <RefreshCw className="size-3.5" aria-hidden="true" />
                  다시 연결
                </button>
              ) : null}
            </div>
          </div>
        ) : null}
      </div>
    </section>
  )
}

function formatTime(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleTimeString("ko-KR", { hour12: false })
}
