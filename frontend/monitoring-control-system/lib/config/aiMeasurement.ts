/**
 * 브라우저에서 직접 표시할 수 있는 AI 측정 카메라 스트림 주소.
 *
 * 스테이션 측정 PC 가 `station/livestream.py`(= `serve.py --stream-port`)로 카메라를
 * MJPEG 으로 내보낸다. 그 주소를 여기 넣는다 — 예: `http://<측정PC>:8879/stream`.
 * 값이 없으면 패널은 "연결 대기"로 남는다(임의 기본값을 만들지 않는다).
 *
 * ⚠️ **브라우저가 직접 가져가는 주소다.** 관제 화면을 여는 PC 에서 그 주소에 닿아야
 * 하고(같은 네트워크·방화벽 허용), 측정 PC 가 DHCP 면 주소가 바뀔 수 있다.
 */
export const AI_MEASUREMENT_STREAM_URL =
  process.env.NEXT_PUBLIC_AI_MEASUREMENT_STREAM_URL?.trim() || null

/**
 * 스트림 종류 강제(`mjpeg` | `page`). 비워두면 주소로 추정한다.
 *
 * 추정 규칙과 왜 종류를 구분해야 하는지는 `lib/monitoring/streamKind.ts` 참고.
 */
export const AI_MEASUREMENT_STREAM_KIND =
  process.env.NEXT_PUBLIC_AI_MEASUREMENT_STREAM_KIND?.trim() || null
