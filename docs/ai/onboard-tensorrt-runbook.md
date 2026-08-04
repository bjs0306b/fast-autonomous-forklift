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

`onboard_s640_fp16.engine`은 **145 이전** 것이다. ~~3클래스(+hole) 모델이 나오면 다시 빌드해야 한다.~~
→ **완료.** 145가 낸 모델은 **`pallet`·`hole` 2클래스**(box는 폐기)이고, `epoch_116`으로
`onboard_s640_ep116_fp16.engine`을 이미 빌드해 **G4(10.18ms)까지 통과**했다.

> ⚠️ **온보드는 2클래스다.** 당초 3클래스(box·pallet·hole) 계획이었으나 2026-07-30에
> **box를 폐기**했다 — exp8 프리라벨이 흰 파렛트를 box로 오인해(장당 2.4개) pallet
> 검출을 해쳤다. `ai/data/labels/onboard_eval.json`의 어노테이션도 `pallet 105` ·
> `hole 221` · **box 0건**이다.
>
> `ai/src/dataset/label_onboard.py`의 `CLASSES`가 아직 `["box","pallet","hole"]` 3슬롯
> 이지만 box(id 1)는 **비어 있다.** 이 잔재 때문에 "3클래스" 기술이 여러 문서에
> 남아 있었다(2026-08-03 정정).

## 4. 절차

### ① GPU 서버에서 ONNX export

서버 mmdeploy는 **pip 설치라 `tools/deploy.py`가 없다**(소스에만 포함). 젯슨 `~/mmdeploy/tools/`를 노트북 경유로 서버에 올려 쓴다(순수 python, 버전 1.3.1로 일치). deploy config가 `type='tensorrt'`라 export 후 TRT 변환까지 시도하지만 **서버엔 TRT가 없어 그 단계만 실패**한다 — end2end.onnx는 그 전에 생성되므로 정상이다.

```bash
# GPU 서버(rtmdet env), ai/ 에서. Device1만 사용
CUDA_VISIBLE_DEVICES=1 python mmdeploy_tools/deploy.py \
    configs/deploy/detection_tensorrt_static_onboard.py \
    configs/rtmdet_s_640_onboard_forklift.py \
    work_dirs/onboard_s_2class/epoch_116.pth \
    <샘플 이미지> --work-dir work_dirs/onboard_export --device cuda:0
```

⚠️ **labels dtype 후처리 필수 (2026-07-30 실측 — 68 최대 함정).** mmdeploy export가
`labels` 출력을 **INT64**로 내면 젯슨 TRT 10.3이 파싱에서 거부한다:
`Assertion failed: For INT32 tensors, the output type must also be INT32`. 07-29 onnx는
INT32라 빌드됐고 구조는 완전 동일했다 — 차이는 labels dtype뿐. **INT32로 되돌린 뒤
젯슨으로 보낸다:**

```bash
python -m dataset.fix_onnx_labels_int32 \
    work_dirs/onboard_export/end2end.onnx \
    work_dirs/onboard_export/end2end_int32.onnx
```

이 진단은 **기존 성공 onnx로 대조 빌드**해서 얻었다 — 새 onnx가 실패할 때 환경 문제인지
export 문제인지부터 가른다(기존이 되면 export, 안 되면 환경). onnx 백업은
`ai/models/onboard_s_2class/end2end_s640_ep116.onnx`(INT32 수정본, 젯슨 재빌드 소스).

### ② 젯슨으로 전송

⚠️ **종전에 "GPU 서버는 아웃바운드가 전면 차단돼 직접 못 보낸다"고 적혀 있었는데 사실이 아니다**(2026-08-03 실측):

| 확인 | 결과 |
|---|---|
| `curl https://pypi.org` | **200** — 아웃바운드 열려 있다 |
| GPU서버 → 젯슨 `70.12.247.81:22` | **열림** — 직접 전송 가능 |
| GPU서버 → EC2 `:8080` | 도달 |
| `git ls-remote` (GitLab) | 실패 — 다만 **자격증명 없음**이지 네트워크 차단이 아니다 |

그러니 **GPU 서버에서 젯슨으로 바로 보내면 된다.** 키가 없으면 비밀번호를 묻는다.

```bash
# GPU 서버에서
scp ~/S15P11A304/work_dirs/.../end2end.onnx orin@70.12.247.81:~/trt_test/
```

노트북 중계도 물론 된다(키가 양쪽에 있어 손이 덜 간다):

```bash
scp i15a304:~/S15P11A304/work_dirs/.../end2end.onnx .
scp end2end.onnx orin:~/trt_test/
```

⚠️ **젯슨 IP 는 DHCP 다**(현재 `70.12.247.81`). 바뀌면 위 주소도 바뀐다 — S15P11A304-183 참조.

### ③ 젯슨에서 FP16 엔진 빌드

```bash
# 검증된 명령 (2026-07-30 epoch 116 빌드 성공, 9.6분)
/usr/src/tensorrt/bin/trtexec \
    --onnx=$HOME/trt_test/onboard_ep116_int32.onnx \
    --saveEngine=$HOME/trt_test/onboard_s640_ep116_fp16.engine \
    --fp16 \
    --staticPlugins=$HOME/mmdeploy/build_trt/lib/libmmdeploy_tensorrt_ops.so \
    --memPoolSize=workspace:512
```

플래그 이름은 보드의 `trtexec --help`로 확인했다 — `--staticPlugins`·`--memPoolSize`·`--iterations`·`--avgRuns` 모두 존재하고, **구버전의 `--plugins`는 없다**(TRT 10에서 `--staticPlugins`/`--dynamicPlugins`로 갈렸다). 위 명령으로 23MB 엔진이 나왔다.

**메모리를 확인하고 시작한다.** 7.4Gi를 CPU와 공유하고 팀원 노드가 상시 5GB 가까이 쓴다(2026-07-30 조회 시 가용 2.1Gi). `--memPoolSize=workspace:512`면 그 여유에서도 빌드된다(1024는 `NvMapMemAllocInternalTagged: error 12`=ENOMEM으로 실패). `free -h`로 먼저 보고, 남의 프로세스를 밀어내지 않게 workspace를 줄인다.

⚠️ **엔진은 이식되지 않는다** — TRT 버전·GPU 아키텍처에 묶인다. 노트북엔 재빌드 소스(INT32 onnx)만 백업하고, 엔진은 젯슨에서만 빌드·사용한다.

### ④ 엔진 검증

```bash
/usr/src/tensorrt/bin/trtexec \
    --loadEngine=$HOME/trt_test/onboard_s640_ep116_fp16.engine \
    --staticPlugins=$HOME/mmdeploy/build_trt/lib/libmmdeploy_tensorrt_ops.so \
    --iterations=200 --avgRuns=100 --warmUp=500
```

**epoch 116 실측 (2026-07-30, G4)**: GPU Compute **10.18ms** (mean=median, p99 10.22ms) · Host Latency 10.50ms · 97.8fps. 측정 조건 = MAXN_SUPER · **팀원 5GB 점유 경합 중** · 전처리 미포함. 예산 100ms의 1/10이라 **G4 통과**. 경합 상태의 값이라 한가할 때 재면 같거나 빨라진다.

## 5. 지연 실측 — 벤치 최적값과 운용값

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

**추론 경로에 180° 회전을 넣는다.** 카메라를 뒤집어 장착했고 촬영은 `shoot.py --rotate180`으로 정립 저장했다. 추론에서 회전을 빼면 학습·추론 도메인이 정반대가 되고, **증상은 "아무것도 검출 안 됨"이라 원인 찾기가 어렵다.** 카메라는 USB 모듈이다(OV5647 아님 — **명세 BOM 정정 완료**). ⚠️ **모듈 스펙은 1280×800이지만
젯슨에 물리면 1280×720까지만 잡힌다**(800 미지원, 2026-07-31 라이브 검증). 학습·eval은 800으로
했으므로 letterbox 비율이 달라지지만 검출은 정상이었다. 명세는 실동작인 **720** 기준이다.

**엔진은 이식되지 않는다.** TensorRT 엔진은 TRT 버전·GPU 아키텍처에 묶인다. 젯슨에서 빌드한 것만 젯슨에서 쓴다. 서버에서 만들어 옮기는 건 안 된다.

**플러그인은 로드 시점에도 필요하다.** 빌드할 때만 필요한 게 아니라 엔진을 역직렬화할 때도 필요하다. 런타임 코드에서도 `.so`를 먼저 로드한다.

## 7. 남은 일

- [x] ~~145 3클래스 모델로 재-export → 재빌드 → G4 재측정~~ → **완료**(2026-07-30).
      145는 **2클래스**(`pallet`·`hole`) 모델이고, `epoch_116` 기준으로 재-export·재빌드해
      **G4 10.18ms(97.8fps)** 로 통과했다. 엔진 `onboard_s640_ep116_fp16.engine`.
- [ ] 07-29 빌드 명령 확정 — §4-③ 플래그를 실행으로 검증하고 이 문서에 반영
- [x] ~~런타임 통합: 180° 회전 + 플러그인 로드 + 카메라 1280×800 → 640 전처리~~ →
      **완료**(2026-07-30~31). `perception/trt_detector.py` + `scripts/onboard_live.py`,
      젯슨 종단 실측 **44.96ms · 22.2fps**. 카메라는 젯슨에서 **1280×720**까지만 잡힌다.
