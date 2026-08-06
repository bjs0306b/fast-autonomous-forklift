"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { CircleAlert, Radio } from "lucide-react"
import { cn } from "@/lib/utils"
import {
  ISAAC_SIM_STREAM_CONFIG,
  ISAAC_SIM_STREAM_LOAD_TIMEOUT_MS,
  ISAAC_SIM_STREAM_PROBE_TIMEOUT_MS,
  ISAAC_SIM_STREAM_RETRY_DELAY_MS,
  isStreamUrlConfigured,
} from "@/lib/config/isaacSimStream"
import { WEBRTC_STATUS_TEXT } from "@/lib/config/webrtcStatusText"
import type { WebRtcStatus } from "@/types/monitoring"

/**
 * IsaacSimStream
 *
 * Isaac Sim "화면" 스트림의 **연결 담당 훅**({@link useIsaacSimStream}), **화면 요소**
 * ({@link IsaacSimVideoSurface}), **접속 정보 카드**({@link IsaacSimStream}).
 *
 * ── 파이프라인이 바뀌었다 (기존 Omniverse WebRTC SDK 직접 연결 → MediaMTX iframe) ──────────
 *
 *   로컬 Ubuntu Isaac Sim 화면 → xdotool → ffmpeg 캡처 → RTSP → EC2 MediaMTX
 *     → MediaMTX WebRTC 재생 페이지(HTTP) → 이 페이지를 <iframe> 으로 그대로 띄운다
 *
 * 예전에는 `@nvidia/ov-web-rtc` SDK 가 시그널링(TCP)·미디어(UDP) 포트에 직접 붙어 <video> 에
 * MediaStream 을 꽂았다(그 설정은 lib/config/isaacSim.ts 에 남아 있지만 이 파일은 더 이상
 * 쓰지 않는다). 지금은 MediaMTX 가 이미 만들어 주는 재생 페이지를 통째로 iframe 에 넣는다 —
 * 브라우저가 RTSP 를 직접 재생할 수 없고, WebRTC 협상은 그 페이지 안의 JS 가 대신 하기 때문이다.
 *
 * ── `connected` 판정 기준이 예전과 다르다는 것을 반드시 알아야 한다 ─────────────────────────
 *
 * 예전 판정 기준(video 의 `playing` 이벤트)은 "프레임이 실제로 그려졌다"는 강한 근거였다.
 * iframe 은 다른 오리진 문서라 부모 페이지가 그 안의 <video> 재생 상태를 들여다볼 수 없고,
 * 브라우저가 안정적으로 주는 신호는 **`load` 이벤트(=HTML 문서가 응답했다)뿐**이다.
 * 즉 지금의 `connected` 는 "MediaMTX 페이지가 로드됐다"는 뜻이지 "영상이 실제로 재생 중이다"는
 * 뜻이 아니다 — 협상까지 끝났지만 프레임이 안 올 수도 있다. 이 훅은 그 사실을 숨기지 않는다
 * ({@link WEBRTC_STATUS_TEXT}.connected 문구가 "실시간 영상"이라고 말하는 것과는 별개로,
 * 콘솔에는 항상 이 한계를 명시한 로그를 남긴다 — 화면 문구 자체는 기존 관제 화면을 그대로
 * 재사용하라는 요구사항 때문에 바꾸지 않았다).
 *
 * **`load` 단독으로는 더 심각한 오판도 생긴다(실측 확인, 2026-08-06).** 목적지(EC2 MediaMTX)가
 * 아예 접속 불가(포트 안 열림·서버 다운)여도, 브라우저는 자기 자신의 "이 사이트에 연결할 수
 * 없습니다" 오류 문서를 iframe 에 그려 넣고 그 문서 역시 `load` 를 정상 발생시킨다. 그래서
 * iframe 을 붙이기 **전에** `fetch(url, { mode: "no-cors" })` 로 실제 네트워크 도달성부터
 * 확인한다({@link ISAAC_SIM_STREAM_PROBE_TIMEOUT_MS}) — CORS 는 응답 판독을 막을 뿐 연결
 * 성공/실패 자체(프라미스 resolve/reject)는 그대로 신뢰할 수 있다. 사전 확인이 실패하면
 * iframe 을 아예 붙이지 않고 바로 `failed` 로 표시한다.
 *
 * 같은 이유로 `disconnected` 는 사실상 도달하지 않는다 — 한 번 `load` 된 뒤 스트림이 내부적으로
 * 끊겨도 iframe 문서 자체는 그대로 "로드된 상태"라 부모가 알아챌 방법이 없다. 실제로 영상이
 * 끊겼는지는 화면을 보는 사람이 판단하거나, MediaMTX 로그로 확인해야 한다(수동 확인 항목).
 *
 * IP·URL 은 전부 {@link ISAAC_SIM_STREAM_CONFIG} 에서만 읽는다 — JSX 에 직접 쓰지 않는다.
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

export type UseIsaacSimStreamResult = {
  /** 현재 연결 상태. 위 파일 주석의 "connected 판정 기준" 한계를 반드시 함께 읽을 것. */
  status: WebRtcStatus
  /** 실패/끊김 사유. 정상일 때는 null. */
  error: string | null
  /** iframe 을 감싸는 컨테이너에 붙일 ref. */
  containerRef: React.RefObject<HTMLDivElement | null>
  /** 상태를 관찰할 iframe 요소 ref. {@link IsaacSimVideoSurface} 가 붙인다. */
  videoRef: React.RefObject<HTMLIFrameElement | null>
  /** 재연결 타이머를 기다리지 않고 즉시 다시 시도한다(수동 "다시 연결" 버튼용). */
  reconnect: () => void
}

/**
 * Isaac Sim MediaMTX 스트림 페이지에 연결한다.
 *
 * 마운트 즉시 연결을 시작한다 — 사용자가 누를 연결 버튼은 없다(기존 동작과 동일).
 *
 * 판정 규칙:
 *   1. 스트림이 비활성화됐거나({@link ISAAC_SIM_STREAM_CONFIG}.enabled === false) URL 이
 *      비어 있으면 `idle` 로 고정한다. 재시도하지 않는다 — 설정이 없는데 계속 재시도해 봐야
 *      매번 같은 이유로 실패한다.
 *   2. 그 외에는 먼저 `fetch(url, { mode: "no-cors" })` 로 네트워크 도달성을 확인한다
 *      ({@link ISAAC_SIM_STREAM_PROBE_TIMEOUT_MS}). 여기서 실패(거부/타임아웃)하면 iframe 을
 *      아예 붙이지 않고 바로 `failed` 로 표시한다 — 목적지가 죽어 있을 때 iframe 의 `load` 가
 *      오판을 일으키는 문제(위 파일 주석 참고)를 이 단계에서 미리 걸러낸다.
 *   3. 사전 확인이 성공하면 그때 iframe 을 붙이고 `load` 이벤트를 기다린다
 *      ({@link ISAAC_SIM_STREAM_LOAD_TIMEOUT_MS}). 제 시간 안에 오면 `connected`, 못 오거나
 *      iframe 이 `error` 를 내면 `failed` 로 표시한다.
 *   4. 2, 3 어느 단계에서 실패하든 {@link ISAAC_SIM_STREAM_RETRY_DELAY_MS} 뒤 자동 재시도한다.
 *
 * 재연결은 `attempt` 카운터를 올려 effect 자체를 다시 돌리는 방식이다(기존 Omniverse 훅과
 * 동일한 패턴). 재시도(attempt > 0)마다 `iframe.src` 에 캐시 무력화 쿼리를 붙여 다시 대입한다 —
 * 같은 URL 을 그대로 다시 대입하면 브라우저가 캐시된 문서로 보고 재요청을 건너뛸 수 있다.
 */
export function useIsaacSimStream(): UseIsaacSimStreamResult {
  const containerRef = useRef<HTMLDivElement | null>(null)
  const videoRef = useRef<HTMLIFrameElement | null>(null)
  const [status, setStatus] = useState<WebRtcStatus>("idle")
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  const reconnect = useCallback(() => setAttempt((n) => n + 1), [])

  useEffect(() => {
    const { url, enabled } = ISAAC_SIM_STREAM_CONFIG
    const configured = enabled && isStreamUrlConfigured(url)

    if (!configured) {
      // 설정이 없으면 재시도할 이유도 없다 — 계속 idle 로 둔다.
      setStatus("idle")
      setError(null)
      if (process.env.NODE_ENV === "development") {
        console.debug(
          enabled
            ? "[IsaacSimStream] NEXT_PUBLIC_ISAAC_SIM_STREAM_URL 이 비어 있어 연결을 시도하지 않습니다."
            : "[IsaacSimStream] NEXT_PUBLIC_ISAAC_SIM_STREAM_ENABLED=false — 스트림이 비활성화되어 있습니다.",
        )
      }
      return
    }

    let disposed = false
    let settled = false
    let retryTimer: ReturnType<typeof setTimeout> | null = null
    let loadTimeoutTimer: ReturnType<typeof setTimeout> | null = null
    let probeController: AbortController | null = null
    const iframe = videoRef.current

    // 최초 시도는 connecting, 끊긴 뒤 재시도는 reconnecting 으로 구분해서 보여 준다(기존 규칙과 동일).
    setStatus(attempt === 0 ? "connecting" : "reconnecting")
    setError(null)

    const scheduleReconnect = () => {
      if (disposed || retryTimer) return
      retryTimer = setTimeout(() => {
        retryTimer = null
        if (!disposed) setAttempt((n) => n + 1)
      }, ISAAC_SIM_STREAM_RETRY_DELAY_MS)
    }

    const markFailed = (reason: string) => {
      if (disposed || settled) return
      settled = true
      console.error("[IsaacSimStream] MediaMTX 스트림 연결 실패:", reason, "- url:", url)
      setStatus("failed")
      setError(reason)
      scheduleReconnect()
    }

    const markConnected = () => {
      if (disposed || settled) return
      settled = true
      // 아래 주석은 위 파일 상단 "connected 판정 기준이 예전과 다르다" 섹션과 짝이다.
      console.info(
        "[IsaacSimStream] MediaMTX 페이지 로드 완료 — 이 시점의 'connected' 는 페이지 응답",
        "확인일 뿐 실제 영상 재생 성공을 보장하지 않습니다. 실 재생 여부는 화면을 직접 확인하세요.",
      )
      if (loadTimeoutTimer) {
        clearTimeout(loadTimeoutTimer)
        loadTimeoutTimer = null
      }
      setStatus("connected")
      setError(null)
    }

    const handleLoad = () => markConnected()
    // 대부분의 브라우저는 iframe 의 네트워크 오류를 이 이벤트로 안정적으로 주지 않는다(§7 참고).
    // 그래도 오는 경우가 있으니 받되, 이것만 믿고 타임아웃을 없애지 않는다.
    const handleError = () => markFailed("iframe 로드 중 오류가 발생했습니다.")

    /**
     * 사전 확인을 통과한 뒤에만 iframe 을 실제로 붙인다.
     *
     * src 는 JSX 가 아니라 여기서 직접 대입한다. 같은 URL 로 `src` 프로퍼티만 다시 대입하면
     * 브라우저가 캐시된 문서로 보고 재요청을 건너뛸 수 있어, 재시도(attempt > 0)에는 캐시
     * 무력화용 쿼리를 붙여 매번 새 요청을 강제한다.
     */
    const attachIframe = () => {
      if (disposed || !iframe) return
      iframe.addEventListener("load", handleLoad)
      iframe.addEventListener("error", handleError)
      loadTimeoutTimer = setTimeout(() => {
        loadTimeoutTimer = null
        markFailed(
          `MediaMTX 스트림 페이지가 ${ISAAC_SIM_STREAM_LOAD_TIMEOUT_MS / 1000}초 안에 응답하지 않았습니다.`,
        )
      }, ISAAC_SIM_STREAM_LOAD_TIMEOUT_MS)
      iframe.src = attempt === 0 ? url : `${url}${url.includes("?") ? "&" : "?"}reconnect=${attempt}`
    }

    /**
     * iframe 을 붙이기 전에 실제 네트워크 도달성부터 확인한다(파일 상단 "load 단독으로는
     * 더 심각한 오판도 생긴다" 주석 참고). `no-cors` 라 응답은 읽을 수 없지만, 연결 자체가
     * 실패하면(DNS 실패·connection refused 등) fetch 프라미스가 reject 되는 것은 CORS 와
     * 무관하게 신뢰할 수 있다.
     */
    const probeReachability = async () => {
      probeController = new AbortController()
      const timeoutId = setTimeout(() => probeController?.abort(), ISAAC_SIM_STREAM_PROBE_TIMEOUT_MS)
      try {
        await fetch(url, { mode: "no-cors", cache: "no-store", signal: probeController.signal })
        clearTimeout(timeoutId)
        if (disposed) return
        attachIframe()
      } catch (caught) {
        clearTimeout(timeoutId)
        if (disposed) return
        const timedOut = caught instanceof DOMException && caught.name === "AbortError"
        markFailed(
          timedOut
            ? `MediaMTX 서버가 ${ISAAC_SIM_STREAM_PROBE_TIMEOUT_MS / 1000}초 안에 응답하지 않았습니다.`
            : "MediaMTX 서버에 연결할 수 없습니다(네트워크 사전 확인 실패).",
        )
      }
    }

    void probeReachability()

    return () => {
      disposed = true
      probeController?.abort()
      if (retryTimer) clearTimeout(retryTimer)
      if (loadTimeoutTimer) clearTimeout(loadTimeoutTimer)
      iframe?.removeEventListener("load", handleLoad)
      iframe?.removeEventListener("error", handleError)
    }
  }, [attempt])

  return { status, error, containerRef, videoRef, reconnect }
}

/**
 * Isaac Sim MediaMTX 스트림이 붙을 iframe 요소.
 *
 * 기존 `IsaacSimVideoSurface`(video/audio 태그) 자리를 그대로 대체한다 — 호출부
 * (DigitalTwinVideoLayer)의 레이아웃·className·props 전달 방식은 바꾸지 않았다(ref 대상
 * 타입만 HTMLVideoElement → HTMLIFrameElement 로 바뀐다).
 *
 * `src` 는 여기서 정적으로 주지 않는다 — {@link useIsaacSimStream} 의 effect 가 마운트 직후
 * (그리고 재시도마다) `videoRef.current.src` 를 직접 대입한다. 설정이 없을 때 iframe 자체를
 * 렌더하지 않는 것도 그 훅과 짝을 이룬다(비활성 상태에서는 굳이 about:blank 요청을 만들지 않음).
 */
export function IsaacSimVideoSurface({
  containerRef,
  videoRef,
  className,
}: {
  containerRef: React.RefObject<HTMLDivElement | null>
  videoRef: React.RefObject<HTMLIFrameElement | null>
  className?: string
}) {
  const { url, enabled } = ISAAC_SIM_STREAM_CONFIG
  if (!enabled || !isStreamUrlConfigured(url)) {
    return <div ref={containerRef} className={cn("size-full", className)} />
  }
  return (
    <div ref={containerRef} className={cn("size-full", className)}>
      <iframe
        ref={videoRef}
        title="Isaac Sim 실시간 스트림"
        allow="autoplay; fullscreen"
        className="size-full border-0"
      />
    </div>
  )
}

/**
 * 접속 정보 + 현재 연결 상태 카드.
 *
 * 상태는 {@link useIsaacSimStream} 이 판정한 것을 그대로 받는다 — 카드가 스스로
 * "연결됨"을 만들어 내지 않는다. 예전에는 Server/Signaling/Media 포트를 보여 줬지만,
 * MediaMTX 파이프라인에는 그 개념이 없어(iframe 하나가 전부다) 재생 페이지 URL 하나만 보여준다.
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
          Isaac Sim Stream
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
        <dt className="text-slate-400">MediaMTX URL</dt>
        <dd className="truncate font-mono text-slate-200">{ISAAC_SIM_STREAM_CONFIG.url}</dd>
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
