/**
 * AI 측정 영상 주소가 어떤 종류인지 가른다.
 *
 * <b>왜 필요한가</b>: 같은 패널이 두 가지 송출을 받는다.
 *
 * - <b>MJPEG</b>(`multipart/x-mixed-replace`) — 스테이션 측정 PC 가 카메라를 그대로
 *   내보낸다(`station/livestream.py`).
 * - <b>재생 페이지</b> — MediaMTX 의 WebRTC 페이지처럼 그 자체가 HTML 인 경우.
 *
 * 그리는 방법이 다르다. 재생 페이지는 `<iframe>` 이어야 하고, MJPEG 은 `<img>` 여야
 * 한다. <b>MJPEG 을 iframe 으로 그리면 화면은 나오는데 "연결됨"이 영영 안 된다</b> —
 * 응답이 끝나지 않는 스트림이라 iframe 의 `load` 이벤트가 발생하지 않고, 패널이
 * "연결 중" 오버레이를 계속 덮어씌운다(2026-08-09 실측).
 *
 * 판단은 <b>주소만 보고</b> 한다. 실제 Content-Type 을 확인하려면 요청을 한 번 더
 * 보내야 하는데, MJPEG 은 그 요청이 끝나지 않아 확인 자체가 스트림을 하나 더 여는 꼴이 된다.
 */

/** MJPEG 으로 볼 경로 꼬리. 우리 송출 서버(`/stream`)와 흔한 관례를 담는다. */
const MJPEG_HINTS = ["/stream", "/mjpeg", "/mjpg", ".mjpg", ".mjpeg", "/video_feed"]

export type StreamKind = "mjpeg" | "page"

/**
 * 주소로 종류를 추정한다.
 *
 * `override` 가 주어지면 그것을 그대로 쓴다 — 추정이 틀리는 주소(예: WebRTC 페이지
 * 경로가 우연히 `/stream` 인 경우)를 코드 수정 없이 넘기기 위한 탈출구다.
 */
export function streamKind(
  url: string | null | undefined,
  override?: string | null,
): StreamKind {
  const forced = override?.trim().toLowerCase()
  if (forced === "mjpeg" || forced === "page") return forced
  if (!url) return "page"
  let path = url
  try {
    path = new URL(url).pathname
  } catch {
    // 상대경로거나 형식이 이상하면 원문에서 찾는다 — 판단을 포기하지 않는다.
  }
  const lower = path.toLowerCase().replace(/\/+$/, "")
  return MJPEG_HINTS.some((hint) => lower.endsWith(hint)) ? "mjpeg" : "page"
}
