import { MonitorPlay } from "lucide-react"
import type { StreamConnectionStatus } from "@/types/monitoring"

/**
 * DigitalTwinVideoLayer (placeholder)
 *
 * 실제 Isaac Sim / 디지털 트윈 영상 스트림이 표시될 자리.
 * 스트림 방식(WebRTC / HLS / MJPEG / iframe / WebSocket)이 확정되지 않았으므로
 * 실제 스트림은 연결하지 않는다. 지금은 샘플 스틸 이미지 + 안내 문구만 표시한다.
 *
 * 방식 확정 후 이 컴포넌트 내부의 placeholder 를 실제 영상 요소로 교체하고,
 * MainRealtimeMonitoringView 는 그대로 이 레이어를 표시한다.
 *
 * <b>샘플 스틸을 실시간 영상처럼 보이게 하지 않는다</b>(prompt67.md 7장).
 * 이전에는 status 가 "connected" 일 때 샘플을 opacity 0.9 로 또렷하게 띄우고 안내 문구도 감췄다 —
 * 실제 스트림이 없는데 정지된 실영상처럼 읽힐 수 있었다. 실스트림이 붙기 전까지는 어떤 status 에서도
 * 샘플을 어둡게 유지하고 "미연결" 표식을 함께 남긴다.
 *
 * TODO(video): 스트림 프로토콜 확정 시 실제 <video> / <img> / <iframe> 등을 연결한다.
 * 그때 이 컴포넌트가 status="connected" 에서 실제 영상 요소를 렌더하도록 교체한다.
 */
export function DigitalTwinVideoLayer({ status }: { status: StreamConnectionStatus }) {
  // connecting/error 는 상위(MainRealtimeMonitoringView)가 전용 오버레이를 덮으므로 안내를 중복하지 않는다.
  const showIdleNotice = status === "idle" || status === "connected"

  return (
    <div className="absolute inset-0 overflow-hidden bg-[#0b1220]">
      {/* 샘플 디지털 트윈 스틸 (실제 스트림 아님 — 실스트림 연결 전까지 항상 어둡게 둔다) */}
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        src="/images/digital-twin-sample.png"
        alt="디지털 트윈 샘플 영상 스틸 (실제 스트림 연결 전 placeholder)"
        className="size-full object-cover opacity-[0.28]"
        draggable={false}
      />

      {/* 샘플임을 상시 표기한다 — 안내 문구를 못 본 사람도 실영상으로 오해하지 않게 한다. */}
      <span className="pointer-events-none absolute bottom-3 left-3 rounded bg-slate-900/80 px-2 py-1 text-[10px] font-medium text-slate-300 ring-1 ring-white/10">
        샘플 이미지 · 실시간 영상 아님
      </span>

      {/* 미연결 안내 – 화면 중앙 */}
      {showIdleNotice ? (
        <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
          <div className="flex flex-col items-center gap-2 text-center">
            <div className="flex size-12 items-center justify-center rounded-full bg-white/5 ring-1 ring-white/10">
              <MonitorPlay className="size-6 text-slate-300" aria-hidden="true" />
            </div>
            <p className="text-sm font-medium text-slate-100">디지털 트윈 스트림 미연결</p>
            <p className="max-w-xs text-xs text-pretty text-slate-400">
              Isaac Sim 스트림이 연결되면 이 영역에 실시간 3D 영상이 표시됩니다.
            </p>
          </div>
        </div>
      ) : null}
    </div>
  )
}

export default DigitalTwinVideoLayer
