"""스테이션 설정 — 장비·캘리브레이션 상수를 한곳에 모은다.

캘리브레이션 값의 출처는 전지웅의 실측 문서다:

- ``docs/ai/measurement/camera-calibration.md`` — Logitech BRIO 100, 1920×1080,
  체커보드 실측. fx/fy/cx/cy. 검증: 60cm 마우스패드 높이 98.24% 정확도.
- ``docs/ai/measurement/height-estimation-formula.md`` — 치수 산출 공식.

⚠️ 카메라 해상도·화각·디지털 줌이 바뀌면 재캘리브레이션해야 한다 (문서 명시).
그래서 상수를 코드에 흩뿌리지 않고 여기 한곳에 둔다.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

# `ai/` 루트. 이 파일이 `ai/src/station/config.py` 이므로 parents[2] 가 `ai/` 다.
# 모델·데이터 경로를 여기 기준으로 잡아 **실행 위치와 무관하게** 동작시킨다.
_AI_ROOT = Path(__file__).resolve().parents[2]


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
    #
    # ⚠️ 2026-08-06에 이 값이 **0으로 바뀐 채 주석만 1로 남아** 있었다(43fa010,
    # "Refactor forklift teleop protocol" — 제목에 스테이션이 없어 안 보였다).
    # 0은 내장 캠이라 측정이 엉뚱한 화면을 잰다. **주석과 값이 어긋나면 값을 의심할 것.**
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
    # ⚠️ **cwd 에 의존하지 않는다.** 예전엔 `Path("models/end2end.onnx")` 상대경로라
    #    `ai/` 밖에서 실행하면 죽었고, 오류가 onnxruntime 의 "Load model ... failed:
    #    File doesn't exist" 라 "어디서 실행해야 하는지"가 드러나지 않았다(2026-08-03).
    #    이 파일이 `ai/src/station/` 에 있으므로 parents[2] 가 `ai/` 다.
    model_path: Path = field(
        default_factory=lambda: _AI_ROOT / "models" / "end2end.onnx")
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
    # 파렛트 높이(실물 cm). 화물은 **항상 파렛트 위에 실려** 운반되므로, 적재 위치
    # 산출(FR-202)에 넘길 값은 화물 높이가 아니라 **파렛트를 포함한 총높이**다.
    # T-11 표준 **120mm**. 스테이션이 재는 것은 **실물 크기** 화물·파렛트이므로 이 값도
    # 실물 기준이고, 백엔드의 `storage.placement.pallet-height-m: 0.12` 과 같은 값이다.
    #
    # ⚠️ `hardware/pallet_mini.scad` 의 `R_height` 와 **다르다**(거기는 140). 그쪽은
    #    3D 출력된 **미니어처** 파렛트를 실측한 값이다 — 설계는 12mm(=실물 120mm)였는데
    #    출력 공차로 14mm 로 나왔고, 그래서 온보드 포크 높이를 7mm 로 다시 잡았다.
    #    스테이션(실물)과 온보드(미니어처)는 **다른 물건을 재므로 값이 달라도 맞다.**
    #    종전 이 주석이 "T-11 표준 120mm (scad R_height)" 라고 두 값을 같은 것처럼
    #    가리키고 있었다(2026-08-06 정정).
    pallet_height_cm: float = 12.0
    eccentric_threshold: float = 0.3   # 편하중 임계 (load_balance 기본과 동일)

    def threshold_for(self, label: str) -> float:
        """클래스별 검출 임계 — detector와 하류(판정·tilt)가 같은 값을 써야 한다.

        detector가 이미 이 임계로 걸러 넘기지만, pipeline·serve가 판정용으로 다시
        거를 때 전역 0.5를 쓰면 파렛트 0.4~0.5 검출이 감지에는 보이는데 load_balance
        에선 사라지는 이중 게이트가 생긴다. 그래서 같은 헬퍼로 통일한다."""
        return self.class_score_thresholds.get(label, self.score_threshold)
