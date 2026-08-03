"""감지 결과 오버레이 캡처 — 발표·데모용 스틸 생성.

serve.py와 같은 배선(카메라→ONNX→TF-Nova→pipeline)을 타되, JSON 대신
bbox·score·치수를 그린 PNG를 남긴다. 측정 로직은 건드리지 않는다.

    python src/station/annotate.py                      # 카메라+TF-Nova 1회 → annotated.png
    python src/station/annotate.py --image x.jpg        # 저장된 사진으로
    python src/station/annotate.py --distance 150       # 거리 고정값으로 (센서 없이)
    python src/station/annotate.py --out demo.png
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import cv2  # noqa: E402
import numpy as np  # noqa: E402

from perception.tfnova import Measurement  # noqa: E402
from station.config import StationConfig  # noqa: E402
from station.detector import OnnxDetector  # noqa: E402
from station.pipeline import build_payload  # noqa: E402
from station.serve import capture, read_distance  # noqa: E402
from station.tilt import estimate_roll_deg  # noqa: E402

BOX_COLOR = (80, 200, 80)      # BGR — 박스: 초록
PALLET_COLOR = (60, 140, 255)  # 파렛트: 주황
PANEL_BG = (30, 30, 30)


def _label(img: np.ndarray, text: str, x: int, y: int, color: tuple) -> None:
    """bbox 위에 배경 있는 라벨 (cv2는 한글 불가 → 영문)."""
    font, fs, ft = cv2.FONT_HERSHEY_SIMPLEX, 0.9, 2
    (tw, th), _ = cv2.getTextSize(text, font, fs, ft)
    y_top = max(y - th - 10, 0)
    cv2.rectangle(img, (x, y_top), (x + tw + 8, y_top + th + 10), color, -1)
    cv2.putText(img, text, (x + 4, y_top + th + 2), font, fs, (255, 255, 255), ft)


def annotate(frame: np.ndarray, payload: dict) -> np.ndarray:
    img = frame.copy()
    det = payload["detection"]

    for b in det["boxes"]:
        x, y, w, h = b["bbox_px"]
        cv2.rectangle(img, (x, y), (x + w, y + h), BOX_COLOR, 3)
        _label(img, f"box {b['score']:.2f}", x, y, BOX_COLOR)

    if det["pallet"]:
        x, y, w, h = det["pallet"]["bbox_px"]
        cv2.rectangle(img, (x, y), (x + w, y + h), PALLET_COLOR, 3)
        _label(img, f"pallet {det['pallet']['score']:.2f}", x, y, PALLET_COLOR)

    # 하단 정보 패널 — 치수·거리·편하중
    lines = [f"status: {payload['status']}"]
    if payload.get("distance"):
        lines.append(f"distance: {payload['distance']['front_cm']} cm")
    if payload.get("dimensions"):
        d = payload["dimensions"]
        line = (f"W {d['width_cm']} x H {d['height_cm']} cm"
                f"  (mini {d['miniature_width_mm']} x {d['miniature_height_mm']} mm)")
        if d.get("tilt_deg"):        # 파렛트 수평 기준 롤 보정이 걸린 경우
            line += f"   roll {d['tilt_deg']:+.1f} deg corrected"
        lines.append(line)
    if payload.get("load_balance"):
        lb = payload["load_balance"]
        lines.append(f"load: {'ECCENTRIC ' + '/'.join(lb['direction']) if lb['eccentric'] else 'BALANCED'}"
                     f"  (ratio_x {lb['ratio_x']})")

    # 패널은 **상단**에 그린다 — 파렛트는 카메라가 낮아 늘 프레임 하단에 잡히므로
    # 하단 패널은 파렛트 bbox를 가린다(발표에서 보여줘야 할 바로 그 부분).
    font, fs, ft, pad, lh = cv2.FONT_HERSHEY_SIMPLEX, 1.0, 2, 14, 42
    panel_h = pad * 2 + lh * len(lines)
    overlay = img.copy()
    cv2.rectangle(overlay, (0, 0), (img.shape[1], panel_h), PANEL_BG, -1)
    img = cv2.addWeighted(overlay, 0.75, img, 0.25, 0)
    for i, line in enumerate(lines):
        cv2.putText(img, line, (pad, pad + lh * (i + 1) - 10),
                    font, fs, (240, 240, 240), ft)
    return img


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="감지 오버레이 캡처 (발표·데모용)")
    parser.add_argument("--image", type=Path, help="카메라 대신 이미지 파일 사용")
    parser.add_argument("--distance", type=float, help="TF-Nova 대신 고정 거리(cm)")
    # 출력은 관례대로 **실행한 디렉터리**에 떨어진다(입력 파일과 달리 cwd 의존이
    # 문제되지 않는다). 어디에 저장되는지는 실행 끝에 절대경로로 찍어 준다.
    parser.add_argument("--out", type=Path, default=Path("annotated.png"),
                        help="저장 경로 (기본: 현재 디렉터리의 annotated.png)")
    args = parser.parse_args(argv)

    cfg = StationConfig()
    frame = (cv2.imread(str(args.image)) if args.image else capture(cfg))
    if frame is None:
        print(f"이미지를 열 수 없습니다: {args.image}", file=sys.stderr)
        return 1

    detector = OnnxDetector(
        model_path=cfg.model_path, input_size=cfg.input_size,
        score_threshold=cfg.score_threshold, class_names=cfg.class_names,
        norm_mean=cfg.norm_mean, norm_std=cfg.norm_std,
        class_thresholds=cfg.class_score_thresholds,
    )
    detections = detector.detect(frame)
    distance = (Measurement(distance_cm=args.distance, std_cm=0.0,
                            frames_used=1, frames_seen=1)
                if args.distance is not None else read_distance(cfg))

    # serve.py와 같은 배선 — 파렛트 상판을 수평 기준면으로 카메라 롤 보정
    pallets = [d for d in detections
               if d.label == "pallet" and d.score >= cfg.threshold_for("pallet")]
    occluders = [d.box for d in detections
                 if d.label == "box" and d.score >= cfg.threshold_for("box")]
    tilt_deg = (estimate_roll_deg(frame, max(pallets, key=lambda d: d.score).box,
                                  occluders=occluders)
                if pallets else None)
    payload = build_payload(detections, distance, cfg, tilt_deg=tilt_deg)

    # cv2.imwrite는 Windows에서 비ASCII 경로에 조용히 실패한다 → imencode+tofile
    ok, buf = cv2.imencode(args.out.suffix or ".png", annotate(frame, payload))
    if not ok:
        print("이미지 인코딩 실패", file=sys.stderr)
        return 1
    buf.tofile(str(args.out))
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    print(f"\n→ 저장: {args.out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
