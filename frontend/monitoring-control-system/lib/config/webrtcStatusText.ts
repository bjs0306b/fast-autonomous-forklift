import type { WebRtcStatus } from "@/types/monitoring"

/**
 * WebRTC 상태 문구의 단일 출처.
 *
 * 같은 상태를 화면 세 곳(상단 배지 · 중앙 문구 · 좌측 하단 표식)에서 보여 주는데,
 * 문구를 각 컴포넌트에 흩어 놓으면 한 곳만 고쳐져 "영상 연결됨" 배지 아래에
 * "연결 대기" 문구가 뜨는 식으로 어긋난다. 그래서 여기 한 곳에만 둔다.
 *
 * 세 축의 의미가 다르다.
 *   primary : 연결 자체의 상태  (상단 배지)
 *   stream  : 영상 스트림의 상태 (상단 배지 · 중앙 문구)
 *   media   : 지금 화면에 무엇이 보이는지 (좌측 하단 표식)
 *
 * 특히 `media` 는 "지금 보고 있는 게 실영상인가"를 답하는 축이다. connected 가 아닌 동안
 * 실시간 영상처럼 읽히지 않게 하는 것이 목적이라 문구를 임의로 완화하지 않는다.
 */
export const WEBRTC_STATUS_TEXT: Record<
  WebRtcStatus,
  { primary: string; stream: string; media: string }
> = {
  idle: { primary: "연결 대기", stream: "영상 연결 대기", media: "대기 이미지" },
  connecting: { primary: "연결 시도 중", stream: "영상 연결 중", media: "대기 영상" },
  connected: { primary: "실시간 연결", stream: "영상 연결됨", media: "실시간 영상" },
  reconnecting: { primary: "재연결 중", stream: "영상 복구 중", media: "대기 영상" },
  disconnected: { primary: "실시간 연결 끊김", stream: "영상 미연결", media: "대기 이미지" },
  failed: { primary: "연결 실패", stream: "영상 연결 실패", media: "대기 이미지" },
}

/**
 * 상태별 대표 문구(중앙 영역).
 *
 * prompt80 5항이 지정한 문구를 그대로 쓴다. `stream` 축과 일부러 다르다 —
 * 중앙은 "지금 무슨 일이 일어나는지"를 문장으로 알리는 자리다.
 */
export const WEBRTC_STATUS_HEADLINE: Record<WebRtcStatus, string> = {
  idle: "영상 연결 대기",
  connecting: "영상 연결 중",
  connected: "영상 연결됨",
  reconnecting: "영상 재연결 중",
  disconnected: "영상 연결 끊김",
  failed: "영상 연결 실패",
}
