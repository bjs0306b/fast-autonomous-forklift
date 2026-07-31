"""검출기 공통 전처리 — letterbox.

`station.detector.OnnxDetector`와 `perception.trt_detector.TrtDetector`가 **같은 코드를
복제**하고 있었다. 원격 추론(스테이션 PC가 letterbox → 젯슨이 추론)을 붙이면서 세 번째
복제가 생길 참이라 한 곳으로 모은다.

**이게 갈라지면 조용히 틀린다.** letterbox 비율이나 pad 값이 어긋나면 학습·추론 도메인이
달라지는데, 증상은 "정확도가 조금 나쁨"이라 원인을 찾기 어렵다.
"""

from __future__ import annotations

import cv2
import numpy as np

PAD_VALUE = 114
"""남는 영역을 채우는 회색. mmdet letterbox 기본값 — 학습 때와 같아야 한다."""


def letterbox(frame: np.ndarray, size: int) -> tuple[np.ndarray, float]:
    """비율을 유지한 채 `size`×`size` 정사각으로 맞춘다.

    반환 `(padded_uint8, scale)`. 좌상단 정렬이고 남는 오른쪽·아래를 `PAD_VALUE`로 채운다.
    검출 결과를 원본 좌표로 되돌리려면 bbox를 `scale`로 나눈다.

    ⚠️ 정규화는 하지 않는다. **uint8 상태로 끊어야** 원격 추론에서 이 배열을 무손실로
    전송할 수 있다(float32 텐서는 4배 크고 압축도 안 된다).
    """
    h, w = frame.shape[:2]
    scale = min(size / w, size / h)
    new_w, new_h = int(round(w * scale)), int(round(h * scale))
    resized = cv2.resize(frame, (new_w, new_h), interpolation=cv2.INTER_LINEAR)
    padded = np.full((size, size, 3), PAD_VALUE, dtype=np.uint8)
    padded[:new_h, :new_w] = resized
    return padded, scale


def to_tensor(padded: np.ndarray, mean: np.ndarray, std: np.ndarray) -> np.ndarray:
    """letterbox 결과 → 정규화된 NCHW float32 텐서.

    같은 uint8 입력이면 어느 기계에서 돌려도 결과가 같다(원소별 float32 연산).
    원격 추론에서 이 단계를 서버(젯슨)가 맡아도 정확도가 달라지지 않는 근거다.
    """
    normalized = (padded.astype(np.float32) - mean) / std
    return np.ascontiguousarray(normalized.transpose(2, 0, 1)[np.newaxis])
