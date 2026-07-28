"""치수 정확도 평가 — 촬영본을 실측 정답과 대조해 오차를 낸다 (FR-103-4, ≤4mm).

`shoot.py --tfnova`로 찍은 폴더(이미지 + session.csv의 프레임별 거리)를 받아,
각 프레임을 측정하고 **자로 잰 박스 실측치**와 비교한다.

**박스·방향은 자동 식별한다.** 촬영 중 어떤 박스를 어느 방향으로 뒀는지 일일이
기록하지 않아도 되게, 측정된 (H, W)를 아래 18가지 조합(박스 3종 × 세운 방향 6)
중 가장 가까운 것에 매칭한다. 박스 간 치수 차가 수십 cm인데 기대 오차는 수 cm라
매칭이 모호할 일이 없다. 매칭 후 남는 잔차가 곧 측정 오차다.

⚠️ TF-Nova가 박스를 빗나가 뒤 벽을 읽은 프레임(거리 이상치)은 제외한다 — 거리가
틀리면 측정도 비례해 틀리므로 정확도 평가에 쓸 수 없다.

    python src/station/dim_eval.py --dir data/raw/station_dim_eval/20260728
"""

from __future__ import annotations

import argparse
import csv
import sys
from dataclasses import replace
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import cv2  # noqa: E402

from perception.tfnova import Measurement  # noqa: E402
from station.config import StationConfig  # noqa: E402
from station.detector import OnnxDetector  # noqa: E402
from station.pipeline import build_payload  # noqa: E402
from station.tilt import estimate_roll_deg  # noqa: E402

# 자로 잰 실물 치수 mm — docs/ai/station-dim-eval-plan.md
BOXES = {
    "2호": (184, 267, 152),
    "2-1호": (342, 255, 105),
    "쿠팡": (305, 405, 505),
}

# 거리 이상치 판정: 리그 촬영 범위를 크게 벗어나면 빔이 박스를 빗나간 것
MAX_VALID_DISTANCE_CM = 400.0
MAX_VALID_STD_CM = 5.0


def candidates() -> list[tuple[str, float, float]]:
    """(이름, 기대 H_mm, 기대 W_mm) — 박스별로 세울 수 있는 방향 전부.

    어느 변이 수직이 되든(3가지), 정면으로 보이는 변이 둘 중 하나(2가지)."""
    out = []
    for name, dims in BOXES.items():
        for i in range(3):
            h = dims[i]
            rest = [dims[j] for j in range(3) if j != i]
            for w in rest:
                out.append((f"{name} H{h}/W{w}", float(h), float(w)))
    return out


def _measure_frame(row, args, cfg, detector):
    """한 프레임 → payload. 거리 이상치·읽기 실패면 (None, 사유)."""
    raw = row.get("distance_cm", "")
    if not raw:
        return None, "거리 없음"
    dist_cm, std = float(raw), float(row.get("distance_std") or 0)
    if dist_cm > MAX_VALID_DISTANCE_CM or std > MAX_VALID_STD_CM:
        return None, f"거리 이상치 {dist_cm:g}cm — 빔 빗나감"
    frame = cv2.imread(str(args.dir / row["file"]))
    if frame is None:
        return None, "이미지 없음"

    dets = detector.detect(frame)
    pallets = [d for d in dets
               if d.label == "pallet" and d.score >= cfg.threshold_for("pallet")]
    tilt_deg = None
    if pallets:
        occ = [d.box for d in dets
               if d.label == "box" and d.score >= cfg.threshold_for("box")]
        tilt_deg = estimate_roll_deg(
            frame, max(pallets, key=lambda d: d.score).box, occluders=occ)
    return build_payload(
        dets, Measurement(distance_cm=dist_cm, std_cm=std, frames_used=1,
                          frames_seen=1), cfg, tilt_deg=tilt_deg), dist_cm


def _config(args) -> StationConfig:
    """--box-score가 있으면 그 임계로 바꾼 config를 준다 (detector·판정 모두 적용)."""
    cfg = StationConfig()
    if args.box_score is None:
        return cfg
    return replace(cfg, class_score_thresholds={**cfg.class_score_thresholds,
                                                "box": args.box_score})


def _per_box(args) -> int:
    """박스별 개별 측정 채점 — 다중 박스 프레임 평가용.

    각 박스를 실측 정답 18조합 중 최근접에 매칭한다. 잔차가 크면 실제 박스가 아니라
    **배경 오탐**으로 보고 따로 집계한다 (실측: 배경 물체가 score 0.5~0.7로 섞인다)."""
    rows = list(csv.DictReader((args.dir / "session.csv").open(encoding="utf-8")))
    cfg = _config(args)
    detector = OnnxDetector(cfg.model_path, cfg.input_size, cfg.score_threshold,
                            cfg.class_names, cfg.norm_mean, cfg.norm_std,
                            class_thresholds=cfg.class_score_thresholds)
    cands = candidates()

    matched, suspects, frames = [], [], 0
    for row in rows:
        payload, info = _measure_frame(row, args, cfg, detector)
        if payload is None or not payload.get("box_measurements"):
            continue
        frames += 1
        num = row["file"].rsplit("_", 1)[-1].split(".")[0]
        for b in payload["box_measurements"]:
            mh, mw = b["height_cm"] * 10, b["width_cm"] * 10
            name, eh, ew = min(cands, key=lambda c: abs(mh - c[1]) + abs(mw - c[2]))
            dh, dw = (mh - eh) / 10, (mw - ew) / 10
            rec = {"num": num, "dist": info, "score": b["score"], "match": name,
                   "mh": mh, "mw": mw, "eh": eh, "ew": ew, "dh": dh, "dw": dw}
            (suspects if max(abs(dh), abs(dw)) > args.fp_threshold_mm
             else matched).append(rec)

    print(f"프레임 {frames}장 / 박스 감지 {len(matched) + len(suspects)}개\n")
    print(f"{'num':>5s} {'dist':>5s} {'sc':>5s} {'박스·방향':22s} "
          f"{'측정 H×W(mm)':>17s} {'기대':>11s} {'오차(mm)':>16s}")
    print("-" * 92)
    for r in matched:
        flag = "" if max(abs(r["dh"]), abs(r["dw"])) <= args.tolerance_mm else "  <-- 초과"
        print(f"{r['num']:>5s} {r['dist']:5.0f} {r['score']:5.2f} {r['match']:22s} "
              f"{r['mh']:7.0f} x{r['mw']:7.0f} {r['eh']:5.0f}x{r['ew']:5.0f} "
              f"{r['dh']:+7.2f} {r['dw']:+7.2f}{flag}")

    if matched:
        hs = [abs(r["dh"]) for r in matched]
        ws = [abs(r["dw"]) for r in matched]
        ok = sum(1 for r in matched
                 if max(abs(r["dh"]), abs(r["dw"])) <= args.tolerance_mm)
        print("\n=== 실제 박스로 매칭된 것 (미니어처 mm) ===")
        print(f"  높이 평균 {sum(hs)/len(hs):.2f} 최대 {max(hs):.2f} / "
              f"너비 평균 {sum(ws)/len(ws):.2f} 최대 {max(ws):.2f}")
        print(f"  KPI(≤{args.tolerance_mm:g}mm) 통과: {ok}/{len(matched)} "
              f"({ok/len(matched):.1%})")

    if suspects:
        print(f"\n=== 배경 오탐 의심 {len(suspects)}개 "
              f"(어느 정답과도 {args.fp_threshold_mm:g}mm 넘게 어긋남) ===")
        for r in suspects:
            print(f"  {r['num']:>5s} score {r['score']:.2f}  "
                  f"측정 {r['mh']:.0f}x{r['mw']:.0f}mm  (최근접 {r['match']})")
        lo = sum(1 for r in suspects if r["score"] < 0.7)
        print(f"  → score<0.7 이 {lo}/{len(suspects)}개. "
              f"박스 임계를 올리면 대부분 걸러진다.")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="치수 정확도 평가")
    parser.add_argument("--dir", type=Path, required=True, help="촬영 폴더")
    parser.add_argument("--tolerance-mm", type=float, default=4.0,
                        help="KPI 허용 오차(미니어처 mm, 기본 4)")
    # 박스가 2개 이상 잡힌 프레임은 hull이 여러 물체를 감싸므로 **단일 박스 실측치와
    # 비교할 수 없다**(치우던 사람 팔·다른 박스가 섞인 프레임). 치수 KPI는 단일 박스
    # 기준이라 기본적으로 n=1만 채점한다. 다중 박스 정확도는 box_measurements로 따로 본다.
    parser.add_argument("--all-frames", action="store_true",
                        help="박스 2개 이상 프레임도 포함 (기본: 단일 박스만 채점)")
    # 다중 박스는 hull이 아니라 **박스별**로 재야 한다. --per-box는 프레임의 박스를
    # 하나씩 실측 정답에 매칭해 채점한다(실측 검증: 진짜 박스는 KPI 안, 배경 오탐만 벗어남).
    parser.add_argument("--per-box", action="store_true",
                        help="박스별 개별 측정(box_measurements)을 채점 — 다중 박스 평가용")
    parser.add_argument("--fp-threshold-mm", type=float, default=8.0,
                        help="이 이상 빗나가면 배경 오탐으로 보고 따로 집계 (기본 8mm)")
    # 배경 오탐(실측 score 0.50~0.68)과 진짜 박스(0.77~0.93)가 점수로 갈린다.
    # 다중 박스 측정에서는 임계를 올려 오탐을 빼는 편이 낫다.
    parser.add_argument("--box-score", type=float,
                        help="박스 검출 임계 override (기본: config 값)")
    args = parser.parse_args(argv)

    if args.per_box:
        return _per_box(args)

    rows = list(csv.DictReader((args.dir / "session.csv").open(encoding="utf-8")))
    cfg = _config(args)
    detector = OnnxDetector(cfg.model_path, cfg.input_size, cfg.score_threshold,
                            cfg.class_names, cfg.norm_mean, cfg.norm_std,
                            class_thresholds=cfg.class_score_thresholds)
    cands = candidates()

    results, skipped = [], []
    for row in rows:
        raw = row.get("distance_cm", "")
        if not raw:
            skipped.append((row["file"], "거리 없음"))
            continue
        dist_cm, std = float(raw), float(row.get("distance_std") or 0)
        if dist_cm > MAX_VALID_DISTANCE_CM or std > MAX_VALID_STD_CM:
            skipped.append((row["file"], f"거리 이상치 {dist_cm:g}cm(std {std:g}) — 빔 빗나감"))
            continue

        frame = cv2.imread(str(args.dir / row["file"]))
        if frame is None:
            skipped.append((row["file"], "이미지 없음"))
            continue

        dets = detector.detect(frame)
        pallets = [d for d in dets
                   if d.label == "pallet" and d.score >= cfg.threshold_for("pallet")]
        tilt_deg = None
        if pallets:
            occ = [d.box for d in dets
                   if d.label == "box" and d.score >= cfg.threshold_for("box")]
            tilt_deg = estimate_roll_deg(
                frame, max(pallets, key=lambda d: d.score).box, occluders=occ)

        payload = build_payload(
            dets, Measurement(distance_cm=dist_cm, std_cm=std, frames_used=1,
                              frames_seen=1),
            cfg, tilt_deg=tilt_deg)
        dim = payload.get("dimensions")
        if not dim:
            skipped.append((row["file"], f"측정 불가(status={payload['status']})"))
            continue

        n_boxes = payload["detection"]["box_count"]
        if n_boxes != 1 and not args.all_frames:
            skipped.append((row["file"], f"박스 {n_boxes}개 — hull이 여러 물체를 감쌈"))
            continue

        # 측정값(실물 mm) — 치수는 cm로 나오므로 ×10
        mh, mw = dim["height_cm"] * 10, dim["width_cm"] * 10
        name, eh, ew = min(cands, key=lambda c: abs(mh - c[1]) + abs(mw - c[2]))
        results.append({
            "file": row["file"], "num": row["file"].rsplit("_", 1)[-1].split(".")[0],
            "dist": dist_cm, "match": name,
            "mh": mh, "mw": mw, "eh": eh, "ew": ew,
            # 미니어처 환산 오차 = 실물 오차 mm / 10
            "dh": (mh - eh) / 10, "dw": (mw - ew) / 10,
            "boxes": n_boxes,
        })

    print(f"측정 {len(results)}장 / 제외 {len(skipped)}장\n")
    if skipped:
        print("=== 제외 ===")
        for f, why in skipped[:20]:
            print(f"  {f.rsplit('_', 1)[-1]:12s} {why}")
        if len(skipped) > 20:
            print(f"  ... 외 {len(skipped) - 20}장")

    print(f"\n{'num':>5s} {'dist':>6s} {'박스·방향':22s} {'측정 H×W(mm)':>18s} "
          f"{'기대':>12s} {'미니어처 오차(mm)':>18s}  n")
    print("-" * 100)
    for r in results:
        flag = "" if max(abs(r["dh"]), abs(r["dw"])) <= args.tolerance_mm else "  <-- 초과"
        print(f"{r['num']:>5s} {r['dist']:6.0f} {r['match']:22s} "
              f"{r['mh']:8.0f} x{r['mw']:7.0f} {r['eh']:5.0f}x{r['ew']:5.0f} "
              f"{r['dh']:+8.2f} {r['dw']:+8.2f}  {r['boxes']}{flag}")

    if not results:
        return 0

    # --- 요약 ---
    hs = [abs(r["dh"]) for r in results]
    ws = [abs(r["dw"]) for r in results]
    both = hs + ws
    ok = sum(1 for r in results
             if max(abs(r["dh"]), abs(r["dw"])) <= args.tolerance_mm)
    print("\n=== 요약 (미니어처 환산 mm) ===")
    print(f"  높이 오차  평균 {sum(hs)/len(hs):.2f}  최대 {max(hs):.2f}")
    print(f"  너비 오차  평균 {sum(ws)/len(ws):.2f}  최대 {max(ws):.2f}")
    print(f"  전체       평균 {sum(both)/len(both):.2f}  최대 {max(both):.2f}")
    print(f"  KPI(≤{args.tolerance_mm:g}mm) 통과: {ok}/{len(results)} "
          f"({ok / len(results):.1%})")

    print("\n=== 박스·방향별 ===")
    groups: dict[str, list] = {}
    for r in results:
        groups.setdefault(r["match"], []).append(r)
    for name, g in sorted(groups.items()):
        gh = [abs(x["dh"]) for x in g]
        gw = [abs(x["dw"]) for x in g]
        print(f"  {name:22s} {len(g):3d}장  H오차 평균 {sum(gh)/len(gh):5.2f} "
              f"최대 {max(gh):5.2f}   W오차 평균 {sum(gw)/len(gw):5.2f} 최대 {max(gw):5.2f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
