"""ASCII UART framing shared by the ROS2 bridge tests and runtime."""

from dataclasses import dataclass

# 🔗 **펌웨어 `main/config.h` 의 TELEOP_STEERING_MIN/MAX_CDEG 와 반드시 같아야 한다.**
#
# 두 값이 어긋나면 증상이 갈린다:
#   여기가 더 좁다  → 브리지가 ValueError 로 **죽는다** (2026-08-04 실제로 겪음)
#   여기가 더 넓다  → 브리지는 통과시키는데 ESP32 가 프레임을 버려 **조향이 조용히
#                     안 먹는다**. 이쪽이 더 나쁘다 — 에러가 안 난다.
#
# 2026-08-04: 8500~11500 → 6600~12600 (중립 9600 기준 ±30°, S15P11A304-197).
# ⚠️ 바꿀 때는 **펌웨어를 재플래시**해야 실제로 반영된다.
STEERING_MIN_CDEG = 6600
STEERING_MAX_CDEG = 12600


@dataclass(frozen=True)
class AckFrame:
    sequence: int
    status: str


@dataclass(frozen=True)
class LiftStatusFrame:
    sequence: int
    state: str
    completed_steps: int
    total_steps: int
    lower_limit_active: bool


def crc16_ccitt_false(data: bytes) -> int:
    """Return CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF)."""
    crc = 0xFFFF
    for value in data:
        crc ^= value << 8
        for _ in range(8):
            if crc & 0x8000:
                crc = ((crc << 1) ^ 0x1021) & 0xFFFF
            else:
                crc = (crc << 1) & 0xFFFF
    return crc


def frame_body(body: str) -> bytes:
    encoded = body.encode("ascii")
    crc = crc16_ccitt_false(encoded)
    return f"@{body}*{crc:04X}\n".encode("ascii")


def next_sequence(sequence: int) -> int:
    """Increment a uint32 sequence number with wraparound."""
    if not 0 <= sequence <= 0xFFFFFFFF:
        raise ValueError("sequence must fit uint32")
    return (sequence + 1) & 0xFFFFFFFF


def encode_command(sequence: int, drive_percent: int, steering_cdeg: int) -> bytes:
    if not 0 <= sequence <= 0xFFFFFFFF:
        raise ValueError("sequence must fit uint32")
    if not -60 <= drive_percent <= 60:
        raise ValueError("drive_percent must be between -60 and 60")
    # 펌웨어 config.h 의 TELEOP_STEERING_MIN/MAX_CDEG 와 **같은 값이어야 한다.**
    # 한쪽만 넓히면 브리지는 통과시키는데 ESP32 가 프레임을 버려, 조향이 조용히
    # 안 먹는다. 반대로 한쪽만 좁으면 여기서 ValueError 가 나며 브리지가 죽는다
    # (2026-08-04 실제로 겪었다 — mapping.py 만 넓히고 여기를 놓쳤다).
    if not STEERING_MIN_CDEG <= steering_cdeg <= STEERING_MAX_CDEG:
        raise ValueError(
            f"steering_cdeg must be between {STEERING_MIN_CDEG} and "
            f"{STEERING_MAX_CDEG} (got {steering_cdeg})")
    return frame_body(f"CMD,{sequence},{drive_percent},{steering_cdeg}")


def encode_lift_command(sequence: int, action: str) -> bytes:
    if not 0 <= sequence <= 0xFFFFFFFF:
        raise ValueError("sequence must fit uint32")
    normalized_action = action.strip().upper()
    if normalized_action not in {"UP", "DOWN", "HOME", "INITIALIZE", "STOP"}:
        raise ValueError(
            "lift action must be UP, DOWN, HOME, INITIALIZE, or STOP"
        )
    return frame_body(f"LIFT,{sequence},{normalized_action}")


def _decode_frame_body(frame: bytes, kind: str) -> str:
    try:
        text = frame.decode("ascii").strip()
    except UnicodeDecodeError as error:
        raise ValueError(f"{kind} is not ASCII") from error

    if not text.startswith("@") or "*" not in text:
        raise ValueError(f"invalid {kind} framing")

    body, separator, crc_text = text[1:].rpartition("*")
    if not separator or len(crc_text) != 4:
        raise ValueError(f"invalid {kind} CRC field")

    try:
        received_crc = int(crc_text, 16)
    except ValueError as error:
        raise ValueError(f"invalid {kind} CRC encoding") from error

    if received_crc != crc16_ccitt_false(body.encode("ascii")):
        raise ValueError(f"{kind} CRC mismatch")
    return body


def parse_ack(frame: bytes) -> AckFrame:
    body = _decode_frame_body(frame, "ACK")

    fields = body.split(",")
    if (
        len(fields) != 3
        or fields[0] != "ACK"
        or fields[2] not in {"OK", "ERROR"}
    ):
        raise ValueError("invalid ACK body")

    try:
        sequence = int(fields[1])
    except ValueError as error:
        raise ValueError("invalid ACK sequence") from error

    if not 0 <= sequence <= 0xFFFFFFFF:
        raise ValueError("ACK sequence must fit uint32")
    return AckFrame(sequence=sequence, status=fields[2])


def parse_lift_status(frame: bytes) -> LiftStatusFrame:
    body = _decode_frame_body(frame, "lift status")
    fields = body.split(",")
    if (
        len(fields) != 6
        or fields[0] != "LIFT_STATUS"
        or fields[2] not in {"RUNNING", "DONE", "ERROR"}
    ):
        raise ValueError("invalid lift status body")

    try:
        sequence = int(fields[1])
        completed_steps = int(fields[3])
        total_steps = int(fields[4])
        lower_limit = int(fields[5])
    except ValueError as error:
        raise ValueError("invalid lift status number") from error

    if not 0 <= sequence <= 0xFFFFFFFF:
        raise ValueError("lift status sequence must fit uint32")
    if not 0 <= completed_steps <= 0xFFFFFFFF:
        raise ValueError("completed_steps must fit uint32")
    if not 0 <= total_steps <= 0xFFFFFFFF:
        raise ValueError("total_steps must fit uint32")
    if lower_limit not in {0, 1}:
        raise ValueError("lower limit flag must be 0 or 1")

    return LiftStatusFrame(
        sequence=sequence,
        state=fields[2],
        completed_steps=completed_steps,
        total_steps=total_steps,
        lower_limit_active=bool(lower_limit),
    )
