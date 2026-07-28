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
 * TODO(video): 스트림 프로토콜 확정 시 실제 <video> / <img> / <iframe> 등을 연결한다.
 */
export function DigitalTwinVideoLayer({ status }: { status: StreamConnectionStatus }) {
  // connected 뉘앙스일 때만 샘플 스틸을 또렷하게, 그 외에는 어둡게 처리한다.
  const dimmed = status !== "connected"

  return (
    <div className="absolute inset-0 overflow-hidden bg-[#0b1220]">
      {/* 샘플 디지털 트윈 스틸 (실제 스트림 아님) */}
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        src="/images/digital-twin-sample.png"
        alt="디지털 트윈 샘플 영상 스틸 (실제 스트림 연결 전 placeholder)"
        className="size-full object-cover"
        style={{ opacity: dimmed ? 0.28 : 0.9 }}
        draggable={false}
      />

      {/* 미연결(idle) 안내 – 화면 중앙 */}
      {status === "idle" ? (
        <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
          <div className="flex flex-col items-center gap-2 text-center">
            <div className="flex size-12 items-center justify-center rounded-full bg-white/5 ring-1 ring-white/10">
              <MonitorPlay className="size-6 text-slate-300" aria-hidden="true" />
            </div>
            <p className="text-sm font-medium text-slate-100">디지털 트윈 영상 미연결</p>
            <p className="max-w-xs text-xs text-slate-400 text-pretty">
              Isaac Sim 스트림이 연결되면 이 영역에 실시간 3D 영상이 표시됩니다.
            </p>
          </div>
        </div>
      ) : null}
    </div>
  )
}

export default DigitalTwinVideoLayer
