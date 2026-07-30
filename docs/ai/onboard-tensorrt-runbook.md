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

CLAUDE.md와 `onboard-finetune-runbook.md` G4에 **RTMDet-s @640 FP16 9.28ms / 107fps**로 적혀 있다. 그런데 **이 숫자의 측정 조건이 어디에도 없다** — 리포지토리에도, 젯슨에도 로그가 없다. 확인 못 한 것들:

- 배치·반복 횟수, 워밍업 유무
- 전처리·후처리 포함인지 (순수 엔진 시간인지)
- 전력 모드 (`nvpmodel` — Orin Nano는 7W/15W/25W에서 성능이 크게 갈린다)
- 측정 당시 다른 프로세스 점유

G4가 이 숫자에 걸려 있으므로 **68 착수 때 위 ④로 다시 재고, 아래를 함께 기록한다.**

```bash
sudo nvpmodel -q            # 전력 모드
free -h; who                # 경합 상태
tegrastats --interval 1000  # 측정 중 GPU·EMC 사용률
```

여유가 7배라는 결론 자체는 뒤집히기 어렵다(예산 100ms). 다만 **클래스가 2→3으로 늘고 145 모델로 바뀐 뒤**의 값이어야 G4 판정에 쓸 수 있다.

## 6. 함정

**입력 640 고정.** config의 `img_size`를 바꿨다면 엔진도 다시 빌드해야 한다. static shape으로 빌드하므로 해상도가 엔진에 박힌다.

**추론 경로에 180° 회전을 넣는다.** 카메라를 뒤집어 장착했고 촬영은 `shoot.py --rotate180`으로 정립 저장했다. 추론에서 회전을 빼면 학습·추론 도메인이 정반대가 되고, **증상은 "아무것도 검출 안 됨"이라 원인 찾기가 어렵다.** 카메라는 USB 모듈 **1280×800**(OV5647 아님 — 명세 BOM 정정 대기).

**엔진은 이식되지 않는다.** TensorRT 엔진은 TRT 버전·GPU 아키텍처에 묶인다. 젯슨에서 빌드한 것만 젯슨에서 쓴다. 서버에서 만들어 옮기는 건 안 된다.

**플러그인은 로드 시점에도 필요하다.** 빌드할 때만 필요한 게 아니라 엔진을 역직렬화할 때도 필요하다. 런타임 코드에서도 `.so`를 먼저 로드한다.

## 7. 남은 일

- [ ] 145 3클래스 모델로 재-export → 재빌드 → **G4 재측정**(§5 기록 항목 포함)
- [ ] 07-29 빌드 명령 확정 — §4-③ 플래그를 실행으로 검증하고 이 문서에 반영
- [ ] 런타임 통합: 180° 회전 + 플러그인 로드 + 카메라 1280×800 → 640 전처리
