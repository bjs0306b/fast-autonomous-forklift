# Isaac Sim 화면 스트리밍 (MediaMTX)

Isaac Sim 을 띄운 로컬 Ubuntu 화면을 그대로 관제 화면에 보여주기 위한 파이프라인이다.

> **다른 문서와의 관계**: 차량의 위치·상태(telemetry)는 이 문서와 별개다. 그쪽은
> `docs/backend-message/communication-protocol.md` 를 참고할 것 — Isaac Sim → MQTT →
> Spring Boot → STOMP → React 경로이며, 여기서 다루는 "화면 영상" 경로와 서로 독립적으로
> 동작한다(§3 참고).

## 1. 아키텍처

```text
로컬 Ubuntu Isaac Sim 화면
  → xdotool 로 Isaac Sim 창 영역 탐색
  → ffmpeg 화면 캡처
  → RTSP 송출
  → EC2 MediaMTX
  → WebRTC 재생 페이지
  → 프론트 관제 화면 iframe
```

확정된 주소:

| 구간 | 주소 |
|---|---|
| RTSP 송출 (Isaac Sim PC → EC2) | `rtsp://i15a304.p.ssafy.io:8554/sim` |
| 브라우저 재생 페이지 (프론트가 iframe 으로 띄우는 URL) | `http://i15a304.p.ssafy.io:8889/sim` |

MediaMTX 포트:

| 포트 | 프로토콜 | 용도 |
|---|---|---|
| 8554 | TCP | RTSP 입력 (Isaac Sim PC → MediaMTX) |
| 8889 | TCP | WebRTC HTTP 재생 페이지 (브라우저 → MediaMTX) |
| 8189 | UDP | WebRTC ICE |

MediaMTX 환경 설정:

```text
MTX_WEBRTCADDITIONALHOSTS=3.38.178.143
```

**프론트는 RTSP 주소(8554)를 절대 쓰지 않는다.** 브라우저가 RTSP 를 직접 재생할 수 없고,
WebRTC 협상은 8889 가 서빙하는 재생 페이지 안의 JS 가 대신 하기 때문이다. 프론트가 아는
주소는 오직 `http://i15a304.p.ssafy.io:8889/sim` 하나뿐이다.

> 이 절(EC2 인프라 구축 절차 자체 — MediaMTX 설정 파일, ffmpeg/xdotool 캡처 스크립트, AWS
> 보안그룹)은 이 문서를 처음 작성하며 함께 기록한 것으로, **실행해 검증하지 않았다**(§6 참고).
> 향후 실제 구축 절차(스크립트, systemd 유닛, docker-compose 등)가 추가되면 이 섹션은
> 그 절차에 맞춰 갱신하고, 프론트·백엔드 연동 절(§2~§5)은 그대로 둘 것.

## 2. 프론트 연결

관제 화면의 기존 Isaac Sim 영상 영역(`DigitalTwinVideoLayer` → `IsaacSimVideoSurface`,
`components/monitoring/IsaacSimStream.tsx`)이 이 URL 을 iframe 으로 띄운다. 새 컴포넌트를
만들지 않고 기존 영상 레이어의 "영상 소스" 부분만 이 경로로 교체했다.

```env
NEXT_PUBLIC_ISAAC_SIM_STREAM_URL=http://i15a304.p.ssafy.io:8889/sim
NEXT_PUBLIC_ISAAC_SIM_STREAM_ENABLED=true
```

- `NEXT_PUBLIC_ISAAC_SIM_STREAM_URL` 이 비어 있으면 기본값(`http://i15a304.p.ssafy.io:8889/sim`)을 쓴다.
- `NEXT_PUBLIC_ISAAC_SIM_STREAM_ENABLED=false` 로만 끌 수 있다(값이 없거나 오타면 켜진 것으로 본다).
- 값 파싱은 `lib/config/isaacSimStream.ts` 한 곳에서만 한다. JSX 에 IP·포트를 직접 쓰지 않는다.
- `NEXT_PUBLIC_*` 이므로 **빌드 시점**에 번들에 박힌다 — 값을 바꾸면 배포 환경에서는
  `npm run build` 를 다시 해야 한다(로컬 `next dev` 는 재시작으로 반영될 수 있다).

### 예전 Isaac Sim WebRTC(Omniverse SDK) 경로와의 차이

`NEXT_PUBLIC_ISAAC_WEBRTC_SERVER`/`_SIGNAL_PORT`/`_MEDIA_PORT` 는 **이 파이프라인과 다른,
예전 경로**다 — `@nvidia/ov-web-rtc` SDK 가 Isaac Sim 의 Omniverse 스트리밍 서버(시그널링
49100/TCP, 미디어 47998/UDP)에 브라우저가 직접 붙는 방식이었다. `.env.example` 실측
기록(2026-07-31)에 따르면 미디어 포트(47998)가 리스닝하지 않는 문제가 있었고, 이 문서가
다루는 MediaMTX 경로가 그 대체다. `IsaacSimStream.tsx` 는 이제 이 SDK 를 쓰지 않는다.
예전 설정 모듈(`lib/config/isaacSim.ts`)은 삭제하지 않고 남겨 두었다(다른 참고용, 현재는
미사용).

### 상태 판정의 한계 — 반드시 읽을 것

`connected` 상태는 **MediaMTX 재생 페이지가 iframe 안에서 로드됐다**(`load` 이벤트)는 뜻이지,
**영상이 실제로 재생 중**이라는 뜻이 아니다. iframe 은 다른 오리진 문서라 부모 페이지(관제
화면)가 그 안의 `<video>` 재생 상태를 들여다볼 수 없다. 협상까지는 끝났는데 프레임이 한 장도
안 오는 상황도 `connected` 로 표시될 수 있다.

같은 이유로 `disconnected` 상태는 사실상 도달하지 않는다 — 한 번 로드된 뒤 스트림이 끊겨도
iframe 문서 자체는 "로드된 상태" 그대로다. 실제 영상 재생 여부는 화면을 직접 보거나 MediaMTX
로그로 확인해야 한다(§6).

`load` 이벤트가 8~12초(기본 10초, `ISAAC_SIM_STREAM_LOAD_TIMEOUT_MS`) 안에 오지 않으면
`failed` 로 표시하고 3초 뒤 자동 재시도한다. 재시도 시 같은 URL 을 그대로 다시 대입하면
브라우저가 캐시로 판단해 재요청을 건너뛸 수 있어, 캐시 무력화 쿼리(`?reconnect=N`)를 붙인다.

**실측으로 드러난 문제와 그 대응(2026-08-06)**: MediaMTX 가 아예 접속 불가(EC2 미기동·보안그룹
미개방 등)여도, 브라우저는 "이 사이트에 연결할 수 없습니다" 같은 자기 자신의 오류 문서를
iframe 에 그려 넣고 그 문서 역시 `load` 를 정상 발생시킨다 — `load` 이벤트만 보면 서버가
죽어 있어도 "연결됨"으로 오판한다(실제로 이 상태를 화면에서 확인했다). 그래서 iframe 을
붙이기 **전에** `fetch(url, { mode: "no-cors" })` 로 네트워크 도달성을 먼저 확인한다
(`ISAAC_SIM_STREAM_PROBE_TIMEOUT_MS`, 기본 6초). CORS 는 응답 판독만 막을 뿐 연결 성공/실패
자체(프라미스 resolve/reject)는 그대로 신뢰할 수 있다. 이 사전 확인에서 실패하면 iframe 을
아예 붙이지 않고 바로 `failed` 로 표시한다 — 지금 EC2 에 MediaMTX 가 떠 있지 않은 상태에서
이 경로로 정확히 "연결 실패"가 뜨는 것까지 브라우저로 확인했다(§6 나머지 항목은 여전히 미검증).

## 3. 데이터 흐름 분리

```text
차량 상태·위치 (telemetry):
  Isaac Sim → MQTT → Spring Boot → STOMP → React

화면 영상:
  Isaac Sim 화면 → ffmpeg → RTSP → MediaMTX → WebRTC 재생 페이지 → iframe
```

두 경로는 서로 독립적으로 동작해야 하고, 실제 구현도 그렇다.

- 영상이 끊겨도 차량 위치·상태(미니맵, 차량 상세 패널)는 계속 표시된다 — 서로 다른 훅
  (`useMonitoringSocket` vs `useIsaacSimStream`)이 독립된 상태를 관리한다.
- MQTT 가 끊겨도 MediaMTX 재생 페이지 URL 이 살아 있으면 영상은 그대로 표시된다.
- 영상 연결 상태(`WebRtcStatus`)와 MQTT/STOMP 연결 상태(`RealtimeConnectionStatus`,
  `RealtimeConnectionBadge`)는 서로 다른 배지로 표시되며 합치지 않는다.
- 영상 오류가 정지·비상정지 버튼을 비활성화하지 않는다 — 두 기능 모두 코드를 건드리지
  않았다.

## 4. 백엔드

**백엔드는 영상을 프록시하지 않는다.** 프론트가 MediaMTX 재생 페이지 URL 에 직접 접근하므로
Spring Boot 가 영상 바이트를 중계할 이유가 없다. 이번 작업에서 백엔드 코드는 수정하지
않았다 — 스트림 URL 을 프론트 환경변수만으로 충분히 관리할 수 있고, 이미 Isaac Sim WebRTC
설정(`NEXT_PUBLIC_ISAAC_WEBRTC_*`)도 같은 방식(프론트 환경변수)으로 관리돼 온 관례를
따른다.

다음은 이번 작업에서도, 앞으로도 하지 않는다.

- Spring Boot 에서 ffmpeg 프로세스 실행
- RTSP → 다른 포맷 변환을 백엔드가 수행
- 영상 프레임을 MQTT/WebSocket/Base64 로 전송
- 백엔드 메모리·DB 에 영상 버퍼 적재

## 5. HTTP/HTTPS 및 보안

- 로컬 개발 프론트(`http://localhost:3000`)는 `http://i15a304.p.ssafy.io:8889/sim` 을
  iframe 으로 그대로 쓸 수 있다.
- **운영 프론트가 HTTPS 라면 HTTP iframe 은 mixed content 로 차단될 수 있다.** MediaMTX 쪽에
  HTTPS/WSS 설정 또는 HTTPS 리버스 프록시가 필요하다 — 이번 작업 범위에 포함하지 않았고,
  인증서·Nginx 구축도 하지 않았다. **운영 배포 전 반드시 확인해야 할 위험**으로 남겨 둔다.
- RTSP 인증정보가 있다면 URL 에 직접 노출하지 않는다. 프론트는애초에 RTSP 주소를 모른다
  (§1) — 노출될 수 있는 것은 재생 페이지 URL(`.../sim`)뿐이고, 여기엔 비밀정보가 없다.
- iframe `src` 는 오직 환경변수로 설정된 값만 쓴다. 사용자 입력을 그대로 iframe 에 꽂는
  구조는 없다.
- PEM 키, EC2 SSH 키 경로, AWS 보안그룹 설정은 이 저장소 코드에 넣지 않는다.

## 6. 수동 확인 필요 (이번 작업에서 실행하지 않음)

아래 항목은 코드·설정만으로 검증할 수 없다. 실제 환경에서 순서대로 확인할 것.

1. AWS 보안그룹에서 `8554/TCP`, `8889/TCP`, `8189/UDP` 가 열려 있는지 확인
2. EC2 에서 MediaMTX 가 `MTX_WEBRTCADDITIONALHOSTS=3.38.178.143` 로 실행 중인지 확인
3. Isaac Sim PC 에서 `xdotool` + `ffmpeg` 로 RTSP 송출이 실제로 되는지 확인
4. MediaMTX 로그에서 `is publishing to path 'sim'` 문구 확인
5. 브라우저에서 `http://i15a304.p.ssafy.io:8889/sim` 직접 접속해 영상이 실제로 재생되는지 확인
6. 관제 화면(`http://localhost:3000` 또는 배포 프론트)에서 iframe 안에 같은 영상이 뜨는지 확인
7. 그 상태에서 MQTT 차량 위치·상태(미니맵, 차량 상세, 정지/비상정지)가 영상과 무관하게
   계속 정상 동작하는지 확인(§3)
8. HTTPS 배포 환경이라면 브라우저 콘솔에 mixed content 오류가 없는지 확인
9. `npm run build`, `npm test`(`lib/**/*.test.ts` 전부), `mvnw test` — 이번 작업에서 실행하지
   않았다. 커밋 전 반드시 실행할 것.

실제로 실행·확인하지 않은 항목을 성공으로 보고하지 않는다.
