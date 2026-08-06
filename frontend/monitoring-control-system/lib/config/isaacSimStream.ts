// Isaac Sim "화면" 스트리밍(MediaMTX WebRTC) 설정.
//
// lib/config/isaacSim.ts(Omniverse 자체 WebRTC SDK로 포트 49100/47998 에 직접 연결하는 기존
// 파이프라인)와는 **완전히 다른 별도 경로**다. 이 모듈이 다루는 스트림의 흐름은 다음과 같다.
//
//   로컬 Ubuntu Isaac Sim 창 화면
//     → xdotool 로 창 영역 탐색
//     → ffmpeg 화면 캡처
//     → RTSP 송출 (rtsp://i15a304.p.ssafy.io:8554/sim)
//     → EC2 MediaMTX
//     → MediaMTX 가 서빙하는 WebRTC "재생 페이지" (http://i15a304.p.ssafy.io:8889/sim)
//     → 프론트가 그 페이지를 <iframe> 으로 그대로 띄운다
//
// 브라우저는 RTSP 를 직접 재생할 수 없고, WebRTC 협상은 MediaMTX 페이지 안의 JS 가 한다.
// 그래서 프론트는 RTSP 주소를 절대 쓰지 않고, 반드시 8889 의 HTTP 재생 페이지 URL만 iframe 에 넣는다.
//
// NEXT_PUBLIC_* 이므로 lib/config/isaacSim.ts 와 동일하게 **빌드 시점**에 번들에 박힌다.
//   - 값을 바꾸면 `npm run build` 를 다시 해야 한다(dev 서버는 재시작만으로 반영될 수 있다).
//   - 브라우저에 그대로 노출되므로 비밀값(RTSP 인증정보 등)을 넣지 않는다.

const DEFAULT_STREAM_URL = "http://i15a304.p.ssafy.io:8889/sim"

/**
 * URL 환경변수를 정리한다. 형식(스킴·호스트)을 검증하지 않는다 — 여기서 정규식으로 막으면
 * 유효한 사설 IP/포트 조합을 오탐할 위험이 있고, 실제로 잘못된 URL인지는 브라우저가 iframe
 * 로드 실패로 더 정확하게 알려준다. 값이 비어 있을 때만 기본값으로 대체한다.
 */
export function parseStreamUrl(value: string | undefined): string {
  const trimmed = value?.trim()
  return trimmed ? trimmed : DEFAULT_STREAM_URL
}

/**
 * "false" 일 때만 끈다. 값이 없거나 오타(예: "flase")면 켜진 것으로 본다 — 운영 기본값이
 * 켜짐이므로, 오타로 스트림이 조용히 꺼지는 쪽보다 켜진 채로 남아 "연결 실패"로 드러나는
 * 쪽이 원인을 알아채기 쉽다.
 */
export function parseStreamEnabled(value: string | undefined): boolean {
  return value?.trim().toLowerCase() !== "false"
}

/**
 * 이 URL 이 iframe 에 넣어도 되는 값인지. 빈 문자열/공백만 걸러낸다.
 * (형식 검증을 하지 않는 이유는 {@link parseStreamUrl} 주석 참고.)
 */
export function isStreamUrlConfigured(url: string | null | undefined): boolean {
  return typeof url === "string" && url.trim().length > 0
}

export const ISAAC_SIM_STREAM_CONFIG = {
  /** MediaMTX WebRTC 재생 페이지 URL. RTSP 주소가 아니다. */
  url: parseStreamUrl(process.env.NEXT_PUBLIC_ISAAC_SIM_STREAM_URL),
  enabled: parseStreamEnabled(process.env.NEXT_PUBLIC_ISAAC_SIM_STREAM_ENABLED),
} as const

/**
 * iframe 의 `load` 이벤트를 이만큼 기다린다. 권장 범위(8~12초)의 중간값이다.
 *
 * 이 타임아웃은 "MediaMTX 페이지 HTML 자체가 응답하는지"만 본다. 실제 WebRTC 협상·영상 디코딩
 * 성공 여부는 이 이벤트만으로 알 수 없다 — iframe 내부는 다른 오리진이라 부모 페이지가 그 안의
 * <video> 재생 상태를 들여다볼 수 없기 때문이다. 이 한계는 IsaacSimStream.tsx 상단 주석에
 * 자세히 남겨 둔다.
 *
 * ⚠️ 이 타임아웃 하나만으로는 부족하다 — 목적지가 아예 접속 불가(DNS 실패·connection
 * refused·타임아웃)여도 브라우저는 자기 자신의 "이 사이트에 연결할 수 없습니다" 오류 문서를
 * iframe 에 그려 넣고, 그 문서도 `load` 이벤트를 정상적으로 발생시킨다. 즉 서버가 완전히 죽어
 * 있어도 이 타임아웃만 보면 "연결됨"으로 오판할 수 있다(실측 확인, 2026-08-06). 그래서
 * {@link ISAAC_SIM_STREAM_PROBE_TIMEOUT_MS} 로 iframe 을 붙이기 **전에** 실제 네트워크
 * 응답 여부를 먼저 확인한다.
 */
export const ISAAC_SIM_STREAM_LOAD_TIMEOUT_MS = 10_000

/**
 * iframe 을 붙이기 전에 하는 네트워크 도달성 사전 확인(`fetch(url, { mode: "no-cors" })`)의
 * 제한 시간. iframe 의 `load` 오판(위 주석 참고)을 막기 위한 것으로, `no-cors` 라 응답 본문/
 * 상태 코드는 읽을 수 없지만 **연결 자체의 성공/실패는 fetch 프라미스의 resolve/reject 로
 * 신뢰성 있게 알 수 있다** — CORS 는 응답을 스크립트가 읽는 것만 막지, 네트워크 계층의
 * 연결 실패 감지 자체를 막지 않는다.
 */
export const ISAAC_SIM_STREAM_PROBE_TIMEOUT_MS = 6_000

/**
 * 재시도까지 대기 시간. 기존 Omniverse WebRTC 파이프라인의 재연결 지연
 * (lib/config/isaacSim.ts 의 ISAAC_RECONNECT_DELAY_MS=3000)과 같은 값을 쓴다 — 화면에 두
 * 스트리밍 경로의 재시도 리듬이 다르게 보이지 않도록 맞춘다.
 */
export const ISAAC_SIM_STREAM_RETRY_DELAY_MS = 3_000
