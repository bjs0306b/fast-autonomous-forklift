# 로컬 Isaac Sim 화면을 EC2 로 띄우기

관제 프론트(EC2 :3000)에서 로컬 시뮬 화면을 보게 하는 방법.

```
로컬 PC (RTX 4050)                     EC2 (i15a304.p.ssafy.io)
┌──────────────────┐                 ┌───────────────────────────┐
│ Isaac Sim 창      │                 │ :3000  프론트 (관제)        │
│      ↓ x11grab    │   RTSP push     │ :8080  백엔드              │
│ ffmpeg (NVENC)    │ ──── 8554 ────▶ │ :8883  MQTT 브로커          │
└──────────────────┘                 │ :8554  MediaMTX ◀ 새로 추가 │
                                     │ :8889 + :8189/udp 재생      │
                                     └───────────────────────────┘
                                          브라우저가 :3000 을 열면
                                          iframe 이 :8889 를 재생
```

**왜 EC2 에서 Isaac 을 직접 못 돌리나** — Isaac 은 RTX GPU 렌더링이 필요한데
이 EC2 는 GPU 인스턴스가 아니다. 렌더링은 로컬에서 하고 화면만 보낸다.

---

## 0. 같은 네트워크면 이 문서가 필요 없다

교육장에서 같은 와이파이로 시연만 할 거라면 Isaac 내장 WebRTC 로 충분하다.
서버 작업이 전혀 없고, 화면을 **조작**까지 할 수 있다.

```bash
~/isaacsim/isaac-sim.streaming.sh --no-ros-env
hostname -I | awk '{print $1}'          # 이 IP 를 팀원에게
```

접속: `http://<로컬IP>:49100/streaming/webrtc-client`

아래는 **외부 인터넷에서도 봐야 할 때**의 방법이다.

---

## 1. AWS 보안그룹 — 인바운드 3개

EC2 인스턴스의 보안그룹에 추가한다.

| 유형 | 프로토콜 | 포트 | 소스 | 용도 |
|---|---|---|---|---|
| 사용자 지정 TCP | TCP | **8554** | 내 공인 IP `/32` | 로컬 → EC2 영상 올리기 |
| 사용자 지정 TCP | TCP | **8889** | `0.0.0.0/0` | 브라우저 연결 |
| 사용자 지정 UDP | **UDP** | **8189** | `0.0.0.0/0` | **영상 데이터** |

`8189/udp` 를 빠뜨리면 재생 페이지는 열리는데 화면이 검게 나온다. 가장 흔한 실수다.

내 공인 IP 확인:

```bash
curl ifconfig.me
```

공인 IP 는 바뀔 수 있다. 나중에 push 가 안 되면 다시 확인해 규칙을 고친다.
번거로우면 8554 도 `0.0.0.0/0` 으로 열되, 그러면 누구나 이 경로에 영상을
올릴 수 있으니 3번의 인증 설정을 함께 하는 것이 좋다.

### 열렸는지 확인 (로컬에서)

```bash
for p in 8554 8889; do
  timeout 4 bash -c "</dev/tcp/i15a304.p.ssafy.io/$p" 2>/dev/null \
    && echo "$p 열림" || echo "$p 닫힘"
done
```

MediaMTX 를 아직 안 띄웠으면 열려 있어도 `닫힘` 으로 나온다. 2번을 먼저 하고
다시 확인한다.

---

## 2. EC2 — 미디어 서버 띄우기

```bash
ssh -i /home/ubuntu/Downloads/I15A304T.pem ubuntu@i15a304.p.ssafy.io
```

```bash
docker run -d --name mediamtx --restart always \
  -p 8554:8554 \
  -p 8889:8889 \
  -p 8189:8189/udp \
  bluenviron/mediamtx:latest

docker logs mediamtx | tail -20
```

이런 줄들이 보이면 정상이다.

```
[RTSP] listener opened on :8554 (TCP), :8000 (UDP/RTP), :8001 (UDP/RTCP)
[WebRTC] listener opened on :8889 (HTTP), :8189 (ICE/UDP)
```

### WebRTC 가 자기 공인 IP 를 알아야 한다

EC2 는 사설 IP(172.26.x.x)를 갖고 있어서, 그대로 두면 브라우저에게 사설 IP 를
알려주고 영상이 안 붙는다. 공인 IP 를 명시한다.

```bash
docker rm -f mediamtx
docker run -d --name mediamtx --restart always \
  -e MTX_WEBRTCADDITIONALHOSTS=3.38.178.143 \
  -p 8554:8554 -p 8889:8889 -p 8189:8189/udp \
  bluenviron/mediamtx:latest
```

### 인증을 걸고 싶으면

기본은 인증이 없다. 포트를 열면 URL 을 아는 사람은 누구나 본다.
데모용이면 그대로 두고, 잠그려면:

```bash
docker rm -f mediamtx
docker run -d --name mediamtx --restart always \
  -e MTX_WEBRTCADDITIONALHOSTS=3.38.178.143 \
  -e MTX_AUTHINTERNALUSERS_0_USER=fast \
  -e MTX_AUTHINTERNALUSERS_0_PASS=원하는비번 \
  -e MTX_AUTHINTERNALUSERS_0_IPS= \
  -e MTX_AUTHINTERNALUSERS_0_PERMISSIONS_0_ACTION=publish \
  -e MTX_AUTHINTERNALUSERS_1_USER=viewer \
  -e MTX_AUTHINTERNALUSERS_1_PASS=보는비번 \
  -e MTX_AUTHINTERNALUSERS_1_PERMISSIONS_0_ACTION=read \
  -p 8554:8554 -p 8889:8889 -p 8189:8189/udp \
  bluenviron/mediamtx:latest
```

이러면 올릴 때 `rtsp://fast:원하는비번@호스트:8554/sim` 형태가 된다.

---

## 3. 로컬 — 화면 올리기

```bash
sudo apt install -y ffmpeg xdotool
```

- `ffmpeg` 인코딩·전송
- `xdotool` Isaac 창을 이름으로 찾아 그 영역만 잡는다 (없으면 클릭으로 지정)

Isaac 을 켜 두고:

```bash
cd /home/ubuntu/forklift_ws
./nav2/scripts/stream_sim.sh
```

```
>>> 창 'Isaac Sim' 을 찾았습니다
>>> 인코더: h264_nvenc (GPU)
>>> 영역 1600x900 @ (100,50)  20fps  4M
>>> 송출:  rtsp://i15a304.p.ssafy.io:8554/sim
>>> 보기:  http://i15a304.p.ssafy.io:8889/sim
```

### 옵션

```bash
./nav2/scripts/stream_sim.sh --full     # 화면 전체
./nav2/scripts/stream_sim.sh --pick     # 창을 마우스로 클릭해 지정
./nav2/scripts/stream_sim.sh --local    # EC2 없이 로컬에서만 확인
```

```bash
STREAM_FPS=30 STREAM_BITRATE=6M ./nav2/scripts/stream_sim.sh   # 더 부드럽게
STREAM_FPS=15 STREAM_BITRATE=2M ./nav2/scripts/stream_sim.sh   # 더 가볍게
STREAM_PATH=sim2 ./nav2/scripts/stream_sim.sh                  # 다른 경로로
```

RTX 4050 이라 `h264_nvenc` 하드웨어 인코딩이 걸린다. 시뮬 프레임률에 거의
영향이 없다. NVENC 을 못 찾으면 `libx264` (CPU) 로 자동 전환되는데, 그때는
FPS 를 15 정도로 낮추는 편이 낫다.

---

## 4. 보기 · 관제 화면에 박기

브라우저에서 먼저 직접 확인한다.

```
http://i15a304.p.ssafy.io:8889/sim
```

여기서 보이면 프론트 삽입은 무조건 된다.

```html
<iframe src="http://i15a304.p.ssafy.io:8889/sim"
        style="width:100%;aspect-ratio:16/9;border:0"
        allow="autoplay"></iframe>
```

프론트가 `http://...:3000` 이라 mixed content 문제가 없다. 나중에 프론트를
HTTPS 로 올리면 이 iframe 이 차단되므로, 그때는 EC2 에 nginx + 인증서를
얹어 `https://.../stream/sim` 으로 리버스 프록시해야 한다.

지연은 0.3~0.7 초 정도다.

---

## 5. 문제 해결

| 증상 | 원인과 조치 |
|---|---|
| ffmpeg `Connection refused` | MediaMTX 미기동, 또는 8554 미개방. `docker ps` 확인 |
| ffmpeg `401 Unauthorized` | 인증을 걸어 놓고 URL 에 계정을 안 넣음 |
| 페이지는 열리는데 **검은 화면** | **8189/udp 미개방** 또는 `MTX_WEBRTCADDITIONALHOSTS` 미설정 |
| 화면이 뚝뚝 끊김 | 업로드 대역폭 부족. `STREAM_BITRATE=2M` 으로 낮춘다 |
| ffmpeg `Invalid data` | 캡처 영역이 홀수 크기. 창 크기를 조금 조절 |
| 시뮬이 버벅임 | NVENC 이 아니라 CPU 인코딩 중. 로그의 `인코더:` 줄 확인 |
| 창을 못 찾음 | `xdotool` 미설치. 또는 `--pick` 으로 직접 클릭 |

### 어디까지 갔는지 나눠서 확인

```bash
# EC2 에서 — 영상이 올라오고 있나
docker logs -f mediamtx        # "is publishing to path 'sim'" 이 뜨면 도착
curl -s http://localhost:8889/sim | head -5

# 로컬에서 — 포트가 열렸나
timeout 4 bash -c "</dev/tcp/i15a304.p.ssafy.io/8554" && echo 열림
```

---

## 6. 영상만으로 관제하지 말 것

영상은 "보기 좋은 배경"이고, **실제 관제 데이터는 MQTT telemetry** 다.
프론트에서는 둘을 겹쳐 쓰는 것이 좋다.

| 목적 | 수단 |
|---|---|
| 3D 로 상황을 보여주기 | 이 문서의 영상 스트리밍 |
| 차량 위치·상태를 지도에 | MQTT `fast/v1/vehicle/+/telemetry` |
| 정지·이동 명령 | MQTT `.../control`, `.../task` |

맵 오버레이를 만들 때는 `nav2/maps/sim_warehouse.png` (400 × 600 px) 를 배경에
깔고, telemetry 의 좌표에 **× 20** 을 하면 픽셀 좌표가 된다 (20 m → 400 px).
y 축은 이미지가 위에서 아래로 커지므로 `600 - y * 20` 으로 뒤집는다.

영상이 끊겨도 맵은 계속 돈다. 클릭해서 목표를 주는 것도 맵 쪽이 자연스럽다.
