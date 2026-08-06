"use client"

import { MonitorPlay } from "lucide-react"
import type { WebRtcStatus } from "@/types/monitoring"
import { WEBRTC_STATUS_HEADLINE, WEBRTC_STATUS_TEXT } from "@/lib/config/webrtcStatusText"
import { IsaacSimStream, IsaacSimVideoSurface } from "./IsaacSimStream"

/**
 * DigitalTwinVideoLayer
 *
 * Isaac Sim 디지털 트윈 영상이 표시되는 레이어.
 *
 * 상태는 스스로 만들지 않고 상위(MonitoringPage 의 useIsaacSimStream)가 판정한 것을 받는다.
 * 이 컴포넌트가 하는 일은 **상태에 따라 무엇을 보여 줄지**뿐이다.
 *
 *   connected                  실영상만. placeholder·"대기 이미지" 표식·안내 문구 전부 제거.
 *   connecting / reconnecting  placeholder 위에 상위의 로딩 오버레이가 덮인다.
 *   idle / disconnected / failed  placeholder + 상태 문구.
 *
 * <b>샘플 스틸을 실시간 영상처럼 보이게 하지 않는다</b>(prompt67.md 7장).
 * 그래서 좌측 하단 표식이 상태에 따라 "실시간 영상 / 대기 영상 / 대기 이미지"로 바뀐다 —
 * connected 가 아닌 동안에는 지금 보이는 게 실영상이 아니라는 사실을 항상 남긴다.
 *
 * iframe(Isaac Sim MediaMTX 스트림, IsaacSimStream.tsx 참고)은 상태와 무관하게 **항상
 * 렌더한다** — useIsaacSimStream 의 effect 가 마운트 시점에 ref 로 src 를 대입하므로,
 * connect 시점에 DOM 에 없으면 연결 자체가 실패한다. 보이기만 opacity 로 제어한다.
 */
export function DigitalTwinVideoLayer({
  status,
  error = null,
  containerRef,
  videoRef,
}: {
  status: WebRtcStatus
  error?: string | null
  containerRef: React.RefObject<HTMLDivElement | null>
  videoRef: React.RefObject<HTMLIFrameElement | null>
}) {
  const live = status === "connected"
  // connecting/reconnecting 은 로딩 오버레이가, failed 는 실패 오버레이가 상위
  // (MainRealtimeMonitoringView)에서 덮으므로 같은 안내를 중복해서 띄우지 않는다.
  const showIdleNotice = status === "idle" || status === "disconnected"

  return (
    <div className="absolute inset-0 overflow-hidden bg-[#0b1220]">
      {/* Isaac Sim 실영상. 연결 전에는 자리만 잡고 보이지 않는다. */}
      <IsaacSimVideoSurface
        containerRef={containerRef}
        videoRef={videoRef}
        className={live ? "absolute inset-0" : "absolute inset-0 opacity-0"}
      />

      {/* 샘플 디지털 트윈 스틸 (실제 스트림 아님 — 실영상이 붙기 전까지만, 항상 어둡게 둔다) */}
      {!live ? (
        <>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src="/images/digital-twin-sample.png"
            alt="디지털 트윈 샘플 영상 스틸 (실제 스트림 연결 전 placeholder)"
            className="size-full object-cover opacity-[0.28]"
            draggable={false}
          />

          {/* 안내 문구를 못 본 사람도 실영상으로 오해하지 않게 한다. */}
          <span
            className="pointer-events-none absolute bottom-3 left-3 rounded bg-slate-900/80 px-2 py-1 text-[10px] font-medium text-slate-300 ring-1 ring-white/10"
            data-testid="video-media-badge"
          >
            {WEBRTC_STATUS_TEXT[status].media} · 실시간 영상 아님
          </span>
        </>
      ) : (
        <span
          className="pointer-events-none absolute bottom-3 left-3 rounded bg-emerald-950/80 px-2 py-1 text-[10px] font-medium text-emerald-100 ring-1 ring-emerald-400/30"
          data-testid="video-media-badge"
        >
          {WEBRTC_STATUS_TEXT.connected.media}
        </span>
      )}

      {/* 미연결 안내 – 화면 중앙.
          Isaac Sim WebRTC 접속 정보를 여기에 함께 보여준다(prompt76 6장) — "스트림이 왜 없는지"를
          알려야 할 자리가 여기 하나뿐이라, 별도 패널을 만들어 같은 메시지를 두 곳에 두지 않는다. */}
      {showIdleNotice ? (
        <div className="absolute inset-0 flex items-center justify-center p-4">
          <div className="flex max-w-sm flex-col items-center gap-3 text-center">
            <div className="pointer-events-none flex flex-col items-center gap-2">
              <div className="flex size-12 items-center justify-center rounded-full bg-white/5 ring-1 ring-white/10">
                <MonitorPlay className="size-6 text-slate-300" aria-hidden="true" />
              </div>
              <p className="text-sm font-medium text-slate-100">
                {WEBRTC_STATUS_HEADLINE[status]}
              </p>
              <p className="text-xs text-pretty text-slate-400">
                {status === "idle"
                  ? "Isaac Sim 스트림이 연결되면 이 영역에 실시간 3D 영상이 표시됩니다."
                  : "3초 뒤 자동으로 다시 연결합니다."}
              </p>
            </div>
            <IsaacSimStream status={status} error={error} className="text-left" />
          </div>
        </div>
      ) : null}
    </div>
  )
}

export default DigitalTwinVideoLayer
