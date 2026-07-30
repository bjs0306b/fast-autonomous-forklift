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


SensorFrame = Union[ImuFrame, ImuStatusFrame]


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

    # An ACK arriving here would mean the links are crossed; treat as unknown.
    return None


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
