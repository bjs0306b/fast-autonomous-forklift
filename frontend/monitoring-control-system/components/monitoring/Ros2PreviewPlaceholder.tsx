"use client"

import { Radar } from "lucide-react"
import { cn } from "@/lib/utils"

/**
 * Ros2PreviewPlaceholder
 *
 * <b>더 이상 어디에서도 렌더링되지 않는다(dead code).</b> AI 측정 영상은 실제 스트림을 그리는
 * {@code AiMeasurementVideo} 가 담당하고, 배치는 {@code MainRealtimeMonitoringView} 의
 * pip 슬롯(영상 컨테이너 안쪽 오른쪽 위)이다. 이 파일은 다음 정리 때 지우면 된다.
 *
 * 메인 디지털 트윈 영상 위에 겹치는 AI 측정 영상 영역의 **자리 확인용 placeholder**였다.
 *
 * <b>데이터를 전혀 구독하지 않는다.</b> ROS2·MQTT·WebSocket 연결 코드가 없고, 그럴듯해 보이는
 * mock 좌표나 센서 값도 만들지 않는다 — 이 단계의 목적은 "이 패널이 화면에서 얼마나 차지하는가"
 * 하나뿐이라, 가짜 데이터를 넣으면 크기 판단이 아니라 데이터 검토를 하게 된다.
 *
 * 그래서 상태 배지도 항상 "대기" 고정이다. props 로 상태를 받게 열어 두면 상위가 임의의 값을
 * 넘기기 시작하고, 연결되지도 않은 채 "연결됨"이 뜨는 상태가 만들어진다.
 *
 * 배치는 {@code MainRealtimeMonitoringView} 의 우상단 세로 스택 안이다 — 별도 absolute 레이어로
 * 띄우면 기존 연결 배지·선택 차량 오버레이와 좌표가 겹친다(자세한 이유는 그쪽 주석 참고).
 */
export function Ros2PreviewPlaceholder({ className }: { className?: string }) {
  return (
    <section
      className={cn(
        // 1920×1080 에서 260×146px. 요청받은 clamp(210px,18vw,280px) 를 그대로 쓰지 않은 이유:
        // 18vw 는 1920 에서 345px 라 항상 상한 280px 에 붙어 버려 목표치 260px 이 나오지 않고,
        // 1600 에서도 288px 로 역시 상한에 걸려 사실상 "항상 280px 고정"이 된다.
        // 기울기를 낮춰 요청한 세 구간을 모두 만족시킨다 — 1920→260, 1600→234, 1366→216.
        // lg 미만(=메인 영상이 한 칸으로 접히는 구간)에서는 180px 로 줄여 영상을 덜 가린다.
        "w-[180px] lg:w-[clamp(210px,calc(106px+8vw),280px)]",
        "aspect-video shrink-0 overflow-hidden rounded-md",
        // 기존 오버레이(SelectedVehicleOverlay)와 같은 어두운 반투명 + 얇은 링 + 그림자 조합.
        "bg-slate-900/80 shadow-lg ring-1 ring-white/10 backdrop-blur-sm",
        // 영상 위 장식일 뿐이라 클릭을 가로채지 않는다.
        "pointer-events-none",
        className,
      )}
      aria-label="AI 측정 영상 영역 (연결 대기)"
      data-testid="ros2-preview-placeholder"
    >
      <div className="flex h-full flex-col p-2">
        {/* 제목 + 상태 배지 */}
        <div className="flex items-center justify-between gap-1.5">
          <span className="truncate text-[11px] font-semibold text-slate-100">AI 측정 영상</span>
          <span className="shrink-0 rounded bg-white/5 px-1.5 py-px text-[9px] font-medium text-slate-300 ring-1 ring-white/10">
            대기
          </span>
        </div>

        {/* 본문 — 빈 검은 사각형으로 두지 않고 무엇이 들어올 자리인지 남긴다. */}
        <div className="flex min-h-0 flex-1 flex-col items-center justify-center gap-1 text-center">
          <Radar className="size-4 shrink-0 text-slate-500" aria-hidden="true" />
          <p className="truncate text-[11px] text-slate-300">AI 측정 영상 연결 대기</p>
          <p className="truncate text-[10px] text-slate-500">화물·팔레트 측정 카메라</p>
        </div>
      </div>
    </section>
  )
}

export default Ros2PreviewPlaceholder
