"""스테이션 설정 — 장비·캘리브레이션 상수를 한곳에 모은다.

캘리브레이션 값의 출처는 전지웅의 실측 문서다:

- ``docs/ai/FR-103-2a-camera-calibration.md`` — Logitech BRIO 100, 1920×1080,
  체커보드 실측. fx/fy/cx/cy. 검증: 60cm 마우스패드 높이 98.24% 정확도.
- ``docs/ai/FR-103-3-height-estimation-formula.md`` — 치수 산출 공식.

⚠️ 카메라 해상도·화각·디지털 줌이 바뀌면 재캘리브레이션해야 한다 (문서 명시).
그래서 상수를 코드에 흩뿌리지 않고 여기 한곳에 둔다.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path


@dataclass(frozen=True)
class CameraCalib:
    """카메라 내부 파라미터 (FR-103-2a 실측, BRIO 100 @1920×1080)."""

    fx: float = 2025.17   # 가로 초점거리 (px)
    fy: float = 2039.07   # 세로 초점거리 (px)
    cx: float = 969.79
    cy: float = 459.43
    # 방사 왜곡 (Jira S15P11A304-72 실측). 뼈대 단계에서는 미적용 — 적용 시
    # cv2.undistort로 프레임을 먼저 펴고 fx/fy를 그대로 쓴다.
    k1: float = 0.10596
    k2: float = -0.39255


@dataclass(frozen=True)
class StationConfig:
    station_id: str = "station-1"

    # --- 카메라 ---
    # 이 PC에는 내장 캠과 BRIO가 함께 있어 인덱스가 섞일 수 있다. 실측(2026-07-22):
    # index 1이 BRIO였음. 장치 구성이 바뀌면 `python -m station.serve --probe`로 재확인.
    camera_index: int = 1
    frame_width: int = 1920    # 캘리브레이션 기준 해상도 — 바꾸면 fx/fy 무효
    frame_height: int = 1080
    warmup_frames: int = 25    # 자동 노출 안정화 전 프레임은 어둡다 (실측)

    # --- TF-Nova ---
    tfnova_port: str = "COM3"
    tfnova_offset_cm: float = -2.0   # 탁상 캘리브레이션(FR-103-1). 리그 장착 후 재확인
    tfnova_seconds: float = 0.5

    # --- 모델 (ONNX, mmdeploy end2end: dets[x1,y1,x2,y2,score] + labels) ---
    model_path: Path = Path("models/end2end.onnx")
    input_size: int = 800   # 실험5(-m@800+SCD, 0.753) end2end.onnx는 800 입력으로 export
    score_threshold: float = 0.5
    # 학습 클래스 순서 (configs/datasets.yaml: box=1, pallet=2 → 라벨 0, 1)
    class_names: tuple[str, ...] = ("box", "pallet")
    # RTMDet 표준 전처리 상수 (BGR, to_rgb=False)
    norm_mean: tuple[float, float, float] = (103.53, 116.28, 123.675)
    norm_std: tuple[float, float, float] = (57.375, 57.12, 58.395)

    # --- 판정 ---
    calib: CameraCalib = field(default_factory=CameraCalib)
    miniature_scale: int = 10          # 실물 ÷10 = 미니어처 (명세 §2.1)
    eccentric_threshold: float = 0.3   # 편하중 임계 (load_balance 기본과 동일)
