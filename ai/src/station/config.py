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
    # 거리 보정(FR-103-1): 실제 = 원값 × scale + offset.
    # **리그 마운트 3점 재캘리 2026-07-27** (카메라 각도 표준·Nova 수평 고정 후,
    # 박스 정면 정조준): 실제 140/181/210 → 원값 137.0/178.0/207.0, 차이 전부 −3.0cm.
    # 차이가 일정 = 고정 오프셋(Nova 렌즈가 카메라 기준면보다 앞). 비율은 1.0219→
    # 1.0145로 거리마다 달라져 스케일 모델은 기각. 그래서 scale 1.0 + offset 3.0.
    # ⚠️ 마운트를 바꾸면 반드시 박스 표적으로 --calibrate 3점 재확인.
    tfnova_scale: float = 1.0
    tfnova_offset_cm: float = 3.0
    tfnova_seconds: float = 0.5

    # --- 모델 (ONNX, mmdeploy end2end: dets[x1,y1,x2,y2,score] + labels) ---
    model_path: Path = Path("models/end2end.onnx")
    input_size: int = 800   # 실험7(증강) end2end.onnx는 800 입력으로 export
    score_threshold: float = 0.5
    # 클래스별 임계 — 파렛트만 낮춘다. 검은 플라스틱 격자라 박스처럼 큰 단색면이 없어
    # 점수가 낮게 잡힌다. 리그 평가셋 30장 실측(S15P11A304-148, docs/ai/rig-eval-map.md):
    #   파렛트 0.4 → 정밀도 96.8% / 재현율 100%(30/30)
    #   파렛트 0.5 → 정밀도 96.7% / 재현율  96.7% (1개 놓침)
    #   박스   0.5 → 정밀도 93.9% / 재현율  95.5% (0.4로 낮추면 정밀도 91.5%로 하락)
    class_score_thresholds: dict[str, float] = field(
        default_factory=lambda: {"pallet": 0.4})
    # 학습 클래스 순서 (configs/datasets.yaml: box=1, pallet=2 → 라벨 0, 1)
    class_names: tuple[str, ...] = ("box", "pallet")
    # RTMDet 표준 전처리 상수 (BGR, to_rgb=False)
    norm_mean: tuple[float, float, float] = (103.53, 116.28, 123.675)
    norm_std: tuple[float, float, float] = (57.375, 57.12, 58.395)

    # --- 판정 ---
    calib: CameraCalib = field(default_factory=CameraCalib)
    miniature_scale: int = 10          # 실물 ÷10 = 미니어처 (명세 §2.1)
    eccentric_threshold: float = 0.3   # 편하중 임계 (load_balance 기본과 동일)
