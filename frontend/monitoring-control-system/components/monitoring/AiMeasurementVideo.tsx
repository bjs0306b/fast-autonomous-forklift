"use client"

import { useEffect, useRef, useState } from "react"
import { ArrowLeft, Expand, Loader2, RefreshCw, Video, VideoOff } from "lucide-react"
import { cn } from "@/lib/utils"
import { AI_MEASUREMENT_STREAM_KIND } from "@/lib/config/aiMeasurement"
import { toBoxRects, type MeasurementBox } from "@/lib/monitoring/measurementBox"
import { streamKind } from "@/lib/monitoring/streamKind"
import { toTippingBadge } from "@/lib/monitoring/tippingLevel"

export type AiVideoConnectionStatus = "CONNECTING" | "CONNECTED" | "DISCONNECTED" | "ERROR"

const STATUS_STYLE: Record<AiVideoConnectionStatus, { label: string; className: string }> = {
  CONNECTING: { label: "연결 중", className: "border-sky-500/40 bg-sky-950/50 text-sky-200" },
  CONNECTED: { label: "연결됨", className: "border-emerald-500/40 bg-emerald-950/50 text-emerald-200" },
  DISCONNECTED: { label: "연결 대기", className: "border-slate-600 bg-slate-800/70 text-slate-300" },
  ERROR: { label: "연결 끊김", className: "border-red-500/40 bg-red-950/50 text-red-200" },
}

export function AiMeasurementVideo({
  streamUrl,
  onboardStreamUrl = null,
  connectionStatus,
  active,
  fullscreen = false,
  pip = false,
  retryKey = 0,
  lastFrameReceivedAt,
  boxes,
  frameWidth,
  frameHeight,
  tippingLevel,
  onConnectionStatusChange,
  onFrameLoaded,
  onOpenFullscreen,
  onCloseFullscreen,
  onRetry,
  className,
}: {
  streamUrl: string | null
  /**
   * 지게차(Orin) 온보드 카메라 스트림. 주면 헤더에 **스테이션/온보드 전환 버튼**이
   * 생긴다. 없으면 버튼도 없다 — 누를 곳만 있고 안 나오는 것보다 낫다.
   */
  onboardStreamUrl?: string | null
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
  /**
   * AI 가 검출한 상자의 이미지 픽셀 좌표. 영상 위에 사각형으로 그린다.
   *
   * ⚠️ 이 좌표는 **측정 카메라 화면 기준**이다. 패널이 다른 카메라를 보여주고 있으면
   *    사각형이 엉뚱한 자리에 찍힌다 — 같은 카메라인지 확인하고 넘길 것.
   */
  boxes?: MeasurementBox[] | null
  /** 위 픽셀 좌표의 기준 해상도. 없으면 환산할 수 없어 아무것도 그리지 않는다. */
  frameWidth?: number | null
  frameHeight?: number | null
  /**
   * 전복 위험 등급(`SAFE`/`WARNING`/`DANGER`). 영상 위에 배지로 얹는다.
   *
   * 종전에는 이 값이 **적재 부적합으로 실패했을 때만** 화면에 나왔다
   * (`MeasurementFailureCard`). 그러면 정상 측정에서는 등급이 어디에도 안 보여
   * "판정을 하긴 하는가"가 화면상 드러나지 않는다. 값이 없으면 배지를 그리지 않는다.
   */
  tippingLevel?: string | null
  onConnectionStatusChange: (status: AiVideoConnectionStatus) => void
  onFrameLoaded?: (receivedAt: string) => void
  onOpenFullscreen?: () => void
  onCloseFullscreen?: () => void
  onRetry?: () => void
  className?: string
}) {
  const imgRef = useRef<HTMLImageElement | null>(null)
  const [source, setSource] = useState<"station" | "onboard">("station")

  /*
   * 온보드 주소가 없으면(또는 사라지면) 스테이션으로 돌아간다. 그렇지 않으면 선택만
   * 남고 화면은 비어 "왜 안 나오지"가 된다.
   */
  const canSwitch = Boolean(onboardStreamUrl)
  const activeSource = canSwitch ? source : "station"
  const activeUrl = activeSource === "onboard" ? onboardStreamUrl : streamUrl

  // MJPEG 이냐 재생 페이지냐에 따라 그리는 요소가 다르다(streamKind.ts 주석 참고).
  // ⚠️ 아래 이펙트의 의존성 배열이 렌더 중에 읽으므로 **이펙트보다 먼저** 선언해야 한다.
  const kind = streamKind(activeUrl, AI_MEASUREMENT_STREAM_KIND)

  useEffect(() => {
    if (!active) return
    onConnectionStatusChange(activeUrl ? "CONNECTING" : "DISCONNECTED")
  }, [active, onConnectionStatusChange, retryKey, activeUrl])

  /*
   * MJPEG 은 `onLoad` 만 믿으면 안 된다.
   *
   * ⚠️ **이미지가 React 가 핸들러를 붙이기 전에 이미 로드돼 있으면 `load` 이벤트가
   *    이미 지나가 버려 영영 안 온다**(2026-08-09 실측: `<img>` 는 960x540 으로
   *    그려져 있는데 패널은 "연결 중"에서 멈춰 있었다). 캐시된 프레임이나 빠른
   *    로컬 네트워크에서 잘 일어난다.
   *
   * 그래서 이벤트 대신 **실제 상태**(`naturalWidth > 0`)를 확인한다. 한 번 보고
   * 아직이면 짧은 주기로 다시 본다 — 이벤트가 오면 그쪽이 먼저 끝낸다.
   */
  useEffect(() => {
    if (!active || !activeUrl || kind !== "mjpeg") return
    if (connectionStatus === "CONNECTED") return

    const check = () => {
      const el = imgRef.current
      if (!el || el.naturalWidth <= 0) return false
      onConnectionStatusChange("CONNECTED")
      onFrameLoaded?.(new Date().toISOString())
      return true
    }
    if (check()) return
    const timer = setInterval(() => {
      if (check()) clearInterval(timer)
    }, 500)
    return () => clearInterval(timer)
  }, [active, activeUrl, kind, connectionStatus, retryKey,
      onConnectionStatusChange, onFrameLoaded])

  const boxRects = toBoxRects(boxes, frameWidth, frameHeight)
  const tipping = toTippingBadge(tippingLevel)
  const status = STATUS_STYLE[connectionStatus]
  const showStream = active && Boolean(activeUrl)

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
          {/*
            카메라 전환. 온보드 주소가 있을 때만 나온다.

            ⚠️ 두 카메라는 **서로 다른 장치**다. 온보드로 바꾸면 측정 결과(검출 상자·
            전복 등급)는 스테이션 카메라 기준이라 화면과 안 맞는다 — 그래서 전환하면
            그 오버레이를 감춘다.
          */}
          {canSwitch ? (
            <div
              className="inline-flex overflow-hidden rounded-md border border-slate-600"
              role="group"
              aria-label="카메라 선택"
            >
              {([
                { key: "station", label: "스테이션" },
                { key: "onboard", label: "온보드" },
              ] as const).map((item) => (
                <button
                  key={item.key}
                  type="button"
                  onClick={() => setSource(item.key)}
                  aria-pressed={activeSource === item.key}
                  className={cn(
                    "px-2 py-0.5 text-[10px] font-semibold transition-colors",
                    activeSource === item.key
                      ? "bg-sky-600 text-white"
                      : "bg-slate-800 text-slate-300 hover:bg-slate-700",
                  )}
                >
                  {item.label}
                </button>
              ))}
            </div>
          ) : null}
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
        {showStream && kind === "mjpeg" ? (
          /*
           * MJPEG 송출(`multipart/x-mixed-replace`)은 **`<img>` 로 그린다.**
           *
           * ⚠️ **iframe 으로 그리면 화면은 나오는데 "연결 중"에서 영영 안 벗어난다**
           *    (2026-08-09 실측). 스트림은 응답이 끝나지 않으므로 iframe 의 `load` 가
           *    발생하지 않고, 아래 "연결 대기" 오버레이가 영상을 계속 덮는다.
           *    `<img>` 는 첫 프레임에서 `load` 가 뜬다.
           *
           * `key={retryKey}` 로 다시 만들어야 재연결이 된다 — 같은 src 를 다시 넣는
           * 것만으로는 브라우저가 요청을 새로 보내지 않는다.
           */
          // eslint-disable-next-line @next/next/no-img-element
          <img
            key={retryKey}
            ref={imgRef}
            src={activeUrl ?? undefined}
            alt="화물·팔레트 AI 측정 카메라 실시간 영상"
            className="absolute inset-0 size-full object-contain"
            onLoad={() => {
              onConnectionStatusChange("CONNECTED")
              onFrameLoaded?.(new Date().toISOString())
            }}
            onError={() => onConnectionStatusChange("ERROR")}
          />
        ) : showStream ? (
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
            src={activeUrl ?? undefined}
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

        {/*
          검출 상자 오버레이.

          iframe 위에 절대배치로 얹는다 — iframe 안(다른 오리진)에는 그릴 수 없기 때문이다.
          좌표는 %라 창 크기가 바뀌어도 따라간다(measurementBox.ts 주석 참고).
          pointer-events-none 이라 영상 조작을 가리지 않는다.
        */}
        {showStream && activeSource === "station" && boxRects.length > 0 ? (
          <div className="pointer-events-none absolute inset-0" aria-hidden="true">
            {boxRects.map((rect, index) => (
              <div
                key={`${rect.leftPct}-${rect.topPct}-${index}`}
                className="absolute border-2 border-emerald-400 shadow-[0_0_0_1px_rgba(0,0,0,0.6)]"
                style={{
                  left: `${rect.leftPct}%`,
                  top: `${rect.topPct}%`,
                  width: `${rect.widthPct}%`,
                  height: `${rect.heightPct}%`,
                }}
              >
                {rect.score != null ? (
                  <span className="absolute -top-5 left-0 rounded bg-emerald-500/90 px-1 text-[10px] font-semibold text-slate-950">
                    {rect.score.toFixed(2)}
                  </span>
                ) : null}
              </div>
            ))}
          </div>
        ) : null}

        {/*
          전복 위험 배지.

          검출 상자와 달리 **좌표가 필요 없다** — 화면 전체에 대한 판정이라 왼쪽 위에
          고정한다. 상자 오버레이와 같은 절대배치 층에 두되 상자를 가리지 않도록
          위쪽 여백만 차지한다.
        */}
        {showStream && activeSource === "station" && tipping ? (
          <div className="pointer-events-none absolute top-2 left-2 z-10">
            <span
              className={cn(
                "inline-flex items-center gap-1.5 rounded border px-2 py-1 text-[11px] font-semibold shadow-lg shadow-black/50 backdrop-blur-sm",
                pip && "px-1.5 py-0.5 text-[10px]",
                tipping.className,
              )}
              data-testid="tipping-badge"
              data-level={tipping.level}
            >
              <span className="size-1.5 rounded-full bg-current" aria-hidden="true" />
              전복 위험 {tipping.label}
            </span>
          </div>
        ) : null}

        {connectionStatus !== "CONNECTED" || !showStream ? (
          <div className={cn("absolute inset-0 flex items-center justify-center bg-slate-950/92 px-6 text-center", pip && "px-4")}>
            <div className={cn("flex max-w-md flex-col items-center gap-3", pip && "gap-2")}>
              {connectionStatus === "CONNECTING" && activeUrl ? (
                <Loader2 className={cn("size-9 animate-spin text-sky-400", pip && "size-7")} aria-hidden="true" />
              ) : connectionStatus === "ERROR" ? (
                <VideoOff className={cn("size-9 text-red-400", pip && "size-7")} aria-hidden="true" />
              ) : (
                <Video className={cn("size-9 text-slate-500", pip && "size-7")} aria-hidden="true" />
              )}
              <div>
                <p className={cn("text-sm font-semibold text-slate-100", pip && "text-[13px]")}>
                  {connectionStatus === "ERROR" ? "AI 측정 영상 연결 끊김" : activeUrl ? "AI 측정 영상 연결 중" : "AI 측정 영상 연결 대기"}
                </p>
                <p className={cn("mt-1 text-xs text-slate-400", pip && "mt-1 text-[11px]")}>
                  {connectionStatus === "ERROR" ? "영상 스트림 연결 상태를 확인하세요." : activeUrl ? "측정 카메라 영상의 첫 프레임을 기다리고 있습니다." : "측정 카메라가 연결되면 영상이 표시됩니다."}
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
