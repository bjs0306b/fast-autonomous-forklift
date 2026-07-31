"""RTMDet TensorRT 추론 래퍼 — 온보드(Jetson) 포크 정렬 비전 (S15P11A304-68).

station.detector.OnnxDetector와 **같은 인터페이스**(detect(frame_bgr) → list[Detection])
지만, onnxruntime 대신 젯슨 TensorRT 엔진을 로드해 실행한다. 전처리·후처리는
OnnxDetector와 동일하게 맞춘다 — 학습·평가와 같은 파이프라인이어야 점수가 유지된다.

**젯슨 전용.** tensorrt·pycuda가 필요하고 엔진은 그 보드에서 빌드된 것만 로드된다.
엔진 빌드·검증은 docs/ai/onboard-tensorrt-runbook.md.

    from perception.trt_detector import TrtDetector
    det = TrtDetector("onboard_s640_ep116_fp16.engine",
                      plugin="~/mmdeploy/build_trt/lib/libmmdeploy_tensorrt_ops.so",
                      class_names=("pallet", "hole"), rotate180=False)
    dets = det.detect(frame_bgr)      # list[Detection], bbox는 원본 픽셀

⚠️ **플러그인을 먼저 로드해야 한다.** 엔진이 mmdeploy 커스텀 op(TRTBatchedNMS)를
쓰므로, `libmmdeploy_tensorrt_ops.so`를 ctypes로 로드하지 않으면 역직렬화에서 죽는다.

⚠️ **rotate180**: 카메라를 뒤집어 장착했다면 True. 학습은 정립 프레임으로 했으므로
추론 입력도 정립이어야 한다 — 카메라가 뒤집혀 있으면 여기서 되돌린다. 한쪽만
돌리면 학습·추론 도메인이 반대가 되고 "아무것도 검출 안 됨"이 된다(runbook §6).
"""

from __future__ import annotations

import ctypes
import os
from pathlib import Path

import cv2
import numpy as np

from perception.load_balance import BBox, Detection

PAD_VALUE = 114


# slack 24px의 근거: eval 정답 라벨 221개에 필터를 걸어보니 8px에서는 **진짜 구멍 3개가
# 버려졌다**(0069·0072·0096, 전부 하단으로 8.3~14.6px 초과). 오라벨이 아니라 규약의
# 결과다 — pallet은 amodal, hole은 modal이라 가까이서 비스듬히 보면 구멍 안쪽이
# 파렛트 하단선보다 아래로 보인다. 24px이면 정답을 하나도 안 버리면서(221→221)
# 창밖 건물 같은 도메인 밖 오탐(파렛트 자체가 없음)은 그대로 걸린다.
DEFAULT_SLACK = 24.0

# 클래스별 임계 — pallet만 0.7로 올린다. **재현율 손실이 0이라 공짜다**(실측 근거).
#
# 2026-07-31 라이브에서 창밖 흰 건물·커튼이 pallet 0.50으로 잡혔다. eval에서 임계별
# 대가를 재보니(positive 105장 / 네거티브 87장):
#
#   임계  G2 재현율        네거티브 오탐
#   0.4   100% (105/105)   17/87 (19.5%)   ← 종전
#   0.5   100%             12/87 (13.8%)
#   0.6   100%              4/87 ( 4.6%)
#   0.7   100% (105/105)    0/87 ( 0.0%)   ← 채택
#
# 진짜 파렛트는 0.9대로 잡히고 오탐은 0.4~0.6대에 몰려 있어 사이가 깨끗하게 갈린다.
# CLAUDE.md의 "임계 상향은 재현율을 같이 깎는다"(박스 0.5→0.7에서 95.5%→91.1%)는
# 여기선 해당하지 않는다 — 그래서 기하가 아닌 임계로 푼다. 0.8도 같은 결과지만
# 다른 조명·거리에서 진짜 파렛트가 0.75쯤 나올 여지를 두어 0.7을 쓴다.
#
# hole은 올리지 않는다. 파렛트 밖 hole 오탐은 `filter_by_geometry`가 기하로 거르고,
# G1(재현율 ≥95%)이 걸린 클래스라 임계로 건드릴 이유가 없다.
DEFAULT_CLASS_THRESHOLDS = {"pallet": 0.7}


def hole_inside_pallet(hole, pallet, slack: float = DEFAULT_SLACK) -> bool:
    """구멍이 파렛트 bbox 안에 있나 (라벨 가이드 §4-1과 같은 판정)."""
    return (hole.x >= pallet.x - slack
            and hole.y >= pallet.y - slack
            and hole.x + hole.w <= pallet.x + pallet.w + slack
            and hole.y + hole.h <= pallet.y + pallet.h + slack)


def filter_by_geometry(detections: list[Detection],
                       slack: float = DEFAULT_SLACK) -> list[Detection]:
    """기하로 hole 오탐을 거른다 — **점수가 아니라 구조로**.

    라벨 가이드 §4의 sanity check를 런타임에 그대로 적용한다:
      ① `pallet`이 없으면 `hole`도 없다 (구멍은 파렛트의 일부다)
      ② `hole`은 어떤 `pallet` bbox 안에 있어야 한다

    2026-07-31 젯슨 라이브에서 **창밖 건물 창문을 hole 0.70으로 오탐**했다. 격자무늬가
    개구부와 닮았고, 학습셋에 창밖 장면이 없어(도메인 밖) 점수만으로는 못 거른다.
    임계를 올리면 진짜 구멍의 재현율(G1 99.5%)까지 깎이므로 기하로 거른다 —
    스테이션 `pipeline.on_pallet`이 같은 이유로 재현율 손실 없이 98.1%를 얻은 방식이다.

    파렛트는 건드리지 않는다. 파렛트 오탐은 이 규칙으로 못 거르고, G2가 100%라 당장
    문제도 아니다.
    """
    pallets = [d for d in detections if d.label == "pallet"]
    if not pallets:
        # ① 파렛트가 없으면 hole은 전부 버린다.
        return [d for d in detections if d.label != "hole"]
    out = []
    for d in detections:
        if d.label != "hole" or any(hole_inside_pallet(d.box, p.box, slack) for p in pallets):
            out.append(d)
    return out


class TrtDetector:
    def __init__(
        self,
        engine_path: str | Path,
        plugin: str | Path,
        input_size: int = 640,
        score_threshold: float = 0.4,
        class_names: tuple[str, ...] = ("pallet", "hole"),
        norm_mean: tuple[float, float, float] = (103.53, 116.28, 123.675),
        norm_std: tuple[float, float, float] = (57.375, 57.12, 58.395),
        class_thresholds: dict[str, float] | None = None,
        rotate180: bool = False,
        geometry_filter: bool = True,
    ) -> None:
        """class_thresholds 기본값은 `DEFAULT_CLASS_THRESHOLDS` — pallet만 0.7로 올린다."""
        import tensorrt as trt
        import pycuda.driver as cuda
        import pycuda.autoinit  # noqa: F401  (컨텍스트 초기화)

        self._trt = trt
        self._cuda = cuda

        # 1) 커스텀 op 플러그인 — 엔진 역직렬화 전에 로드해야 한다.
        ctypes.CDLL(os.path.expanduser(str(plugin)), mode=ctypes.RTLD_GLOBAL)

        # 2) 엔진 로드
        logger = trt.Logger(trt.Logger.WARNING)
        trt.init_libnvinfer_plugins(logger, "")
        runtime = trt.Runtime(logger)
        with open(os.path.expanduser(str(engine_path)), "rb") as f:
            self._engine = runtime.deserialize_cuda_engine(f.read())
        if self._engine is None:
            raise RuntimeError(f"엔진 로드 실패: {engine_path} (플러그인·아키텍처 확인)")
        self._ctx = self._engine.create_execution_context()

        # 3) I/O 텐서 이름·shape (TRT 10 API)
        self._io = {}
        for i in range(self._engine.num_io_tensors):
            name = self._engine.get_tensor_name(i)
            mode = self._engine.get_tensor_mode(name)
            self._io[name] = mode
        self._in_name = next(n for n, m in self._io.items()
                             if m == trt.TensorIOMode.INPUT)
        self._out_names = [n for n, m in self._io.items()
                           if m == trt.TensorIOMode.OUTPUT]

        self.input_size = input_size
        self.score_threshold = score_threshold
        self.class_names = class_names
        self.class_thresholds = dict(
            DEFAULT_CLASS_THRESHOLDS if class_thresholds is None else class_thresholds)
        self._mean = np.array(norm_mean, dtype=np.float32)
        self._std = np.array(norm_std, dtype=np.float32)
        self.rotate180 = rotate180
        self.geometry_filter = geometry_filter

        self._alloc()

    def _alloc(self) -> None:
        """정적 shape이므로 버퍼를 한 번만 잡는다."""
        trt, cuda = self._trt, self._cuda
        size = self.input_size
        self._ctx.set_input_shape(self._in_name, (1, 3, size, size))

        self._host, self._dev, self._shapes = {}, {}, {}
        for name in [self._in_name, *self._out_names]:
            shape = tuple(self._ctx.get_tensor_shape(name))
            dtype = trt.nptype(self._engine.get_tensor_dtype(name))
            host = cuda.pagelocked_empty(int(np.prod(shape)), dtype)
            dev = cuda.mem_alloc(host.nbytes)
            self._host[name] = host
            self._dev[name] = dev
            self._shapes[name] = shape
            self._ctx.set_tensor_address(name, int(dev))
        self._stream = cuda.Stream()

    def detect(self, frame_bgr: np.ndarray) -> list[Detection]:
        cuda = self._cuda
        tensor, scale = self._preprocess(frame_bgr)

        np.copyto(self._host[self._in_name], tensor.ravel())
        cuda.memcpy_htod_async(self._dev[self._in_name],
                               self._host[self._in_name], self._stream)
        self._ctx.execute_async_v3(self._stream.handle)
        for name in self._out_names:
            cuda.memcpy_dtoh_async(self._host[name], self._dev[name], self._stream)
        self._stream.synchronize()

        outs = {n: self._host[n].reshape(self._shapes[n]) for n in self._out_names}
        # mmdeploy end2end 출력 이름은 dets, labels
        dets = outs.get("dets", next(iter(outs.values())))
        labels = outs.get("labels", list(outs.values())[-1])
        result = self._postprocess(dets[0], labels[0], scale)
        return filter_by_geometry(result) if self.geometry_filter else result

    def _preprocess(self, frame: np.ndarray) -> tuple[np.ndarray, float]:
        if self.rotate180:
            frame = cv2.rotate(frame, cv2.ROTATE_180)
        h, w = frame.shape[:2]
        size = self.input_size
        scale = min(size / w, size / h)
        new_w, new_h = int(round(w * scale)), int(round(h * scale))
        resized = cv2.resize(frame, (new_w, new_h), interpolation=cv2.INTER_LINEAR)
        padded = np.full((size, size, 3), PAD_VALUE, dtype=np.uint8)
        padded[:new_h, :new_w] = resized
        normalized = (padded.astype(np.float32) - self._mean) / self._std
        tensor = normalized.transpose(2, 0, 1)[np.newaxis]
        return np.ascontiguousarray(tensor, dtype=np.float32), scale

    def _postprocess(self, dets, labels, scale) -> list[Detection]:
        out: list[Detection] = []
        for (x1, y1, x2, y2, score), label in zip(dets, labels):
            if int(label) < 0 or int(label) >= len(self.class_names):
                continue
            name = self.class_names[int(label)]
            if score < self.class_thresholds.get(name, self.score_threshold):
                continue
            x1, y1, x2, y2 = x1 / scale, y1 / scale, x2 / scale, y2 / scale
            out.append(Detection(
                label=name,
                box=BBox(x=float(x1), y=float(y1),
                         w=float(x2 - x1), h=float(y2 - y1)),
                score=float(score),
            ))
        return out
