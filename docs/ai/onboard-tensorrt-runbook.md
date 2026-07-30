# 온보드 TensorRT 엔진 런북 (S15P11A304-68)

> 대상: Jetson Orin Nano에서 RTMDet ONNX → TensorRT FP16 엔진
> 관련: 145(파인튜닝) · 144(촬영·라벨) · 152~154(정렬 제어)
> 파인튜닝 쪽 절차는 `onboard-finetune-runbook.md`, 이 문서는 **엔진 빌드와 그 함정**만 다룬다.

이 문서는 2026-07-30에 젯슨 실물을 조회해 작성했다. **검증된 것과 기록이 없는 것을 구분해 적는다** — 68 착수 때 추측을 사실로 읽으면 안 된다.

## 1. 플랫폼 (2026-07-30 실측)

| 항목 | 값 |
|---|---|
| L4T | **R36.4.7** (GCID 42132812, 2025-09-18 빌드) |
| OS | Ubuntu 22.04.5 LTS · aarch64 |
| TensorRT | **10.3.0.30** (deb, `tensorrt` 메타패키지) |
| CUDA toolkit | 12.6.11 (`/usr/local/cuda` → 12.6) |
| `trtexec` | `/usr/src/tensorrt/bin/trtexec` (PATH에 없다 — 절대경로로 부른다) |
| 메모리 | 7.4Gi 공유 (CPU·GPU 공용) |
| SSH | 별칭 `ssh orin` (키 인증), 계정 `orin` |

`nvidia-smi`는 **Tegra라 GPU 정보를 못 준다**(`[N/A]`). 사용률은 `tegrastats`로 본다.

## 2. mmdeploy 플러그인 (검증됨)

RTMDet ONNX는 mmdeploy가 낸 커스텀 op(`TRTBatchedNMS`)를 쓰므로 **플러그인 없이는 엔진 빌드도, 로드도 안 된다.**

| 항목 | 값 |
|---|---|
| mmdeploy | `~/mmdeploy` · **v1.3.1** (`bc75c9d`, `version.py` = 1.3.1) |
| 플러그인 | `~/mmdeploy/build_trt/lib/libmmdeploy_tensorrt_ops.so` (22MB, 2026-07-29 빌드) |

⚠️ **export 쪽 mmdeploy와 버전이 같아야 한다.** GPU 서버도 1.3.1이다(CLAUDE.md 검증 조합). 버전이 어긋나면 op 스키마가 안 맞아 파싱에서 죽는다.

## 3. 현재 산출물 (`~/trt_test`)

| 파일 | 크기 | 날짜 | 비고 |
|---|---|---|---|
| `end2end_m800.onnx` | 109MB | 07-28 | 스테이션 -m@800. 온보드용 아님 — 대조용으로만 올라와 있다 |
| `end2end_s640.onnx` | 40MB | 07-28 | -s@640 초기 export |
| `end2end_s640_trt.onnx` | 40MB | 07-29 | **TRT deploy config로 재-export.** 위와 크기는 같지만 md5가 다르다 — 재-export는 no-op이 아니다 |
| `onboard_s640_fp16.engine` | 23MB | 07-29 | **현재 엔진.** 2클래스(box·pallet) 시절 산출물 |

`onboard_s640_fp16.engine`은 **145 이전** 것이다. 3클래스(+hole) 모델이 나오면 다시 빌드해야 한다.

## 4. 절차

### ① GPU 서버에서 ONNX export

```bash
# GPU 서버, Device1만 사용
CUDA_VISIBLE_DEVICES=1 python tools/deploy.py \
    configs/deploy/detection_tensorrt_static_onboard.py \
    <모델 config> <체크포인트 .pth> <샘플 이미지>
```

**onnxruntime용 export를 그대로 쓰지 않는다.** TensorRT deploy config로 다시 뽑는다 — §3에서 07-28본과 07-29본의 md5가 다른 이유다.

### ② 노트북 경유로 전송

**GPU 서버는 아웃바운드가 전면 차단**돼 젯슨으로 직접 못 보낸다. 노트북이 중계한다.

```bash
scp i15a304:~/S15P11A304/work_dirs/.../end2end.onnx .
scp end2end.onnx orin:~/trt_test/
```

### ③ 젯슨에서 FP16 엔진 빌드

```bash
/usr/src/tensorrt/bin/trtexec \
    --onnx=$HOME/trt_test/end2end_s640_trt.onnx \
    --saveEngine=$HOME/trt_test/onboard_s640_fp16.engine \
    --fp16 \
    --staticPlugins=$HOME/mmdeploy/build_trt/lib/libmmdeploy_tensorrt_ops.so
```

플래그 이름은 보드의 `trtexec --help`로 확인했다(2026-07-30) — `--staticPlugins`·`--memPoolSize`·`--iterations`·`--avgRuns` 모두 존재하고, **구버전의 `--plugins`는 없다**(TRT 10에서 `--staticPlugins`/`--dynamicPlugins`로 갈렸다).

⚠️ 다만 **07-29에 실제로 쓴 명령은 복원하지 못했다** — `~/trt_test`에 로그가 없고 `~/.bash_history`도 비어 있다. 위 명령은 TensorRT 10.3 기준으로 재구성한 것이니, 처음 돌릴 때 결과를 이 문서에 확정해 둘 것.

**메모리를 확인하고 시작한다.** 7.4Gi를 CPU와 공유하고 팀원 ROS2 노드가 상시 4GB 가까이 쓴다(2026-07-30 조회 시 가용 1.9Gi). 빌드는 워크스페이스를 크게 잡으므로 여유가 없으면 실패하거나 다른 사람 프로세스를 밀어낸다. `free -h`와 `who`로 먼저 보고, 필요하면 `--memPoolSize=workspace:1024`로 제한한다.

### ④ 엔진 검증

```bash
/usr/src/tensorrt/bin/trtexec \
    --loadEngine=$HOME/trt_test/onboard_s640_fp16.engine \
    --staticPlugins=$HOME/mmdeploy/build_trt/lib/libmmdeploy_tensorrt_ops.so \
    --iterations=200 --avgRuns=100
```

## 5. 지연 실측 — 재측정으로 확정할 것

CLAUDE.md와 `onboard-finetune-runbook.md` G4에 **RTMDet-s @640 FP16 9.28ms / 107fps**로 적혀 있다. 그런데 **이 숫자의 측정 조건이 어디에도 없다** — 리포지토리에도, 젯슨에도 로그가 없다(`~/trt_test` 로그 없음, `~/.bash_history` 비어 있음).

### 전력 모드 — 가장 큰 변수 (2026-07-30 실측)

`/etc/nvpmodel.conf`의 모드 정의와 현재 상태:

| ID | 이름 | |
|---|---|---|
| 0 | 15W | |
| 1 | 25W | **config 기본값** (`PM_CONFIG DEFAULT=1`) |
| 2 | **MAXN_SUPER** | **현재 모드** (`/var/lib/nvpmodel/status` = `pmode:0002`) |
| 3 | 7W | |

보드는 지금 **전력 상한이 없는 최고 성능 모드**에 있다. 9.28ms가 이 상태에서 나왔다면 **가장 빠를 수 있는 조건의 값**이고, 재측정은 같거나 느려진다.

⚠️ **현재 모드가 config 기본값이 아니다.** 누가 MAXN_SUPER로 올려둔 상태이고 기본값은 25W다. 보드를 리셋·재플래시하면 **코드를 아무도 안 건드렸는데 성능이 바뀐다.** 측정할 때 모드를 함께 적어야 하는 이유다.

### 재측정 시 달라질 수 있는 폭

| 요인 | 방향 | 대략 |
|---|---|---|
| MAXN_SUPER → 7W | 느려짐 | **2~3× (최대 요인)** |
| 클럭 미고정 — 조회 시 CPU 1344/1728MHz로 DVFS 작동 | 느려짐·산포 | 10~30% |
| 전처리 포함 (1280×800 → 640 letterbox·정규화·캡처) | 추가 | +수 ms |
| 팀원 ROS2·SLAM과 메모리 대역폭 경합 | 느려짐 | 1.5~3× |
| 지속 부하 발열 (MAXN_SUPER는 상한 없음) | 느려짐 | 장시간 시 하락 |
| 2→3 클래스 | 거의 무관 | <1% |

NMS는 mmdeploy가 그래프에 넣었으므로(플러그인이 필요한 이유) 9.28ms에 **포함**돼 있다. 빠진 것은 letterbox·정규화·카메라 캡처다.

### 결론과 실제 위험

나쁜 경우를 겹쳐도 수십 ms대이고 예산은 100ms다 — **G4는 안전하고 "실시간 된다"는 결론은 견고하다.**

진짜 위험은 **107fps를 설계 전제로 쓰는 것**이다. 152 정렬 제어 루프를 100fps 가정으로 짜면 현장 실측이 25~40fps일 때 설계가 틀어진다. 9.28ms는 벤치 최적 조건값이고 **운용 보장값이 아니다.**

68 착수 때 §4-④로 다시 재고 아래를 함께 기록한다. **클래스가 2→3으로 늘고 145 모델로 바뀐 뒤**의 값이어야 G4 판정에 쓸 수 있다.

```bash
cat /var/lib/nvpmodel/status   # 전력 모드 (sudo 없이 읽힌다)
free -h; who                   # 경합 상태 — 팀원 접속 여부까지
tegrastats --interval 1000     # 측정 중 GPU·EMC 사용률
cat /sys/class/thermal/thermal_zone*/temp   # 발열
```

## 6. 함정

**입력 640 고정.** config의 `img_size`를 바꿨다면 엔진도 다시 빌드해야 한다. static shape으로 빌드하므로 해상도가 엔진에 박힌다.

**추론 경로에 180° 회전을 넣는다.** 카메라를 뒤집어 장착했고 촬영은 `shoot.py --rotate180`으로 정립 저장했다. 추론에서 회전을 빼면 학습·추론 도메인이 정반대가 되고, **증상은 "아무것도 검출 안 됨"이라 원인 찾기가 어렵다.** 카메라는 USB 모듈 **1280×800**(OV5647 아님 — 명세 BOM 정정 대기).

**엔진은 이식되지 않는다.** TensorRT 엔진은 TRT 버전·GPU 아키텍처에 묶인다. 젯슨에서 빌드한 것만 젯슨에서 쓴다. 서버에서 만들어 옮기는 건 안 된다.

**플러그인은 로드 시점에도 필요하다.** 빌드할 때만 필요한 게 아니라 엔진을 역직렬화할 때도 필요하다. 런타임 코드에서도 `.so`를 먼저 로드한다.

## 7. 남은 일

- [ ] 145 3클래스 모델로 재-export → 재빌드 → **G4 재측정**(§5 기록 항목 포함)
- [ ] 07-29 빌드 명령 확정 — §4-③ 플래그를 실행으로 검증하고 이 문서에 반영
- [ ] 런타임 통합: 180° 회전 + 플러그인 로드 + 카메라 1280×800 → 640 전처리
