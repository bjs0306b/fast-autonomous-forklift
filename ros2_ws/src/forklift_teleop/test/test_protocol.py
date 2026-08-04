import unittest

from forklift_teleop.protocol import (
    STEERING_MAX_CDEG,
    STEERING_MIN_CDEG,
    crc16_ccitt_false,
    encode_command,
    encode_lift_command,
    frame_body,
    next_sequence,
    parse_ack,
    parse_lift_status,
)


class ProtocolTest(unittest.TestCase):
    def test_known_crc_vector(self):
        self.assertEqual(crc16_ccitt_false(b"123456789"), 0x29B1)

    def test_encode_command(self):
        frame = encode_command(42, -55, 10325)
        body, crc_text = frame.decode().strip()[1:].split("*")
        self.assertEqual(body, "CMD,42,-55,10325")
        self.assertEqual(int(crc_text, 16), crc16_ccitt_false(body.encode()))

    def test_encode_rejects_out_of_range(self):
        with self.assertRaises(ValueError):
            encode_command(1, 61, 10000)
        # 경계는 상수에서 끌어온다 — 값을 박아두면 범위를 넓힐 때마다 여기가 깨지고,
        # "테스트가 깨졌으니 테스트를 고치자" 로 흘러 정작 펌웨어와의 불일치를
        # 못 잡는다. 2026-08-04 에 8499 가 박혀 있어 실제로 깨졌다.
        with self.assertRaises(ValueError):
            encode_command(1, 0, STEERING_MIN_CDEG - 1)
        with self.assertRaises(ValueError):
            encode_command(1, 0, STEERING_MAX_CDEG + 1)

    def test_encode_accepts_range_edges(self):
        """양 끝은 통과해야 한다 — 한 칸 좁으면 최대 조향에서 브리지가 죽는다."""
        encode_command(1, 0, STEERING_MIN_CDEG)
        encode_command(1, 0, STEERING_MAX_CDEG)

    def test_encode_lift_command(self):
        frame = encode_lift_command(9, "up")
        body, crc_text = frame.decode().strip()[1:].split("*")
        self.assertEqual(body, "LIFT,9,UP")
        self.assertEqual(int(crc_text, 16), crc16_ccitt_false(body.encode()))

    def test_encode_home_and_initialize_lift_commands(self):
        self.assertIn(b"LIFT,10,HOME*", encode_lift_command(10, "home"))
        self.assertIn(
            b"LIFT,11,INITIALIZE*",
            encode_lift_command(11, "initialize"),
        )

    def test_encode_lift_rejects_unknown_action(self):
        with self.assertRaisesRegex(ValueError, "INITIALIZE"):
            encode_lift_command(1, "sideways")

    def test_sequence_increments_and_wraps(self):
        self.assertEqual(next_sequence(41), 42)
        self.assertEqual(next_sequence(0xFFFFFFFF), 0)

    def test_parse_ack(self):
        ack = parse_ack(frame_body("ACK,7,OK"))
        self.assertEqual(ack.sequence, 7)
        self.assertEqual(ack.status, "OK")

    def test_parse_ack_rejects_bad_crc(self):
        with self.assertRaisesRegex(ValueError, "CRC mismatch"):
            parse_ack(b"@ACK,7,OK*0000\n")

    def test_parse_error_ack(self):
        ack = parse_ack(frame_body("ACK,8,ERROR"))
        self.assertEqual(ack.sequence, 8)
        self.assertEqual(ack.status, "ERROR")

    def test_parse_lift_status(self):
        status = parse_lift_status(
            frame_body("LIFT_STATUS,9,DONE,1600,1600,0")
        )
        self.assertEqual(status.sequence, 9)
        self.assertEqual(status.state, "DONE")
        self.assertEqual(status.completed_steps, 1600)
        self.assertEqual(status.total_steps, 1600)
        self.assertFalse(status.lower_limit_active)

    def test_parse_lift_status_rejects_bad_limit_flag(self):
        with self.assertRaisesRegex(ValueError, "lower limit flag"):
            parse_lift_status(
                frame_body("LIFT_STATUS,9,DONE,1600,1600,2")
            )


if __name__ == "__main__":
    unittest.main()
