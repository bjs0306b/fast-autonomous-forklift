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

- **계통 오차(치우침)는 오프셋 캘리브레이션으로 제거한다** — 자로 잰 거리를 재보고
  차이를 빼면 된다 (CLI의 ``--calibrate``).
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

# 계통 오차 오프셋 (cm). 2026-07-20 탁상 캘리브레이션 실측:
#
#   실제(cm)   30    50~   55    100   170
#   읽음(cm)   28    48~49 52    98    167~168
#   오프셋     -2.0  -1.5  -3.0  -2.0  -2.5
#
# 거리와 무관하게 약 -2cm로 일정 → 고정 오프셋으로 보정한다 (비율 오차였다면
# 170cm에서 -10cm쯤 나왔어야 한다). 보정 후 잔차는 전 구간 ±1cm(분해능 수준).
#
# ⚠️ 오프셋은 기준면(센서 앞면 vs 장착 브래킷)에 따라 달라지므로, 측정 스테이션에
# 장착한 뒤 --calibrate로 재확인할 것.
DEFAULT_OFFSET_CM = -2.0


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

    distance_cm: float   # 캘리브레이션 오프셋 반영 후
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
    offset_cm: float = 0.0,
) -> Measurement:
    """프레임 묶음에서 거리 하나를 낸다.

    측정 실패(65535)와 저신뢰 프레임을 버린 뒤 **중앙값**을 쓴다. 평균은 순간
    튐(사람이 지나감, 반사면)에 끌려가지만 중앙값은 버틴다.

    ``offset_cm``은 캘리브레이션으로 구한 계통 오차다. 측정값에서 뺀다.
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
        distance_cm=statistics.median(distances) - offset_cm,
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
        offset_cm: float = DEFAULT_OFFSET_CM,
    ) -> Measurement:
        """짧게 수집해 거리 하나를 낸다. 기본 0.25초 ≈ 50프레임."""
        return aggregate(
            self.collect(duration_s), min_confidence=min_confidence, offset_cm=offset_cm
        )


def main(argv: list[str] | None = None) -> int:
    import argparse

    parser = argparse.ArgumentParser(description="TF-Nova 거리 측정 (FR-103-1)")
    parser.add_argument("--port", default="COM3")
    parser.add_argument("--seconds", type=float, default=1.0, help="수집 시간 (기본 1초)")
    parser.add_argument("--min-confidence", type=int, default=DEFAULT_MIN_CONFIDENCE)
    parser.add_argument("--offset", type=float, default=DEFAULT_OFFSET_CM,
                        help=f"캘리브레이션 오프셋 cm (기본 {DEFAULT_OFFSET_CM:g}, "
                             "측정값에서 뺀다)")
    parser.add_argument("--calibrate", type=float, metavar="TRUE_CM",
                        help="자로 잰 실제 거리. 지정하면 오프셋을 계산해 준다")
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
        m = aggregate(frames, min_confidence=args.min_confidence, offset_cm=args.offset)
    except MeasurementUnreliable as e:
        print(f"측정 불가: {e}", file=sys.stderr)
        return 1

    print(f"거리: {m}")

    if args.calibrate is not None:
        raw_median = m.distance_cm + args.offset       # 오프셋 적용 전 원값
        suggested = raw_median - args.calibrate
        print(f"\n[캘리브레이션] 실제 {args.calibrate:g}cm, 센서 원값 {raw_median:.1f}cm")
        print(f"  → 권장 오프셋: {suggested:+.1f}cm  (이후 --offset {suggested:.1f} 로 사용)")
        print("  여러 거리에서 반복해 오프셋이 일정한지 확인할 것 — 거리에 비례해"
              " 변하면 단순 오프셋으로는 부족하다.")

    return 0


if __name__ == "__main__":
    sys.exit(main())
