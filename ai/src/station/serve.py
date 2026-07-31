"""스테이션 측정 실행 — 하드웨어·모델을 파이프라인에 배선하는 진입점.

사용 (ai/ 에서, ai_env):
    python src/station/serve.py --once                 # 카메라+TF-Nova 실측 1회
    python src/station/serve.py --once --image x.jpg   # 저장된 사진으로 (카메라 없이)
    python src/station/serve.py --once --distance 150  # 거리 고정값으로 (센서 없이)
    python src/station/serve.py --probe                # 카메라 인덱스 확인용 프리뷰 저장

결과 JSON은 stdout(및 --out 파일)으로 낸다. 백엔드 전송(MQTT/HTTP)은 규격
합의(S15P11A304-91) 후 붙인다 — 지금은 출력까지가 범위.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

# 직접 실행(python src/station/serve.py)도 되게 src를 경로에 얹는다
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import cv2  # noqa: E402

from perception.tfnova import Measurement, MeasurementUnreliable, TfNova  # noqa: E402
from station.config import StationConfig  # noqa: E402
from station.detector import OnnxDetector  # noqa: E402
from station.pipeline import build_payload  # noqa: E402
from station.tilt import estimate_roll_deg  # noqa: E402


def capture(cfg: StationConfig) -> "cv2.typing.MatLike":
    cap = cv2.VideoCapture(cfg.camera_index, cv2.CAP_DSHOW)
    if not cap.isOpened():
        raise RuntimeError(f"카메라 index {cfg.camera_index}를 열 수 없습니다 (--probe로 확인)")
    try:
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, cfg.frame_width)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, cfg.frame_height)
        for _ in range(cfg.warmup_frames):   # 자동 노출 안정화 (실측: 안 하면 어둡다)
            cap.read()
        ok, frame = cap.read()
        if not ok:
            raise RuntimeError("프레임 캡처 실패")
        h, w = frame.shape[:2]
        if (w, h) != (cfg.frame_width, cfg.frame_height):
            # 해상도가 다르면 캘리브레이션(fx/fy)이 무효다 — 조용히 넘어가면 치수가 다 틀린다
            raise RuntimeError(
                f"프레임 {w}x{h} ≠ 캘리브레이션 기준 {cfg.frame_width}x{cfg.frame_height}")
        return frame
    finally:
        cap.release()


def read_distance(cfg: StationConfig) -> Measurement | None:
    try:
        with TfNova(cfg.tfnova_port) as sensor:
            return sensor.measure(cfg.tfnova_seconds, scale=cfg.tfnova_scale,
                                  offset_cm=cfg.tfnova_offset_cm)
    except MeasurementUnreliable as e:
        print(f"[거리 측정 불가] {e}", file=sys.stderr)
        return None
    except Exception as e:  # 포트 점유·미연결 등
        print(f"[TF-Nova 오류] {e}", file=sys.stderr)
        return None


def probe_cameras(max_index: int = 3) -> None:
    for idx in range(max_index):
        cap = cv2.VideoCapture(idx, cv2.CAP_DSHOW)
        if not cap.isOpened():
            print(f"index {idx}: 안 열림")
            continue
        for _ in range(15):
            cap.read()
        ok, frame = cap.read()
        if ok:
            path = f"camera_probe_{idx}.jpg"
            cv2.imwrite(path, frame)
            h, w = frame.shape[:2]
            print(f"index {idx}: {w}x{h} → {path} (열어서 어느 카메라인지 확인)")
        cap.release()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="측정 스테이션 (FR-101-5)")
    parser.add_argument("--once", action="store_true", help="1회 측정 후 종료")
    parser.add_argument("--probe", action="store_true", help="카메라 인덱스 확인")
    parser.add_argument("--image", type=Path, help="카메라 대신 이미지 파일 사용")
    parser.add_argument("--distance", type=float, help="TF-Nova 대신 고정 거리(cm) 사용")
    parser.add_argument("--out", type=Path, help="결과 JSON 저장 경로")
    parser.add_argument("--save-frame", type=Path, help="캡처 프레임 저장 경로 (디버그)")
    parser.add_argument("--publish", action="store_true",
                        help="측정 결과를 백엔드로 전송 "
                             "(POST /api/stations/measurements, STATION_API_BASE 환경변수)")
    parser.add_argument("--session-id",
                        help="이미 열려 있는 측정 세션의 sessionId. --publish 에 필수 "
                             "(--cargo-id 로 새로 열 수도 있다)")
    parser.add_argument("--cargo-id",
                        help="세션을 새로 열 화물 ID. 열고 받은 sessionId 로 측정을 전송한다")
    args = parser.parse_args(argv)

    if args.probe:
        probe_cameras()
        return 0
    if not args.once:
        parser.error("--once 또는 --probe를 지정하세요 (상시 서비스 모드는 추후)")

    cfg = StationConfig()

    if args.image:
        frame = cv2.imread(str(args.image))
        if frame is None:
            print(f"이미지를 읽을 수 없습니다: {args.image}", file=sys.stderr)
            return 1
    else:
        frame = capture(cfg)
    if args.save_frame:
        cv2.imwrite(str(args.save_frame), frame)

    if args.distance is not None:
        distance = Measurement(distance_cm=args.distance, std_cm=0.0,
                               frames_used=0, frames_seen=0)
    else:
        distance = read_distance(cfg)

    detector = OnnxDetector(
        cfg.model_path, cfg.input_size, cfg.score_threshold,
        cfg.class_names, cfg.norm_mean, cfg.norm_std,
        class_thresholds=cfg.class_score_thresholds,
    )
    detections = detector.detect(frame)

    # 카메라 롤 추정 — 파렛트 상판이 실제 수평이라는 점을 기준면으로 쓴다.
    # 파렛트가 없거나 추정이 불안정하면 None이고, 그러면 치수 보정을 건너뛴다.
    pallets = [d for d in detections
               if d.label == "pallet" and d.score >= cfg.threshold_for("pallet")]
    tilt_deg = None
    if pallets:
        best = max(pallets, key=lambda d: d.score)
        # 박스가 상판을 가리는 구간은 제외한다 — 안 그러면 편심 배치에서 각도가 뒤집힌다
        occluders = [d.box for d in detections
                     if d.label == "box" and d.score >= cfg.threshold_for("box")]
        tilt_deg = estimate_roll_deg(frame, best.box, occluders=occluders)

    payload = build_payload(detections, distance, cfg, tilt_deg=tilt_deg)

    text = json.dumps(payload, ensure_ascii=False, indent=2)
    print(text)
    if args.out:
        args.out.write_text(text, encoding="utf-8")

    if args.publish:
        # status != ok도 보낸다 — 백엔드가 status별 검증을 하고, 재측정 판단에 쓴다.
        from station.rest_client import StationApiError, open_session, send

        # sessionId 를 **여기서 한 번 정하고** 그대로 쓴다. 전송 직전에 "지금 활성 세션"을
        # 다시 읽지 않는다 — 그 경로가 늦은 측정을 새 세션에 귀속시킨다.
        session_id = args.session_id
        if not session_id and args.cargo_id:
            try:
                session_id = open_session(args.cargo_id)
                print(f"[publish] 세션 생성: sessionId={session_id}, cargoId={args.cargo_id}",
                      file=sys.stderr)
            except StationApiError as e:
                print(f"[publish] 세션 생성 실패 {e}", file=sys.stderr)
                return 1
        if not session_id:
            print("[publish] --session-id 또는 --cargo-id 가 필요하다. "
                  "sessionId 없이는 측정을 전송하지 않는다.", file=sys.stderr)
            return 1

        ok = send(payload, session_id)
        print(f"[publish] {'성공' if ok else '실패'} "
              f"POST /api/stations/measurements ({payload.get('measurement_id')}, "
              f"sessionId={session_id})", file=sys.stderr)
        if not ok:
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
