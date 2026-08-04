/**
 * 브라우저에서 직접 표시할 수 있는 AI 측정 카메라 스트림 주소.
 *
 * 현재 저장소의 AI 프로그램은 로컬 카메라를 OpenCV로 읽을 뿐 영상 서버를 제공하지 않는다.
 * 따라서 기본 URL을 임의로 만들지 않으며, 배포 환경이 MJPEG 또는 갱신 이미지 URL을 제공할 때만
 * NEXT_PUBLIC_AI_MEASUREMENT_STREAM_URL을 설정한다.
 */
export const AI_MEASUREMENT_STREAM_URL =
  process.env.NEXT_PUBLIC_AI_MEASUREMENT_STREAM_URL?.trim() || null
