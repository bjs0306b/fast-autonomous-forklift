# 젯슨 추론 서버 자동기동

> 대상: Jetson Orin Nano (`ssh orin`) — 스테이션 측정이 REST 로 붙는 추론 서버
> 관련 이슈: S15P11A304-181

## 왜 필요한가

**자동기동이 없어서 재부팅하면 서버가 안 뜬다.** 가정이 아니라 실측이다 —
2026-08-03에 확인했을 때 젯슨이 약 2시간 전 재부팅된 상태였고, 8877 리스닝도
프로세스도 없었다(마지막 실행 흔적 07-31 20:40).

시연 당일 젯슨이 한 번이라도 재부팅되면 **스테이션 측정이 통째로 실패한다.**

---

## 1. 실행 요건 (문서에 없던 것들)

조사하며 알아낸 것이라 여기 남긴다. 손으로 띄울 때도 같다.

| 항목 | 값 |
|---|---|
| 스크립트 | `/home/orin/dimeval/onboard_infer_server.py` |
| 작업 디렉터리 | `/home/orin/dimeval` |
| **PYTHONPATH** | **`/home/orin/dimeval/src`** ← 없으면 즉시 죽는다 |
| 엔진 | `/home/orin/calib/station_m800_fp32.engine` |
| TRT 플러그인 | `/home/orin/mmdeploy/build_trt/lib/libmmdeploy_tensorrt_ops.so` |
| 포트 | `8877` |
| 입력 크기 | `800` (스테이션 letterbox 크기와 같아야 한다) |
| `--score` | **`0.05`** ← 아래 참조 |

> ⚠️ **스크립트가 레포 안에 없다.** 젯슨의 `~/S15P11A304` 에는 이 파일이 없고
> `~/dimeval/` 에 손으로 복사된 사본이 있다. EC2 배포 디렉터리와 같은 구조라,
> 레포를 고쳐도 젯슨에 자동으로 반영되지 않는다. 옮길 때는 사람이 복사해야 한다.

### ⚠️ `--score` 를 낮추는 이유

서버 기본값은 `0.5` 인데 **스테이션의 파렛트 임계는 `0.4`** 다
(`ai/src/station/config.py`, 리그 평가셋 30장 실측 근거).

기본값 그대로 띄우면 **0.4~0.5 파렛트가 서버에서 먼저 버려져** 스테이션 필터에
도달조차 못 한다. 그리고 이건 **에러를 내지 않는다** — 같은 장면이 로컬 경로에서는
`ok`, 보드 경로에서는 `dimensions_only` 가 되어 전복·편하중 판정이 통째로 빠진다.
2026-07-31에 실제로 겪었고 상세는 `ai/src/station/remote_detector.py` 의
docstring 에 있다.

**원칙: 서버는 후보만 돌려주고 판정 임계는 스테이션이 쥔다.**
따라서 `--score` 는 스테이션의 **가장 낮은 클래스 임계보다 낮게** 둔다.

---

## 2. 설치

```bash
scp scripts/systemd/fast-onboard-infer.service orin:/tmp/
```

```bash
ssh orin
```

```bash
sudo install -m 644 /tmp/fast-onboard-infer.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now fast-onboard-infer
```

확인:

```bash
systemctl status fast-onboard-infer --no-pager
ss -ltn | grep 8877
curl -sS -o /dev/null -w '%{http_code}\n' http://localhost:8877/health
```

---

## 3. ⚠️ VS Code Remote 가 붙어 있으면 기동에 실패한다

```
RuntimeError: make_default_context() wasn't able to create a context
             on any of the 1 detected devices
```

젯슨에 VS Code Remote 세션이 붙어 있으면 메모리를 크게 점유해 **TensorRT 가
CUDA 컨텍스트를 잡지 못한다.** 알려진 문제다.

- **부팅 시점에는 문제가 없다.** VS Code 서버는 사람이 접속할 때 뜨므로, 부팅
  직후에는 이 서비스가 먼저 컨텍스트를 잡는다. 오히려 유리하다.
- **손으로 검증할 때만 걸린다.** 서비스를 재시작하려는데 실패한다면 VS Code
  Remote 부터 의심할 것 — 팀원이 붙어 있을 수 있으니 임의로 끊지 말고 물어본다.

```bash
# 누가 붙어 있는지 확인
ps -eo rss,cmd --no-headers | grep '[.]vscode-server' | head
free -h
```

`Restart=always` 지만 `StartLimitBurst=5` / `StartLimitIntervalSec=300` 으로
제한해 두었다 — 컨텍스트를 못 잡는 상황에서 무한 재시작으로 로그를 채우지
않게 하기 위해서다. 한도를 넘겨 멈췄으면 원인을 없앤 뒤:

```bash
sudo systemctl reset-failed fast-onboard-infer && sudo systemctl start fast-onboard-infer
```

---

## 4. 주소 고정 — Tailscale

젯슨 WiFi 는 **DHCP 라 재부팅하면 IP 가 바뀐다**(현재 `70.12.247.81`).
스테이션의 `--infer-url` 이 여기 걸려 있어 시연 전마다 확인해야 했다.

**젯슨에는 이미 Tailscale 이 깔려 있고 `tailscaled` 가 부팅 자동기동이다.**

```
tailscale0 : 100.88.197.8     ← DHCP 와 무관하게 바뀌지 않는다
```

측정 PC 에도 Tailscale 을 설치해 같은 테일넷에 넣으면 주소를 고정할 수 있다.

```bash
python -m station.serve --listen --infer-url http://100.88.197.8:8877
```

> 2026-08-03 기준 **측정 PC 에는 Tailscale 이 설치돼 있지 않다.** 테일넷에는
> 젯슨(`orin-desktop`)과 `desktop-ms327cl`(다른 윈도우 노드) 둘만 있다.

Tailscale 을 쓰지 않는다면 대안은 공유기 DHCP 예약이나 젯슨 WiFi 고정 IP 설정이다.

---

## 5. 검증 상태

| 항목 | 상태 |
|---|---|
| 실행 명령(경로·PYTHONPATH·인자) | ✅ import 통과까지 실측 확인 |
| ExecStart 가 가리키는 4개 경로 실재 | ✅ 스크립트·엔진·플러그인·모듈 |
| 유닛 파일 문법 | ✅ `systemd-analyze verify` 경고 0 |
| **기동 실증** | ❌ **미검증** — CUDA 컨텍스트 확보 불가(§3) |
| **재부팅 후 자동 기동** | ❌ **미검증** |

⚠️ **시연 전에 반드시 재부팅 실증을 할 것.** 유닛이 등록돼 있다는 것과 실제로
올라온다는 것은 다르다. 이 프로젝트에서 가장 비싼 버그는 늘 "조용히 안 되는"
쪽이었다.
