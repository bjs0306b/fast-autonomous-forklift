"""Sensor telemetry framing for the ESP32 USB uplink.

Framing and CRC are shared with the UART command link, so
:func:`forklift_teleop.protocol.crc16_ccitt_false` is reused verbatim rather
than reimplemented here.
"""

from dataclasses import dataclass
from typing import Optional, Union

from forklift_teleop.protocol import crc16_ccitt_false

# Only the native USB link carries these, and the ESP32 console shares that
# stream. Anything that is not a frame is an ESP_LOG line and gets dropped.
FRAME_START = b"@"

IMU_FIELD_COUNT = 5
IMU_STATUS_FIELD_COUNT = 6
TOF_FIELD_COUNT = 5
TOF_STATUS_FIELD_COUNT = 8
ENCODER_FIELD_COUNT = 5

# 8x8 multizone, packed as fixed-width hex: three digits of distance in mm
# followed by one digit of target status, with no separators.
TOF_ZONE_COUNT = 64
TOF_ZONE_CHARS = 4
TOF_PAYLOAD_CHARS = TOF_ZONE_COUNT * TOF_ZONE_CHARS

# The ULD reports 5 for a good measurement and 9 for one with half confidence.
TOF_STATUS_VALID = 5
TOF_STATUS_VALID_LOW_CONFIDENCE = 9

# A reboot restarts esp_timer at zero, and NTP can step the host clock. Either
# invalidates the offset estimate outright.
CLOCK_RESET_THRESHOLD_NS = 1_000_000_000

# Allowance for MCU-versus-host clock skew, in nanoseconds per nanosecond.
# 20 ppm is generous for a crystal and still far below queueing jitter.
CLOCK_DRIFT_RATE = 20e-6


@dataclass(frozen=True)
class ImuFrame:
    sequence: int
    mcu_time_us: int
    gyro_z_mdps: int
    temp_cdeg: int


@dataclass(frozen=True)
class ImuStatusFrame:
    who_am_i: int
    bias_mdps: int
    idle: bool
    dropped: int
    sequence: int


@dataclass(frozen=True)
class TofFrame:
    sensor_id: int
    sequence: int
    mcu_time_us: int
    distance_mm: tuple
    status: tuple


@dataclass(frozen=True)
class TofStatusFrame:
    sensor_id: int
    present: bool
    read_errors: int
    data_ready_errors: int
    interrupts: int
    published: int
    polled: int


@dataclass(frozen=True)
class EncoderFrame:
    """One cumulative wheel-encoder reading.

    The count is cumulative rather than a delta, so a frame lost in transit
    costs timing resolution but never distance: the next one still carries
    everything the wheel has turned.
    """

    sequence: int
    mcu_time_us: int
    count: int
    read_errors: int


SensorFrame = Union[
    ImuFrame, ImuStatusFrame, TofFrame, TofStatusFrame, EncoderFrame
]


def _split_verified_body(line: bytes) -> Optional[list]:
    """Return the CRC-verified comma fields, or None for a non-frame line."""
    if not line.startswith(FRAME_START):
        return None

    try:
        text = line.decode("ascii").strip()
    except UnicodeDecodeError:
        return None

    if not text.startswith("@"):
        return None

    body, separator, crc_text = text[1:].rpartition("*")
    if not separator or len(crc_text) != 4:
        raise ValueError("invalid sensor frame CRC field")

    try:
        received_crc = int(crc_text, 16)
    except ValueError as error:
        raise ValueError("invalid sensor frame CRC encoding") from error

    if received_crc != crc16_ccitt_false(body.encode("ascii")):
        raise ValueError("sensor frame CRC mismatch")

    return body.split(",")


def _parse_int(text: str, name: str) -> int:
    stripped = text[1:] if text.startswith("-") else text
    if not stripped or not stripped.isdigit():
        raise ValueError(f"invalid {name} field")
    return int(text)


def parse_sensor_line(line: bytes) -> Optional[SensorFrame]:
    """Parse one line from the USB uplink.

    Returns None for lines that are not sensor frames, which covers ESP32 log
    output sharing the same stream. Raises ValueError for lines that look like
    frames but fail CRC or field validation.
    """
    fields = _split_verified_body(line)
    if fields is None:
        return None

    kind = fields[0]

    if kind == "IMU":
        if len(fields) != IMU_FIELD_COUNT:
            raise ValueError("invalid IMU field count")
        return ImuFrame(
            sequence=_parse_int(fields[1], "IMU sequence"),
            mcu_time_us=_parse_int(fields[2], "IMU timestamp"),
            gyro_z_mdps=_parse_int(fields[3], "IMU gyro"),
            temp_cdeg=_parse_int(fields[4], "IMU temperature"),
        )

    if kind == "IMS":
        if len(fields) != IMU_STATUS_FIELD_COUNT:
            raise ValueError("invalid IMS field count")
        return ImuStatusFrame(
            who_am_i=_parse_int(fields[1], "IMS who_am_i"),
            bias_mdps=_parse_int(fields[2], "IMS bias"),
            idle=_parse_int(fields[3], "IMS idle") != 0,
            dropped=_parse_int(fields[4], "IMS dropped"),
            sequence=_parse_int(fields[5], "IMS sequence"),
        )

    if kind == "TOF":
        if len(fields) != TOF_FIELD_COUNT:
            raise ValueError("invalid TOF field count")
        return _parse_tof(fields)

    if kind == "TFS":
        if len(fields) != TOF_STATUS_FIELD_COUNT:
            raise ValueError("invalid TFS field count")
        return TofStatusFrame(
            sensor_id=_parse_int(fields[1], "TFS sensor id"),
            present=_parse_int(fields[2], "TFS present") != 0,
            read_errors=_parse_int(fields[3], "TFS read errors"),
            data_ready_errors=_parse_int(fields[4], "TFS data ready errors"),
            interrupts=_parse_int(fields[5], "TFS interrupts"),
            published=_parse_int(fields[6], "TFS published"),
            polled=_parse_int(fields[7], "TFS polled"),
        )

    if kind == "ENC":
        if len(fields) != ENCODER_FIELD_COUNT:
            raise ValueError("invalid ENC field count")
        return EncoderFrame(
            sequence=_parse_int(fields[1], "ENC sequence"),
            mcu_time_us=_parse_int(fields[2], "ENC timestamp"),
            count=_parse_int(fields[3], "ENC count"),
            read_errors=_parse_int(fields[4], "ENC read errors"),
        )

    # Unknown kinds are ignored rather than rejected, so a firmware that gains
    # a new frame type does not break a bridge that has not been updated yet.
    return None


def _parse_tof(fields: list) -> TofFrame:
    payload = fields[4]
    if len(payload) != TOF_PAYLOAD_CHARS:
        raise ValueError(
            f"TOF payload must be {TOF_PAYLOAD_CHARS} chars, got {len(payload)}"
        )

    distance_mm = []
    status = []
    for zone in range(TOF_ZONE_COUNT):
        chunk = payload[zone * TOF_ZONE_CHARS:(zone + 1) * TOF_ZONE_CHARS]
        try:
            distance_mm.append(int(chunk[:3], 16))
            status.append(int(chunk[3], 16))
        except ValueError as error:
            raise ValueError(f"invalid TOF zone {zone} encoding") from error

    return TofFrame(
        sensor_id=_parse_int(fields[1], "TOF sensor id"),
        sequence=_parse_int(fields[2], "TOF sequence"),
        mcu_time_us=_parse_int(fields[3], "TOF timestamp"),
        distance_mm=tuple(distance_mm),
        status=tuple(status),
    )


class ClockOffsetTracker:
    """Map ESP32 ``esp_timer`` microseconds onto ROS time.

    This is the stand-in for ``rmw_uros_sync_session`` on the plain-ASCII link.
    Stamping frames on arrival instead would fold serial read latency into the
    timestamp and make the EKF see lumpy sample spacing.

    The offset tracks the *minimum* observed ``host - mcu`` difference, which
    strips queueing delay, plus a small upward allowance so a slowly drifting
    MCU clock is followed rather than pinned to its luckiest sample.
    """

    def __init__(
        self,
        reset_threshold_ns: int = CLOCK_RESET_THRESHOLD_NS,
        drift_rate: float = CLOCK_DRIFT_RATE,
    ) -> None:
        self._offset_ns: Optional[int] = None
        self._last_mcu_ns: Optional[int] = None
        self._reset_threshold_ns = reset_threshold_ns
        self._drift_rate = drift_rate
        self.reset_count = 0

    @property
    def offset_ns(self) -> Optional[int]:
        return self._offset_ns

    def _reset(self, candidate_ns: int, mcu_ns: int) -> None:
        self._offset_ns = candidate_ns
        self._last_mcu_ns = mcu_ns
        self.reset_count += 1

    def stamp_ns(self, mcu_time_us: int, host_time_ns: int) -> int:
        """Return the ROS-time nanoseconds to stamp a sample with."""
        mcu_ns = mcu_time_us * 1000
        candidate_ns = host_time_ns - mcu_ns

        if self._offset_ns is None or self._last_mcu_ns is None:
            self._reset(candidate_ns, mcu_ns)
        elif mcu_ns < self._last_mcu_ns:
            # esp_timer went backwards: the MCU rebooted.
            self._reset(candidate_ns, mcu_ns)
        elif abs(candidate_ns - self._offset_ns) > self._reset_threshold_ns:
            self._reset(candidate_ns, mcu_ns)
        else:
            elapsed_ns = mcu_ns - self._last_mcu_ns
            allowance_ns = int(elapsed_ns * self._drift_rate)
            self._offset_ns = min(
                candidate_ns,
                self._offset_ns + allowance_ns,
            )
            self._last_mcu_ns = mcu_ns

        # Never stamp into the future; tf2 rejects those transforms.
        return min(mcu_ns + self._offset_ns, host_time_ns)
