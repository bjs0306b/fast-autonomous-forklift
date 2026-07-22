"""RTMDet ONNX 추론 래퍼 (mmdeploy end2end).

모델: ``models/end2end.onnx`` — RTMDet-m, LOCO+Cardboard 학습(mAP_50 0.710),
서버에서 mmdeploy로 export (NMS 포함). 입력 [1,3,H,W] float32, 출력
``dets`` [1,N,5](x1,y1,x2,y2,score — 입력 좌표계)와 ``labels`` [1,N].

전처리: 비율 유지 축소 → 좌상단 기준 114 패딩 → BGR 원시 픽셀(float32).
**정규화(mean/std)는 하지 않는다** — mmdeploy가 export 시 정규화를 ONNX 그래프
안에 내장했다. 밖에서 또 하면 이중 정규화로 입력이 망가져 점수가 붕괴한다
(2026-07-22 실측: 이중 정규화 시 박스 최고점수 0.11 → 원시 픽셀 0.42로 회복).
출력 bbox는 패딩이 좌상단 기준이므로 scale로 나누기만 하면 원본 좌표가 된다.

onnxruntime만 쓰므로 mmdet/mmcv 스택이 필요 없다 — Windows 스테이션 PC에서
의존성 지옥 없이 돈다. 결과는 perception.load_balance의 Detection으로 감싸
편하중·선택 로직과 바로 물린다.
"""

from __future__ import annotations

from pathlib import Path

import cv2
import numpy as np

from perception.load_balance import BBox, Detection

PAD_VALUE = 114


class OnnxDetector:
    def __init__(
        self,
        model_path: Path,
        input_size: int = 640,
        score_threshold: float = 0.5,
        class_names: tuple[str, ...] = ("box", "pallet"),
        norm_mean: tuple[float, float, float] = (103.53, 116.28, 123.675),
        norm_std: tuple[float, float, float] = (57.375, 57.12, 58.395),
    ) -> None:
        import onnxruntime as ort

        self._session = ort.InferenceSession(
            str(model_path), providers=["CPUExecutionProvider"]
        )
        self._input_name = self._session.get_inputs()[0].name
        self.input_size = input_size
        self.score_threshold = score_threshold
        self.class_names = class_names
        # norm_mean/std는 받아두되 쓰지 않는다 (모델 내장). 시그니처 호환 유지용.
        del norm_mean, norm_std

    def detect(self, frame_bgr: np.ndarray) -> list[Detection]:
        """프레임 한 장 → Detection 리스트 (bbox는 원본 픽셀 좌표)."""
        tensor, scale = self._preprocess(frame_bgr)
        dets, labels = self._session.run(None, {self._input_name: tensor})
        return self._postprocess(dets[0], labels[0], scale)

    def _preprocess(self, frame: np.ndarray) -> tuple[np.ndarray, float]:
        h, w = frame.shape[:2]
        size = self.input_size
        scale = min(size / w, size / h)
        new_w, new_h = int(round(w * scale)), int(round(h * scale))
        resized = cv2.resize(frame, (new_w, new_h), interpolation=cv2.INTER_LINEAR)

        padded = np.full((size, size, 3), PAD_VALUE, dtype=np.uint8)
        padded[:new_h, :new_w] = resized

        # 정규화는 모델 내장 — 원시 BGR 픽셀을 float32로만 넘긴다 (클래스 도크스트링 참고)
        tensor = padded.astype(np.float32).transpose(2, 0, 1)[np.newaxis]  # HWC → NCHW
        return np.ascontiguousarray(tensor), scale

    def _postprocess(
        self, dets: np.ndarray, labels: np.ndarray, scale: float
    ) -> list[Detection]:
        out: list[Detection] = []
        for (x1, y1, x2, y2, score), label in zip(dets, labels):
            if score < self.score_threshold:
                continue  # dets는 score 내림차순이지만 mmdeploy가 0점 패딩을 섞는다
            x1, y1, x2, y2 = x1 / scale, y1 / scale, x2 / scale, y2 / scale
            out.append(
                Detection(
                    label=self.class_names[int(label)],
                    box=BBox(x=float(x1), y=float(y1),
                             w=float(x2 - x1), h=float(y2 - y1)),
                    score=float(score),
                )
            )
        return out
