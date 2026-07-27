"""TF-Nova 거리계 연동 (FR-103-1).

Benewake TF-Nova는 단일 점 거리계다 (FoV 14°×1°, 정확도 ±5cm, 반복 정밀도
<1cm 1σ, 기본 100Hz). 매뉴얼과 실측(2026-07-20, COM3 115200에서 199Hz 수신)으로
확인한 프레임 형식:

    바이트:  0     1     2       3       4       5       6     7            8
            0x59  0x59  Dist_L  Dist_H  Peak_L  Peak_H  Temp  Confidence  Checksum

- Dist: cm 단위 리틀엔디언. 측정 실패 시 65535
- Confidence: 0~100 — 불량 측정을 걸러내는 필터로 쓴다
- Checksum: 앞 8바이트 합의 하위 1바이트

정확도 ±5cm는 계통 오차를 포함한 값이고 무작위 성분은 <1cm(1σ)뿐이다. 그래서:

- **계통 오차(비율)는 스케일 캘리브레이션으로 제거한다** — 자로 잰 거리와 원값의
  비를 구해 원값에 곱한다 (CLI의 ``--calibrate``).
- **무작위 성분은 다중 프레임 평균으로 줄인다** — 200Hz면 0.25초에 ~50프레임,
  잡음이 1/√50 ≈ 0.14cm로 떨어진다.

이 둘을 적용한 실효 오차가 치수 측정 KPI(미니어처 환산 최악 ≤4mm)를 좌우한다.

파싱(``FrameParser``)은 시리얼과 분리돼 있어 하드웨어 없이 테스트할 수 있다.
"""

from __future__ import annotations

import statistics
import sys
import time
from dataclasses import dataclass

HEADER = b"\x59\x59"
FRAME_LENGTH = 9
INVALID_DISTANCE = 0xFFFF

DEFAULT_BAUD = 115200
DEFAULT_MIN_CONFIDENCE = 90
DEFAULT_MIN_FRAMES = 10

# 계통 오차 = 비율(스케일) 성분. 2026-07-23 스테이션 마운트 재캘리(카메라 렌즈
# 기준 줄자, 원거리는 깨끗한 벽):
#
#   실제(cm)   55    70    77    120   200
#   원값(cm)   53    67    75    116   194
#   원값/실제  0.96  0.96  0.97  0.97  0.97
#
# 원값 ≈ 0.968×실제 (절편≈0) → 거리에 비례해 과소하게 읽는다고 봤다.
#
# **2026-07-27 리그 마운트 재캘리 — 오프셋으로 정정.** 카메라 각도를 표준값으로
# 고정하고 Nova 수평을 다시 잡은 뒤 박스 정면을 정조준해 3점을 쟀다:
#
#   실제(cm)   140     181     210
#   원값(cm)   137.0   178.0   207.0
#   차이(cm)   −3.0    −3.0    −3.0     ← 일정 = 오프셋
#   원값/실제  0.979   0.983   0.986    ← 거리마다 달라짐 = 비율 아님
#
# 차이가 전 구간 −3.0cm로 일정하므로 **비율이 아니라 고정 오프셋**이다. 원인은
# Nova 렌즈가 카메라 기준면보다 앞에 있는 것(줄자는 카메라 기준으로 잰다).
# 그래서 실제 = 원값 × SCALE + OFFSET 으로 계산하고, 리그에서는 SCALE=1.0을 쓴다.
#
# ⚠️ 07-23 탁상 마운트 5점(55~200cm)에서는 차이가 −2→−6cm로 커져 비율(0.968)로
# 봤었다. 오늘 데이터는 셋업 정비 후 std 0.12~0.23으로 훨씬 안정적이라 이쪽을 채택.
# **스케일·오프셋 모두 마운트에 따라 달라진다 — 리그를 옮기면 반드시 재캘리.**
DEFAULT_SCALE = 1.0
DEFAULT_OFFSET_CM = 3.0   # 리그 마운트 실측(2026-07-27). 실제 = 원값 + 3.0


class MeasurementUnreliable(Exception):
    """유효 프레임이 부족해 거리를 신뢰할 수 없다."""


@dataclass(frozen=True)
class Frame:
    """프레임 하나의 해석 결과."""

    distance_cm: int
    peak: int
    temperature_c: int
    confidence: int

    @property
    def valid(self) -> bool:
        # 65535 = 측정 실패. 0 = 대상 없음/신호 미약(매뉴얼 명시) — 신뢰도가
        # 100으로 나오더라도 거리값이 아니므로 버린다. 실측에서 확인됨.
        return 0 < self.distance_cm < INVALID_DISTANCE


@dataclass(frozen=True)
class Measurement:
    """여러 프레임을 합친 거리 측정값."""

    distance_cm: float   # 스케일 보정 반영 후
    std_cm: float        # 사용한 프레임들의 표준편차 (측정 안정성 지표)
    frames_used: int
    frames_seen: int     # 필터 전 전체 (수신 품질 파악용)

    def __str__(self) -> str:
        return (f"{self.distance_cm:.1f}cm ±{self.std_cm:.2f} "
                f"({self.frames_used}/{self.frames_seen} 프레임)")


class FrameParser:
    """바이트 스트림에서 프레임을 골라낸다.

    시리얼 read()가 프레임 경계와 무관하게 잘라 주므로 조각을 이어 붙이며
    헤더를 찾는다. 체크섬이 틀리면 그 지점의 헤더만 버리고 다음 후보로
    재동기화한다 — 통째로 버리면 뒤따르는 정상 프레임까지 잃는다.
    """

    def __init__(self) -> None:
        self._buffer = bytearray()
        self.checksum_failures = 0

    def feed(self, data: bytes) -> list[Frame]:
        self._buffer += data
        frames: list[Frame] = []

        while True:
            start = self._buffer.find(HEADER)
            if start < 0:
                # 헤더가 없다. 끝의 0x59 하나는 다음 조각과 이어질 수 있으니 남긴다.
                del self._buffer[:-1]
                break
            if len(self._buffer) - start < FRAME_LENGTH:
                del self._buffer[:start]
                break

            raw = bytes(self._buffer[start:start + FRAME_LENGTH])
            if sum(raw[:8]) & 0xFF != raw[8]:
                # 가짜 헤더일 수 있다. 잡음의 0x59와 진짜 헤더 첫 바이트가 겹치면
                # 한 바이트 앞에서 매칭되는데, 2바이트를 건너뛰면 진짜 헤더를 밟고
                # 지나가 뒤따르는 정상 프레임까지 잃는다. 1바이트만 전진한다.
                self.checksum_failures += 1
                del self._buffer[:start + 1]
                continue

            frames.append(
                Frame(
                    distance_cm=raw[2] | (raw[3] << 8),
                    peak=raw[4] | (raw[5] << 8),
                    temperature_c=raw[6],
                    confidence=raw[7],
                )
            )
            del self._buffer[:start + FRAME_LENGTH]

        return frames


def aggregate(
    frames: list[Frame],
    min_confidence: int = DEFAULT_MIN_CONFIDENCE,
    min_frames: int = DEFAULT_MIN_FRAMES,
    scale: float = 1.0,
    offset_cm: float = 0.0,
) -> Measurement:
    """프레임 묶음에서 거리 하나를 낸다.

    측정 실패(65535)와 저신뢰 프레임을 버린 뒤 **중앙값**을 쓴다. 평균은 순간
    튐(사람이 지나감, 반사면)에 끌려가지만 중앙값은 버틴다.

    보정은 ``실제 = 원값 × scale + offset_cm``. 마운트에 따라 계통 오차가 비율로도
    오프셋으로도 나타나므로 둘 다 지원한다 — 리그 마운트는 오프셋이 맞다(모듈 상단).
    """
    usable = [f for f in frames if f.valid and f.confidence >= min_confidence]
    if len(usable) < min_frames:
        raise MeasurementUnreliable(
            f"유효 프레임 {len(usable)}개 (최소 {min_frames}). "
            f"수신 {len(frames)}개 중 측정 실패·저신뢰(<{min_confidence})를 제외한 결과다. "
            "대상이 측정 범위(0.1~4m)에 있는지, 빔이 대상을 향하는지 확인하라."
        )

    distances = [f.distance_cm for f in usable]
    return Measurement(
        distance_cm=statistics.median(distances) * scale + offset_cm,
        std_cm=statistics.pstdev(distances),
        frames_used=len(usable),
        frames_seen=len(frames),
    )


class TfNova:
    """시리얼 포트에서 TF-Nova를 읽는다.

    pyserial은 여기서만 쓰므로 지연 임포트한다 — 파서·집계 테스트는
    하드웨어도 pyserial도 필요 없다.
    """

    def __init__(self, port: str, baud: int = DEFAULT_BAUD, timeout: float = 0.05) -> None:
        import serial

        self._serial = serial.Serial(port, baud, timeout=timeout)
        self._parser = FrameParser()

    def close(self) -> None:
        self._serial.close()

    def __enter__(self) -> TfNova:
        return self

    def __exit__(self, *exc) -> None:
        self.close()

    def collect(self, duration_s: float) -> list[Frame]:
        """duration_s 동안 수신한 프레임 전부."""
        self._serial.reset_input_buffer()
        frames: list[Frame] = []
        deadline = time.monotonic() + duration_s
        while time.monotonic() < deadline:
            frames += self._parser.feed(self._serial.read(256))
        return frames

    def measure(
        self,
        duration_s: float = 0.25,
        min_confidence: int = DEFAULT_MIN_CONFIDENCE,
        scale: float = DEFAULT_SCALE,
        offset_cm: float = DEFAULT_OFFSET_CM,
    ) -> Measurement:
        """짧게 수집해 거리 하나를 낸다. 기본 0.25초 ≈ 50프레임."""
        return aggregate(
            self.collect(duration_s), min_confidence=min_confidence,
            scale=scale, offset_cm=offset_cm,
        )


def main(argv: list[str] | None = None) -> int:
    import argparse

    parser = argparse.ArgumentParser(description="TF-Nova 거리 측정 (FR-103-1)")
    parser.add_argument("--port", default="COM3")
    parser.add_argument("--seconds", type=float, default=1.0, help="수집 시간 (기본 1초)")
    parser.add_argument("--min-confidence", type=int, default=DEFAULT_MIN_CONFIDENCE)
    parser.add_argument("--scale", type=float, default=DEFAULT_SCALE,
                        help=f"캘리브레이션 비율 계수 (기본 {DEFAULT_SCALE:g}, "
                             "원값에 곱한다)")
    parser.add_argument("--offset", type=float, default=DEFAULT_OFFSET_CM,
                        metavar="CM",
                        help=f"고정 오프셋 보정 (기본 {DEFAULT_OFFSET_CM:g}cm, "
                             "scale 적용 후 더한다)")
    parser.add_argument("--calibrate", type=float, metavar="TRUE_CM",
                        help="자로 잰 실제 거리. 지정하면 오프셋·비율을 둘 다 계산해 준다")
    args = parser.parse_args(argv)

    import serial

    try:
        with TfNova(args.port) as sensor:
            frames = sensor.collect(args.seconds)
    except serial.SerialException as e:
        print(f"포트 {args.port}를 열 수 없습니다: {e}", file=sys.stderr)
        print("뷰어 등 다른 프로그램이 포트를 잡고 있으면 닫고 다시 실행하세요.",
              file=sys.stderr)
        return 1

    if not frames:
        print("프레임을 받지 못했습니다. 포트·배선을 확인하세요.", file=sys.stderr)
        return 1

    rate = len(frames) / args.seconds
    invalid = sum(1 for f in frames if not f.valid)
    low_conf = sum(1 for f in frames if f.valid and f.confidence < args.min_confidence)
    print(f"수신 {len(frames)}프레임 ({rate:.0f} Hz)  "
          f"측정 실패 {invalid}  저신뢰 {low_conf}  "
          f"온도 {frames[-1].temperature_c}°C")

    try:
        m = aggregate(frames, min_confidence=args.min_confidence,
                      scale=args.scale, offset_cm=args.offset)
    except MeasurementUnreliable as e:
        print(f"측정 불가: {e}", file=sys.stderr)
        return 1

    print(f"거리: {m}")

    if args.calibrate is not None:
        raw_median = (m.distance_cm - args.offset) / args.scale   # 보정 전 원값
        diff = args.calibrate - raw_median
        ratio = args.calibrate / raw_median
        print(f"\n[캘리브레이션] 실제 {args.calibrate:g}cm, 센서 원값 {raw_median:.1f}cm")
        print(f"  → 오프셋 모델: {diff:+.1f}cm   (--offset {diff:.1f} --scale 1.0)")
        print(f"  → 비율 모델  : {ratio:.4f}     (--scale {ratio:.4f} --offset 0)")
        print("  **여러 거리에서 반복해 둘 중 어느 쪽이 일정한지 볼 것** — 차이(cm)가"
              " 일정하면 오프셋, 비율이 일정하면 스케일이다.")
        print(f"  현재 설정(scale {args.scale:g}, offset {args.offset:+g}) 적용값"
              f" {m.distance_cm:.1f}cm → 실제 대비 {m.distance_cm - args.calibrate:+.1f}cm")

    return 0


if __name__ == "__main__":
    sys.exit(main())
