"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { CircleAlert, Radio } from "lucide-react"
import type { StreamEvent } from "@nvidia/ov-web-rtc"
import { cn } from "@/lib/utils"
import {
  ISAAC_AUDIO_ELEMENT_ID,
  ISAAC_RECONNECT_DELAY_MS,
  ISAAC_SIM_CONFIG,
  ISAAC_VIDEO_ELEMENT_ID,
} from "@/lib/config/isaacSim"
import { WEBRTC_STATUS_TEXT } from "@/lib/config/webrtcStatusText"
import type { WebRtcStatus } from "@/types/monitoring"

/**
 * IsaacSimStream
 *
 * Isaac Sim WebRTC 스트림의 **연결 담당 훅**({@link useIsaacSimStream}), **영상 요소**
 * ({@link IsaacSimVideoSurface}), **접속 정보 카드**({@link IsaacSimStream}).
 *
 * 상태({@link WebRtcStatus})는 오직 실제 이벤트로만 바뀐다 — 사용자가 고르는 값이 아니다.
 * 판정 기준은 아래 두 가지이며, 이 순서를 뒤집지 않는다.
 *
 *   1. `connected` 는 **video 의 `playing` 이벤트**로만 만든다.
 *      connect() 성공(=시그널링/협상 성공)은 `connected` 가 아니다. 협상이 끝나도 프레임이
 *      한 장도 안 올 수 있고, 그때 "연결됨"으로 표시하면 검은 화면을 정상으로 읽게 된다.
 *   2. 끊김은 `disconnected`, 오류는 `failed` 로 나눈다. 둘 다 3초 뒤 자동 재연결하며
 *      재시도 중에는 `reconnecting` 이다.
 *
 * IP·포트는 전부 {@link ISAAC_SIM_CONFIG} 에서만 읽는다 — JSX 에 직접 쓰지 않는다.
 *
 * 네트워크 성격(자세한 내용은 lib/config/isaacSim.ts 주석 참고)
 *   signaling 49100 : WebRTC 시그널링(TCP). new WebSocket() 으로 직접 열지 않는다.
 *   media     47998 : 미디어 전송(UDP). iframe/fetch/주소창으로 접근하는 포트가 아니다.
 *   두 포트 모두 SDK(@nvidia/ov-web-rtc)가 DirectConfig 로 받아서 처리한다.
 */

const STATUS_TONE: Record<WebRtcStatus, { tone: string; dot: string }> = {
  idle: {
    tone: "border-slate-600/50 bg-slate-800/50 text-slate-300",
    dot: "bg-slate-400",
  },
  connecting: {
    tone: "border-sky-500/40 bg-sky-950/40 text-sky-100",
    dot: "bg-sky-400",
  },
  connected: {
    tone: "border-emerald-500/40 bg-emerald-950/40 text-emerald-100",
    dot: "bg-emerald-400",
  },
  reconnecting: {
    tone: "border-amber-500/40 bg-amber-950/40 text-amber-100",
    dot: "bg-amber-400",
  },
  disconnected: {
    tone: "border-slate-500/40 bg-slate-800/60 text-slate-200",
    dot: "bg-slate-400",
  },
  failed: {
    tone: "border-red-500/40 bg-red-950/40 text-red-100",
    dot: "bg-red-400",
  },
}

/** StreamEvent.info 는 string | Error 다. 화면에 그대로 못 쓰므로 문자열로 눌러 준다. */
function describe(info: StreamEvent["info"] | unknown): string {
  if (info instanceof Error) return info.message
  if (typeof info === "string") return info
  if (info && typeof info === "object" && "info" in info) {
    return describe((info as StreamEvent).info)
  }
  return String(info ?? "알 수 없는 오류")
}

/** video 에 붙어 있는 MediaStream 을 끊고 트랙까지 정지한다(언마운트/재연결 공통 정리). */
function releaseMediaStream(video: HTMLVideoElement | null) {
  if (!video) return
  const stream = video.srcObject
  if (stream instanceof MediaStream) {
    for (const track of stream.getTracks()) track.stop()
  }
  video.srcObject = null
}

export type UseIsaacSimStreamResult = {
  /** 현재 연결 상태. 실제 이벤트로만 바뀐다. */
  status: WebRtcStatus
  /** 실패/끊김 사유. 정상일 때는 null. */
  error: string | null
  /** <video>/<audio> 를 감싸는 컨테이너에 붙일 ref */
  containerRef: React.RefObject<HTMLDivElement | null>
  /** 재생 이벤트를 관찰할 video 요소 ref. {@link IsaacSimVideoSurface} 가 붙인다. */
  videoRef: React.RefObject<HTMLVideoElement | null>
  /** 재연결 타이머를 기다리지 않고 즉시 다시 시도한다(수동 "다시 연결" 버튼용). */
  reconnect: () => void
}

/**
 * Isaac Sim WebRTC 스트림에 연결한다.
 *
 * 마운트 즉시 연결을 시작한다 — 사용자가 누를 연결 버튼은 없다.
 * SDK 는 useEffect 안에서 동적 import 한다(서버 렌더 단계에서 WebRTC/DOM 을 만지지 않기 위해).
 *
 * 재연결은 `attempt` 카운터를 올려 effect 자체를 다시 돌리는 방식이다. cleanup 이 먼저
 * 완전히 정리(종료·리스너 해제·트랙 정지)한 뒤 새 연결이 시작되므로, 재시도가 겹쳐
 * AppStreamer 인스턴스가 둘 살아 있는 상태가 생기지 않는다.
 */
export function useIsaacSimStream(): UseIsaacSimStreamResult {
  const containerRef = useRef<HTMLDivElement | null>(null)
  const videoRef = useRef<HTMLVideoElement | null>(null)
  const [status, setStatus] = useState<WebRtcStatus>("idle")
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  const reconnect = useCallback(() => setAttempt((n) => n + 1), [])

  useEffect(() => {
    let disposed = false
    let streamer: import("@nvidia/ov-web-rtc").AppStreamer | null = null
    let retryTimer: ReturnType<typeof setTimeout> | null = null
    const video = videoRef.current

    // 최초 시도는 connecting, 끊긴 뒤 재시도는 reconnecting 으로 구분해서 보여 준다.
    setStatus(attempt === 0 ? "connecting" : "reconnecting")

    /** 3초 뒤 effect 를 다시 돌린다. 이미 예약돼 있으면 중복 예약하지 않는다. */
    const scheduleReconnect = () => {
      if (disposed || retryTimer) return
      retryTimer = setTimeout(() => {
        retryTimer = null
        if (!disposed) setAttempt((n) => n + 1)
      }, ISAAC_RECONNECT_DELAY_MS)
    }

    const markFailed = (reason: unknown) => {
      console.error("[IsaacSimStream] WebRTC 오류:", reason)
      if (disposed) return
      setStatus("failed")
      setError(describe(reason))
      scheduleReconnect()
    }

    const markDisconnected = (reason: unknown) => {
      if (disposed) return
      setStatus("disconnected")
      setError(describe(reason))
      scheduleReconnect()
    }

    // ── video 이벤트: connected 판정의 유일한 근거 ────────────────────────────
    const handlePlaying = () => {
      if (disposed) return
      setStatus("connected")
      setError(null)
    }
    // 재생이 멎었다 = 스트림이 끊겼다. 브라우저가 스스로 복구하지 않으므로 재연결한다.
    const handleEnded = () => markDisconnected("영상 스트림이 종료되었습니다.")
    const handleEmptied = () => {
      // srcObject 가 아직 붙기 전(연결 초기)에도 emptied 가 날 수 있어, 재생 중이던
      // 경우에만 끊김으로 본다.
      if (video && video.currentTime > 0) markDisconnected("영상 스트림이 끊겼습니다.")
    }
    const handleVideoError = () => markFailed(video?.error ?? "video element error")

    video?.addEventListener("playing", handlePlaying)
    video?.addEventListener("ended", handleEnded)
    video?.addEventListener("emptied", handleEmptied)
    video?.addEventListener("error", handleVideoError)

    void (async () => {
      try {
        const { AppStreamer, EventStatus, LogLevel, StreamType } = await import(
          "@nvidia/ov-web-rtc"
        )
        if (disposed) return

        streamer = new AppStreamer()

        const event = await streamer.connect({
          streamSource: StreamType.DIRECT,
          logLevel: LogLevel.WARN,
          streamConfig: {
            signalingServer: ISAAC_SIM_CONFIG.server,
            signalingPort: ISAAC_SIM_CONFIG.signalPort,
            mediaServer: ISAAC_SIM_CONFIG.server,
            mediaPort: ISAAC_SIM_CONFIG.mediaPort,
            videoElementId: ISAAC_VIDEO_ELEMENT_ID,
            audioElementId: ISAAC_AUDIO_ELEMENT_ID,
            // 영상 요소 크기에 스트림 해상도를 맞춘다(패널 크기가 고정이 아니다).
            fitStreamResolution: true,
            // 관제 화면이라 마이크는 열지 않는다.
            mic: false,
            /**
             * 여기서 status 를 connected 로 올리지 않는다 — 이 콜백은 "협상이 끝났다"까지만
             * 보장한다. 재생 시작은 위의 `playing` 리스너가 판정한다.
             */
            onStart: (message) => {
              if (disposed) return
              if (
                message.status === EventStatus.ERROR ||
                message.status === EventStatus.CANCELED
              ) {
                markFailed(message.info)
              }
            },
            onStop: (message) => markDisconnected(message.info),
            onTerminate: (message) => markDisconnected(message.info),
            onUpdate: (message) => {
              if (message.status === EventStatus.ERROR) {
                console.error("[IsaacSimStream] WebRTC 오류 이벤트:", message)
              }
            },
          },
        })

        if (disposed) return
        if (event.status === EventStatus.ERROR || event.status === EventStatus.CANCELED) {
          markFailed(event.info)
        }
        // SUCCESS 여도 여기서는 아무 상태도 만들지 않는다(위 주석 참고).
      } catch (caught) {
        // connect() 는 실패 시 Error 가 아니라 StreamEvent 를 throw 하기도 한다.
        markFailed(caught)
      }
    })()

    return () => {
      disposed = true
      if (retryTimer) clearTimeout(retryTimer)
      retryTimer = null

      video?.removeEventListener("playing", handlePlaying)
      video?.removeEventListener("ended", handleEnded)
      video?.removeEventListener("emptied", handleEmptied)
      video?.removeEventListener("error", handleVideoError)

      // terminateApp=false: 우리 화면이 닫힌다고 Isaac Sim 앱 자체를 내리지는 않는다.
      streamer?.terminate(false).catch((caught: unknown) => {
        console.error("[IsaacSimStream] WebRTC 종료 실패:", caught)
      })
      streamer = null

      releaseMediaStream(video)
    }
  }, [attempt])

  return { status, error, containerRef, videoRef, reconnect }
}

/**
 * Isaac Sim 스트림이 붙을 <video>/<audio> 요소.
 *
 * SDK 가 id 로 찾아 srcObject 를 붙이므로 **connect() 전에 DOM 에 있어야 한다**.
 * 따라서 연결 상태와 무관하게 항상 렌더하고, 보이기만 상태로 제어한다.
 */
export function IsaacSimVideoSurface({
  containerRef,
  videoRef,
  className,
}: {
  containerRef: React.RefObject<HTMLDivElement | null>
  videoRef: React.RefObject<HTMLVideoElement | null>
  className?: string
}) {
  return (
    <div ref={containerRef} className={cn("size-full", className)}>
      {/* muted: 자동재생 정책상 소리가 있으면 재생이 차단된다(=playing 이벤트도 안 온다). */}
      <video
        id={ISAAC_VIDEO_ELEMENT_ID}
        ref={videoRef}
        className="size-full object-contain"
        autoPlay
        playsInline
        muted
        tabIndex={-1}
      />
      <audio id={ISAAC_AUDIO_ELEMENT_ID} autoPlay />
    </div>
  )
}

/**
 * 접속 정보 + 현재 연결 상태 카드.
 *
 * 상태는 {@link useIsaacSimStream} 이 판정한 것을 그대로 받는다 — 카드가 스스로
 * "연결됨"을 만들어 내지 않는다.
 */
export function IsaacSimStream({
  status,
  error,
  className,
}: {
  status: WebRtcStatus
  error?: string | null
  className?: string
}) {
  const tone = STATUS_TONE[status]
  const label = WEBRTC_STATUS_TEXT[status].stream

  return (
    <section
      aria-labelledby="isaac-sim-stream-title"
      className={cn(
        "w-full max-w-sm rounded-lg border border-slate-700 bg-slate-900/85 p-3 shadow-lg backdrop-blur-sm",
        className,
      )}
      data-testid="isaac-sim-stream"
      data-status={status}
    >
      <div className="flex items-center justify-between gap-2">
        <h2
          id="isaac-sim-stream-title"
          className="flex items-center gap-1.5 text-xs font-semibold text-slate-100"
        >
          <Radio className="size-3.5 text-slate-400" aria-hidden="true" />
          Isaac Sim WebRTC
        </h2>
        <span
          className={cn(
            "inline-flex items-center gap-1.5 rounded border px-1.5 py-0.5 text-[10px] font-medium",
            tone.tone,
          )}
          aria-live="polite"
        >
          <span className={cn("size-1.5 rounded-full", tone.dot)} aria-hidden="true" />
          {label}
        </span>
      </div>

      <dl className="mt-2 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-[11px]">
        <dt className="text-slate-400">Server</dt>
        <dd className="truncate font-mono text-slate-200">{ISAAC_SIM_CONFIG.server}</dd>

        <dt className="text-slate-400">Signaling</dt>
        <dd className="font-mono text-slate-200">
          {ISAAC_SIM_CONFIG.signalPort}
          <span className="ml-1 text-slate-500">/TCP</span>
        </dd>

        <dt className="text-slate-400">Media</dt>
        <dd className="font-mono text-slate-200">
          {ISAAC_SIM_CONFIG.mediaPort}
          <span className="ml-1 text-slate-500">/UDP</span>
        </dd>
      </dl>

      {/* 실패/끊김 사유는 화면에도 남긴다 — 콘솔을 열지 않는 조작자도 원인을 봐야 한다. */}
      {(status === "failed" || status === "disconnected") && error ? (
        <p className="mt-2 flex items-start gap-1.5 rounded border border-red-500/30 bg-red-950/30 px-2 py-1.5 text-[10px] leading-snug text-red-100/90">
          <CircleAlert className="mt-px size-3 shrink-0 text-red-400" aria-hidden="true" />
          <span className="break-all">{error}</span>
        </p>
      ) : null}
    </section>
  )
}

export default IsaacSimStream
