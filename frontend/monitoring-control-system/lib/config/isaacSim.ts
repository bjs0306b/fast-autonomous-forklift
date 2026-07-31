// Isaac Sim WebRTC 연결 설정.
//
// IP·포트를 컴포넌트에 흩어 놓지 않기 위한 단일 출처다.
// 화면에서 이 값들이 필요하면 반드시 이 모듈을 import 한다.
//
// ─────────────────────────────────────────────────────────────────────────────
// 네트워크 구조
//
//   React(브라우저)  ──▶  <server>:49100      WebRTC signaling (TCP)
//   Isaac Sim        ──▶  React(브라우저)     <server>:47998/UDP  media stream
//
// 두 포트의 성격이 다르다는 점이 중요하다.
//
//   signalPort(49100)
//     WebRTC 시그널링용. SDP offer/answer 와 ICE candidate 를 교환한다.
//     ★ 일반 WebSocket 엔드포인트라고 가정하면 안 된다.
//        `new WebSocket("ws://<server>:49100")` 같은 직접 연결을 하지 않는다.
//        실제 연결은 Isaac Sim 이 제공하는 WebRTC 클라이언트(SDK)가 담당한다.
//
//   mediaPort(47998)
//     실제 영상이 흐르는 **UDP** 미디어 포트.
//     ★ 웹페이지도 REST API 도 아니다. 따라서 다음을 하지 않는다.
//        - <iframe src="http://<server>:47998" />
//        - fetch("http://<server>:47998")
//        - 브라우저 주소창에 직접 입력
//     이 포트는 WebRTC 협상이 끝난 뒤 피어 간 미디어 전송에만 쓰인다.
// ─────────────────────────────────────────────────────────────────────────────

/** 서버 주소 기본값. 환경변수(NEXT_PUBLIC_ISAAC_WEBRTC_SERVER)가 있으면 그 값이 우선한다. */
const DEFAULT_SERVER = "70.12.130.106"
/** WebRTC signaling 포트 기본값 (TCP) */
const DEFAULT_SIGNAL_PORT = 49100
/** 미디어 스트림 포트 기본값 (UDP) */
const DEFAULT_MEDIA_PORT = 47998

/**
 * 포트 문자열을 안전하게 숫자로 바꾼다.
 *
 * 잘못된 값(빈 문자열, 문자, 범위 밖, 소수)이면 예외를 던지지 않고 fallback 을 쓴다 —
 * 환경변수 오타 하나로 관제 화면 전체가 죽는 것보다, 기본값으로 뜨고 화면에 표시된 포트가
 * 예상과 다른 것을 사람이 보고 알아채는 편이 낫다.
 */
function parsePort(value: string | undefined, fallback: number): number {
  if (value === undefined || value.trim() === "") return fallback
  const parsed = Number(value)
  if (!Number.isInteger(parsed) || parsed <= 0 || parsed > 65535) {
    // 개발 중에만 경고한다(운영 번들에서는 이 분기가 제거된다).
    if (process.env.NODE_ENV === "development") {
      console.warn(`[isaacSim] 잘못된 포트 값 "${value}" — 기본값 ${fallback} 을 사용합니다.`)
    }
    return fallback
  }
  return parsed
}

function parseServer(value: string | undefined, fallback: string): string {
  const trimmed = value?.trim()
  return trimmed ? trimmed : fallback
}

/**
 * Isaac Sim WebRTC 접속 정보.
 *
 * NEXT_PUBLIC_* 이므로 **빌드 시점에 번들로 인라인**된다.
 *   - 값을 바꾸면 `npm run build` 를 다시 해야 한다.
 *   - 브라우저에 그대로 노출되므로 비밀값을 넣으면 안 된다.
 */
export const ISAAC_SIM_CONFIG = {
  /** Isaac Sim 서버 주소 */
  server: parseServer(process.env.NEXT_PUBLIC_ISAAC_WEBRTC_SERVER, DEFAULT_SERVER),
  /** WebRTC signaling 포트 (TCP). 일반 WebSocket 으로 직접 연결하지 않는다. */
  signalPort: parsePort(process.env.NEXT_PUBLIC_ISAAC_WEBRTC_SIGNAL_PORT, DEFAULT_SIGNAL_PORT),
  /** 미디어 스트림 포트 (UDP). 브라우저가 직접 접속하는 주소가 아니다. */
  mediaPort: parsePort(process.env.NEXT_PUBLIC_ISAAC_WEBRTC_MEDIA_PORT, DEFAULT_MEDIA_PORT),
} as const

/**
 * 끊김·실패 후 재연결까지 기다리는 시간(ms).
 *
 * 이 값만큼 기다렸다가 다시 connect() 한다. 실패해도 계속 재시도한다 — 관제 화면은
 * 사람이 새로고침해 주기를 기다릴 수 없고, Isaac Sim 이 뒤늦게 떠도 스스로 붙어야 한다.
 */
export const ISAAC_RECONNECT_DELAY_MS = 3000

/**
 * SDK 가 스트림을 붙일 DOM 요소의 id.
 *
 * ov-web-rtc 는 ref 가 아니라 **id 로 요소를 찾아** srcObject 를 붙인다
 * (DirectConfig.videoElementId / audioElementId). 따라서 connect() 이전에 이 id 를 가진
 * 요소가 DOM 에 있어야 하고, 한 페이지에 하나만 존재해야 한다.
 */
export const ISAAC_VIDEO_ELEMENT_ID = "isaac-sim-remote-video"
export const ISAAC_AUDIO_ELEMENT_ID = "isaac-sim-remote-audio"
