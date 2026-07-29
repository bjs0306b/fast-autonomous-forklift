# 온보드 TensorRT 배포 런북 (S15P11A304-68)

Jetson Orin Nano에서 RTMDet ONNX를 TensorRT 엔진으로 빌드하는 절차. 2026-07-29 실제로
관통했고 **막힌 지점 4개를 전부 기록**한다 — 젯슨을 다시 플래싱하면 이 문서대로 재현해야 한다.

## 결과 (RTMDet-s @640, FP16)

| 지표 | 값 |
|---|---|
| **GPU Compute Time** | **median 9.28 ms** (min 9.21 / mean 9.33) |
| Throughput | **106.8 qps** |
| End-to-end Latency | median 9.59 ms |
| 엔진 크기 | 23 MB |

포크 정렬에 필요한 건 10~15 FPS인데 **약 107 FPS**가 나온다 — 7배 이상 여유.
**-m으로 올려도(대략 2배 ≈ 18ms, 55FPS) 온보드 실시간이 된다.**

## 환경

- Jetson Orin Nano Super, JetPack 6 / L4T R36.4.7, Ubuntu 22.04 aarch64
- **CUDA 12.6 + TensorRT 10.3.0**, `trtexec` = `/usr/src/tensorrt/bin/trtexec`
- 빌드 도구: cmake 3.22, g++ (기본 설치됨)

## 막힌 지점 4개 (전부 실측으로 확인)

### 1. 동적 입력 shape
mmdeploy가 onnxruntime용으로 뽑은 ONNX는 입력이 `batch,3,height,width`로 동적이다.
TRT는 concrete shape가 필요해 `/neck/Concat: axis 2 dimensions must be equal`로 실패한다.
→ **정적 shape로 export**(`ai/configs/deploy/detection_tensorrt_static_onboard.py`).

### 2. TopK K > 3840
`ITopKLayer /TopK: K exceeds the maximum value allowed (3840)`.
NMS 전 후보 수가 TensorRT의 TopK 하드 한계를 넘는다.
→ deploy config에서 **`pre_top_k=3000`**.

### 3. `TRTBatchedNMS` 플러그인 없음 ★ 핵심
mmdeploy end2end ONNX의 NMS는 **mmdeploy 커스텀 TRT 플러그인**이다. 젯슨엔 mmdeploy가
없어 `Cannot find plugin: TRTBatchedNMS`로 파싱이 실패한다.
→ **젯슨에서 플러그인을 직접 빌드**(아래 절차).

### 4. labels 타입 INT64 vs INT32
플러그인은 `labels`를 INT32로 내는데 ONNX 그래프는 INT64로 선언한다. TRT 10은 이 불일치를
엄격히 잡는다: `For INT32 tensors, the output type must also be INT32`.
→ **TRT 전용 ONNX에서 출력 타입을 INT32로** 바꾼다. ⚠️ 스테이션용 원본은 건드리지 않는다
(onnxruntime는 INT64로 잘 돌고 있다).

## 절차

### A. 서버 — TRT용 ONNX export
```bash
# GPU 서버, conda env: rtmdet (mmdeploy 1.3.1)
cd ~/S15P11A304/ai
CUDA_VISIBLE_DEVICES=1 python - <<'PY'
from mmdeploy.apis import torch2onnx
torch2onnx(img="<샘플 이미지>", work_dir="~/trt_export_s", save_file="end2end.onnx",
           deploy_cfg="ai/configs/deploy/detection_tensorrt_static_onboard.py",
           model_cfg="<모델 config>", model_checkpoint="<체크포인트 .pth>", device="cuda:0")
PY
```

labels 타입을 INT32로 바꿔 TRT 전용 사본을 만든다:
```python
import onnx
from onnx import TensorProto
m = onnx.load("end2end.onnx")
for o in m.graph.output:
    if o.name == "labels" and o.type.tensor_type.elem_type == TensorProto.INT64:
        o.type.tensor_type.elem_type = TensorProto.INT32
onnx.save(m, "end2end_trt.onnx")
```

> GPU 서버는 아웃바운드가 막혀 있어 젯슨으로 직접 못 보낸다. **노트북을 경유**한다.

### B. 젯슨 — mmdeploy TRT ops 플러그인 빌드 (최초 1회)

```bash
cd ~
git clone --depth 1 -b v1.3.1 https://github.com/open-mmlab/mmdeploy.git
cd mmdeploy && mkdir -p build_trt && cd build_trt
cmake .. -DMMDEPLOY_TARGET_BACKENDS=trt -DTENSORRT_DIR=/usr -DCUDNN_DIR=/usr \
         -DCMAKE_BUILD_TYPE=Release
make -j4
```

> **버전을 서버의 mmdeploy와 맞춘다(v1.3.1).** 플러그인 ABI가 export 쪽과 어긋나면 안 된다.

#### ⚠️ TensorRT 10 호환 패치 (2곳)
mmdeploy 1.3.1은 TensorRT 8 기준이라 **`Dims::d`가 int32 → int64로 바뀐** TRT 10에서
컴파일이 깨진다. 커널 시그니처는 `int*`인데 `int64_t*`가 들어가 타입 에러가 난다.

- `csrc/mmdeploy/backend_ops/tensorrt/gather_topk/gather_topk.cpp`
- `csrc/mmdeploy/backend_ops/tensorrt/grid_sampler/trt_grid_sampler.cpp`

두 파일 모두 **`dims.d[]`를 `std::vector<int>`로 좁혀 복사**한 뒤 그 포인터를 커널에
넘기도록 고친다(차원값은 int 범위를 넘지 않는다). 예:

```cpp
// TensorRT 10에서 Dims::d 가 int64_t 로 바뀌었다. 커널은 int 배열을 받으므로 좁혀 복사한다.
std::vector<int> dims_vec(nbDims);
for (int i = 0; i < nbDims; ++i) dims_vec[i] = static_cast<int>(inputDesc[0].dims.d[i]);
const int *dims = dims_vec.data();
```

**패치 파일을 커밋해 뒀다** — 손으로 고칠 필요 없다:
```bash
cd ~/mmdeploy/csrc/mmdeploy/backend_ops/tensorrt
patch -p0 < <레포>/ai/patches/mmdeploy-1.3.1-tensorrt10-int64-dims.patch
```

산출물: `~/mmdeploy/build_trt/lib/libmmdeploy_tensorrt_ops.so` (약 21MB)

### C. 젯슨 — 엔진 빌드
```bash
/usr/src/tensorrt/bin/trtexec \
  --onnx=end2end_trt.onnx --fp16 \
  --staticPlugins=$HOME/mmdeploy/build_trt/lib/libmmdeploy_tensorrt_ops.so \
  --saveEngine=onboard_s640_fp16.engine
```

성공 신호: `Successfully created plugin: TRTBatchedNMS` → `Engine built in ...` → `PASSED`.
**빌드에 약 10분** 걸린다(커널 튜닝). 엔진은 **기기·TRT 버전 종속**이라 반드시 타깃에서 빌드한다.

## 남은 일

- 지금 엔진은 **exp7 계보 -s**로 만든 관통 검증용이다. **145의 미니어처 파인튜닝 -s가
  나오면 같은 절차로 다시 뽑는다** — 블로커는 이미 다 뚫려 있어 A→C만 반복하면 된다.
- 온보드 추론 코드가 TRT 엔진을 쓰도록 붙이는 작업은 별개(현재 스테이션은 onnxruntime).
- INT8 양자화는 캘리브레이션 데이터(미니어처)가 필요해 145 이후.

## 산출물 위치
- 젯슨: `~/mmdeploy/build_trt/lib/libmmdeploy_tensorrt_ops.so`, `~/trt_test/onboard_s640_fp16.engine`
- 패치 원본 백업: `<파일>.bak` (같은 디렉터리)
- 서버: `~/trt_export_s/end2end.onnx`, `end2end_trt.onnx`
