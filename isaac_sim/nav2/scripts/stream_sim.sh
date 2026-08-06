#!/usr/bin/env bash
# Isaac Sim 화면을 EC2 미디어 서버로 밀어넣는다 (관제 프론트에서 볼 수 있게).
#
#   로컬 Isaac 화면 → ffmpeg(NVENC) → EC2 MediaMTX → 브라우저/관제화면
#
# EC2 준비 (한 번만):
#   docker run -d --name mediamtx --restart always \
#     -p 8554:8554 -p 8889:8889 -p 8189:8189/udp bluenviron/mediamtx:latest
#   보안그룹 인바운드: 8554/tcp, 8889/tcp, 8189/udp
#
# 사용:
#   ./nav2/scripts/stream_sim.sh              # Isaac 창을 자동으로 찾아 송출
#   ./nav2/scripts/stream_sim.sh --full       # 화면 전체
#   ./nav2/scripts/stream_sim.sh --pick       # 창을 마우스로 직접 클릭해 지정
#   ./nav2/scripts/stream_sim.sh --local      # EC2 대신 로컬에서만 확인
#
# 환경변수:
#   STREAM_SCALE=1280   내보낼 가로 해상도 (기본 1280, 0 이면 원본)
#   STREAM_FPS=20       프레임률
#   STREAM_BITRATE=4M   비트레이트
#   STREAM_CROP="x,y,w,h"  창 안에서 잘라낼 영역 (뷰포트만 보내기)
#
# Isaac 의 패널을 통째로 숨기려면 Script Editor 에서:
#   import carb; carb.settings.get_settings().set("/app/window/hideUi", True)
#
# 보기:
#   http://i15a304.p.ssafy.io:8889/sim
#
# 관제 화면에 박기:
#   <iframe src="http://i15a304.p.ssafy.io:8889/sim"
#           style="width:100%;aspect-ratio:16/9;border:0" allow="autoplay"></iframe>

HOST="${STREAM_HOST:-i15a304.p.ssafy.io}"
PATH_NAME="${STREAM_PATH:-sim}"
FPS="${STREAM_FPS:-20}"
BITRATE="${STREAM_BITRATE:-4M}"
WINDOW_NAME="${STREAM_WINDOW:-Isaac Sim}"
# 내보낼 가로 해상도. Isaac 창이 4K 급이면 그대로 보내봐야 대역폭만 먹고
# 관제 화면에서는 작게 보이므로 줄여서 보낸다. 0 이면 원본 그대로.
SCALE_W="${STREAM_SCALE:-1280}"
# 창 안에서 잘라낼 영역 "x,y,w,h" (창 좌상단 기준). Stage/Property 패널을
# 빼고 뷰포트만 보내고 싶을 때 쓴다. 빈 값이면 창 전체.
#   STREAM_CROP="300,80,2000,1400" ./stream_sim.sh
CROP="${STREAM_CROP:-}"

MODE="auto"
for a in "$@"; do
  case "$a" in
    --full)  MODE="full" ;;
    --pick)  MODE="pick" ;;
    --local) HOST="127.0.0.1" ;;
    *) echo "알 수 없는 옵션: $a"; exit 1 ;;
  esac
done

if ! command -v ffmpeg >/dev/null; then
  echo "ffmpeg 가 없습니다:  sudo apt install -y ffmpeg"
  exit 1
fi

# ── 잡을 영역 정하기 ────────────────────────────────────────────────
# x11grab 은 짝수 크기를 요구한다(h264 4:2:0). 홀수면 인코더가 거부한다.
even() { echo $(( $1 - $1 % 2 )); }

geom_from_xwininfo() {
  local out="$1"
  X=$(echo "$out" | awk '/Absolute upper-left X/{print $4}')
  Y=$(echo "$out" | awk '/Absolute upper-left Y/{print $4}')
  W=$(echo "$out" | awk '/^  Width/{print $2}')
  H=$(echo "$out" | awk '/^  Height/{print $2}')
}

case "$MODE" in
  full)
    read W H <<<"$(xdpyinfo | awk '/dimensions:/{split($2,a,"x"); print a[1], a[2]}')"
    X=0; Y=0
    echo ">>> 화면 전체 ${W}x${H}"
    ;;
  pick)
    echo ">>> 송출할 창을 클릭하세요..."
    geom_from_xwininfo "$(xwininfo)"
    ;;
  auto)
    # 이름으로 창을 찾는다. 못 찾으면 클릭으로 넘어간다.
    if command -v xdotool >/dev/null; then
      WID=$(xdotool search --name "$WINDOW_NAME" 2>/dev/null | head -1)
    fi
    if [ -n "$WID" ]; then
      geom_from_xwininfo "$(xwininfo -id "$WID")"
      echo ">>> 창 '$WINDOW_NAME' 을 찾았습니다"
    else
      echo ">>> '$WINDOW_NAME' 창을 자동으로 못 찾았습니다."
      echo "    (xdotool 이 있으면 자동:  sudo apt install -y xdotool)"
      echo "    송출할 창을 클릭하세요..."
      geom_from_xwininfo "$(xwininfo)"
    fi
    ;;
esac

W=$(even "$W"); H=$(even "$H")
if [ -z "$W" ] || [ "$W" -le 0 ]; then
  echo "창 크기를 읽지 못했습니다."
  exit 1
fi

# 창 안에서 일부만 잘라낸다 (패널 제외하고 뷰포트만 등)
if [ -n "$CROP" ]; then
  IFS=',' read -r CX CY CW CH <<<"$CROP"
  X=$((X + CX)); Y=$((Y + CY))
  W=$(even "$CW"); H=$(even "$CH")
  echo ">>> 잘라내기: 창 기준 (${CX},${CY}) 에서 ${W}x${H}"
fi

# ── 인코더 고르기 ───────────────────────────────────────────────────
# NVENC 이 있으면 GPU 로 인코딩한다. 시뮬 프레임률에 거의 영향이 없다.
if ffmpeg -hide_banner -encoders 2>/dev/null | grep -q h264_nvenc; then
  VENC=(-c:v h264_nvenc -preset p1 -tune ll -rc cbr -b:v "$BITRATE")
  echo ">>> 인코더: h264_nvenc (GPU)"
else
  VENC=(-c:v libx264 -preset ultrafast -tune zerolatency -b:v "$BITRATE")
  echo ">>> 인코더: libx264 (CPU) — NVENC 을 못 찾았습니다"
fi

# 축소 필터. 세로는 -2 로 두어 가로비를 유지하면서 짝수로 맞춘다.
VF=()
OUT="${W}x${H}"
if [ "$SCALE_W" -gt 0 ] && [ "$W" -gt "$SCALE_W" ]; then
  VF=(-vf "scale=${SCALE_W}:-2")
  OUT="${SCALE_W}x(비율유지)"
fi

URL="rtsp://${HOST}:8554/${PATH_NAME}"
echo ">>> 영역 ${W}x${H} @ (${X},${Y}) -> 송출 ${OUT}  ${FPS}fps  ${BITRATE}"
echo ">>> 송출:  $URL"
echo ">>> 보기:  http://${HOST}:8889/${PATH_NAME}"
echo ">>> 종료: Ctrl+C"
echo ""

exec ffmpeg -hide_banner -loglevel warning -stats \
  -f x11grab -framerate "$FPS" -video_size "${W}x${H}" -i ":0.0+${X},${Y}" \
  "${VF[@]}" "${VENC[@]}" -g $((FPS * 2)) -pix_fmt yuv420p \
  -f rtsp -rtsp_transport tcp "$URL"
