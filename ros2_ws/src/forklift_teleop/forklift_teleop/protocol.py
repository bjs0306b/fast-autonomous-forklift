"""ASCII UART framing shared by the ROS2 bridge tests and runtime."""

from dataclasses import dataclass


@dataclass(frozen=True)
class AckFrame:
    sequence: int
    status: str


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
    if not 8500 <= steering_cdeg <= 11500:
        raise ValueError("steering_cdeg must be between 8500 and 11500")
    return frame_body(f"CMD,{sequence},{drive_percent},{steering_cdeg}")


def parse_ack(frame: bytes) -> AckFrame:
    try:
        text = frame.decode("ascii").strip()
    except UnicodeDecodeError as error:
        raise ValueError("ACK is not ASCII") from error

    if not text.startswith("@") or "*" not in text:
        raise ValueError("invalid ACK framing")

    body, separator, crc_text = text[1:].rpartition("*")
    if not separator or len(crc_text) != 4:
        raise ValueError("invalid ACK CRC field")

    try:
        received_crc = int(crc_text, 16)
    except ValueError as error:
        raise ValueError("invalid ACK CRC encoding") from error

    if received_crc != crc16_ccitt_false(body.encode("ascii")):
        raise ValueError("ACK CRC mismatch")

    fields = body.split(",")
    if len(fields) != 3 or fields[0] != "ACK" or fields[2] != "OK":
        raise ValueError("invalid ACK body")

    try:
        sequence = int(fields[1])
    except ValueError as error:
        raise ValueError("invalid ACK sequence") from error

    if not 0 <= sequence <= 0xFFFFFFFF:
        raise ValueError("ACK sequence must fit uint32")
    return AckFrame(sequence=sequence, status=fields[2])
