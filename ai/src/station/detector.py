"""RTMDet ONNX 추론 래퍼 (mmdeploy end2end).

모델: ``models/end2end.onnx`` — RTMDet-m, LOCO+Cardboard 학습(mAP_50 0.710),
서버에서 mmdeploy로 export (NMS 포함). 입력 [1,3,H,W] float32, 출력
``dets`` [1,N,5](x1,y1,x2,y2,score — 입력 좌표계)와 ``labels`` [1,N].

전처리는 mmdet test_pipeline과 정확히 일치해야 한다 (2026-07-22 서버 진단이
torch .pth 오라클과 대조해 확정 — 일치 시 onnx가 torch와 top score 0.6496까지
완전 일치):

1. **keep_ratio 리사이즈** — scale=min(size/W, size/H)로 종횡비 유지 축소
2. **좌상단 114 패딩** — size×size 캔버스에 (0,0) 배치
3. **BGR 유지** (RGB 변환 안 함)
4. **정규화** (pixel - mean) / std — mmdeploy가 정규화를 그래프에 넣지 않았으므로
   호출자가 해야 한다. 안 하면 점수 붕괴.

⚠️ 함정: 종횡비를 무시한 단순 정사각 리사이즈(cv2.resize(img,(size,size)))를 쓰면
왜곡으로 score가 0.65→0.58로 떨어진다. input size를 키워도 왜곡은 그대로라 안 오른다.
출력 bbox는 패딩이 좌상단 기준이므로 scale로 나누면 원본 좌표가 된다.

onnxruntime만 쓰므로 mmdet/mmcv 스택이 필요 없다 — Windows 스테이션 PC에서
의존성 지옥 없이 돈다. 결과는 perception.load_balance의 Detection으로 감싸
편하중·선택 로직과 바로 물린다.
"""

from __future__ import annotations

from pathlib import Path

import cv2
import numpy as np

from perception.load_balance import BBox, Detection
from perception.preprocess import letterbox, to_tensor


class OnnxDetector:
    def __init__(
        self,
        model_path: Path,
        input_size: int = 640,
        score_threshold: float = 0.5,
        class_names: tuple[str, ...] = ("box", "pallet"),
        norm_mean: tuple[float, float, float] = (103.53, 116.28, 123.675),
        norm_std: tuple[float, float, float] = (57.375, 57.12, 58.395),
        class_thresholds: dict[str, float] | None = None,
    ) -> None:
        import onnxruntime as ort

        self._session = ort.InferenceSession(
            str(model_path), providers=["CPUExecutionProvider"]
        )
        self._input_name = self._session.get_inputs()[0].name
        self.input_size = input_size
        self.score_threshold = score_threshold
        self.class_names = class_names
        # 클래스별 임계 — 지정한 클래스만 덮어쓰고 나머지는 score_threshold를 쓴다.
        # 파렛트가 박스보다 낮은 점수로 잡히기 때문이다 (검은 플라스틱 + 격자 구조라
        # 박스처럼 큰 단색면이 없다). 평가셋 실측: 파렛트는 0.4에서 재현율 100%,
        # 0.5로 올리면 1개를 놓친다. 박스는 0.5에서 정밀도 93.9%로 유지가 낫다.
        self.class_thresholds = dict(class_thresholds or {})
        self._mean = np.array(norm_mean, dtype=np.float32)   # BGR 순서 그대로
        self._std = np.array(norm_std, dtype=np.float32)

    def detect(self, frame_bgr: np.ndarray) -> list[Detection]:
        """프레임 한 장 → Detection 리스트 (bbox는 원본 픽셀 좌표)."""
        tensor, scale = self._preprocess(frame_bgr)
        dets, labels = self._session.run(None, {self._input_name: tensor})
        return self._postprocess(dets[0], labels[0], scale)

    def _preprocess(self, frame: np.ndarray) -> tuple[np.ndarray, float]:
        padded, scale = letterbox(frame, self.input_size)
        return to_tensor(padded, self._mean, self._std), scale

    def _postprocess(
        self, dets: np.ndarray, labels: np.ndarray, scale: float
    ) -> list[Detection]:
        out: list[Detection] = []
        for (x1, y1, x2, y2, score), label in zip(dets, labels):
            name = self.class_names[int(label)]
            if score < self.class_thresholds.get(name, self.score_threshold):
                continue  # dets는 score 내림차순이지만 mmdeploy가 0점 패딩을 섞는다
            x1, y1, x2, y2 = x1 / scale, y1 / scale, x2 / scale, y2 / scale
            out.append(
                Detection(
                    label=name,
                    box=BBox(x=float(x1), y=float(y1),
                             w=float(x2 - x1), h=float(y2 - y1)),
                    score=float(score),
                )
            )
        return out
