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


def release_session(abandon: bool = False) -> int:
    """잠긴 세션을 푼다 — `--release-session`.

    백엔드에 세션 TTL이 없어서, 측정 전송이 실패한 채 프로세스가 끝나면 설비가 잠긴
    상태로 남는다. 그 상태에서는 **아무도 새 측정을 시작할 수 없다**(409 ALREADY_OCCUPIED).
    사람이 푸는 유일한 경로다.

    `--abandon`은 마지막 수단이다. 백엔드는 **측정이 저장된 세션만** 닫아주므로, 측정
    없이 잠긴 세션은 `unreliable` 측정을 하나 남겨야 풀린다. **없는 측정을 지어내는
    것이 아니라 "이 세션은 측정에 실패했다"를 기록하는 것**이라 status가 unreliable이다.
    """
    from station.rest_client import (StationApiError, active_session, close_session,
                                     post_measurement)

    session = active_session()
    if not session:
        print("활성 세션이 없습니다 — 설비는 비어 있습니다.")
        return 0
    session_id = session.get("sessionId")
    print(f"활성 세션: sessionId={session_id} cargoId={session.get('cargoId')}")

    try:
        close_session(session_id)
        print("세션을 닫았습니다.")
        return 0
    except StationApiError as e:
        if not (e.status == 409 and "MEASUREMENT_NOT_COMPLETED" in e.body):
            print(f"세션 종료 실패: {e}", file=sys.stderr)
            return 1

    print("이 세션엔 측정 결과가 없어 백엔드가 종료를 거부합니다.", file=sys.stderr)
    if not abandon:
        print("  --abandon 을 주면 unreliable 측정을 남기고 강제로 풉니다.", file=sys.stderr)
        return 1

    from datetime import datetime, timezone
    stamp = datetime.now(timezone.utc).astimezone()
    abandoned = {
        "measurement_id": f"abandoned-{session_id}",
        "status": "unreliable",
        "measured_at": stamp.isoformat(timespec="seconds"),
        "dimensions": None,
        "tipping": None,
    }
    try:
        post_measurement(abandoned)
        close_session(session_id)
    except StationApiError as e:
        print(f"강제 해제 실패: {e}", file=sys.stderr)
        return 1
    print(f"unreliable 측정을 남기고 세션을 풀었습니다 (measurementId={abandoned['measurement_id']}).")
    return 0


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
    parser.add_argument("--cargo-id",
                        help="이 측정의 화물 ID. 주면 측정 전에 세션을 열고 끝나면 반드시 "
                             "닫는다. 안 주면 남이 연 세션에 측정이 붙는다(오귀속 위험)")
    parser.add_argument("--release-session", action="store_true",
                        help="잠긴 측정 세션을 조회·해제한다 (측정은 하지 않는다)")
    parser.add_argument("--abandon", action="store_true",
                        help="--release-session 전용. 측정이 없어 닫히지 않는 세션을 "
                             "unreliable 측정을 남겨 강제로 푼다")
    args = parser.parse_args(argv)

    if args.probe:
        probe_cameras()
        return 0
    if args.release_session:
        return release_session(abandon=args.abandon)
    if not args.once:
        parser.error("--once 또는 --probe를 지정하세요 (상시 서비스 모드는 추후)")

    cfg = StationConfig()

    def measure_and_emit() -> dict | None:
        """측정하고 결과를 stdout·`--out`으로 낸다. 이미지 로드 실패면 None."""
        if args.image:
            frame = cv2.imread(str(args.image))
            if frame is None:
                print(f"이미지를 읽을 수 없습니다: {args.image}", file=sys.stderr)
                return None
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
        return payload

    def publish(payload: dict) -> bool:
        # status != ok도 보낸다 — 백엔드가 status별 검증을 하고, 재측정 판단에 쓴다.
        from station.rest_client import send
        ok = send(payload)
        print(f"[publish] {'성공' if ok else '실패'} "
              f"POST /api/stations/measurements ({payload.get('measurement_id')})",
              file=sys.stderr)
        return ok

    if not (args.publish and args.cargo_id):
        payload = measure_and_emit()
        if payload is None:
            return 1
        if not args.publish:
            return 0
        print("[publish] ⚠️ --cargo-id 없이 보냅니다 — 백엔드는 요청의 화물을 묻지 않고 "
              "**현재 활성 세션**에 붙입니다. 남이 연 세션이 있으면 그 화물로 잘못 "
              "기록되고, 사후에 알아낼 방법이 없습니다.", file=sys.stderr)
        return 0 if publish(payload) else 1

    # **세션을 측정 앞에 연다** (2026-07-31 팀 결정). 백엔드 문서상 정상 흐름
    # ("세션 시작 → 측정 → 결과 등록")이고, 세션이 **"지금 이 화물을 측정 중"** 이라는
    # 뜻을 갖는다. 얻는 것 둘:
    #   ① 관제가 `GET /sessions/active`로 설비 점유를 실시간 표시할 수 있다.
    #   ② 설비가 이미 점유 중이면 **측정을 시작하기 전에** 409로 튕겨 헛수고를 막는다.
    #
    # ⚠️ 대가: 측정 도중 예외(카메라 미개방·해상도 불일치·모델 파일 없음)가 나면 측정이
    # 저장되지 않은 채 세션만 열려 **백엔드가 종료를 거부한다**(MEASUREMENT_NOT_COMPLETED).
    # `measurement_session`이 세션 ID와 복구 명령을 찍는다. 대안으로 실패 시 unreliable
    # 측정을 남겨 자동 해제하는 방식도 검토했으나, **측정 실패 행을 DB에 쌓지 않기로**
    # 했다. 근본 해결(TTL·강제 해제)은 S15P11A304-172.
    from station.rest_client import SessionNotReleased, StationApiError, measurement_session

    try:
        with measurement_session(args.cargo_id):
            payload = measure_and_emit()
            if payload is None:
                return 1
            ok = publish(payload)
    except StationApiError as e:
        # 세션 열기 실패(대개 409 ALREADY_OCCUPIED) — 측정은 시작도 안 했다.
        print(f"[station-api] 세션을 열 수 없어 측정을 건너뜁니다: {e}", file=sys.stderr)
        return 1
    except SessionNotReleased:
        return 2        # 잠긴 세션이 남았다 — 성공(0)·측정실패(1)와 구분한다
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
