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

/**
 * 지게차(Orin)에 달린 온보드 카메라 스트림. 예: `http://<젯슨>:8878/stream`
 * (`ai/scripts/onboard_camera_stream.py`).
 *
 * 값이 있으면 "AI 측정 영상" 패널에 **스테이션/온보드 전환 버튼**이 생긴다. 없으면
 * 버튼 없이 스테이션 화면만 나온다 — 누를 곳이 있는데 안 나오는 것보다 낫다.
 *
 * ⚠️ 온보드 카메라는 **포크 정렬 노드와 같은 장치**다. 그쪽이 돌고 있으면 송출
 * 서버가 카메라를 못 연다(리눅스 V4L2 도 배타적이다). 먼저 쓰는 쪽이 이긴다.
 */
export const ONBOARD_STREAM_URL =
  process.env.NEXT_PUBLIC_ONBOARD_STREAM_URL?.trim() || null
