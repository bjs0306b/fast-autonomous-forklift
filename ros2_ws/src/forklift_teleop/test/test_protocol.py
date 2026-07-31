import unittest

from forklift_teleop.protocol import (
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
        with self.assertRaises(ValueError):
            encode_command(1, 0, 8499)

    def test_encode_lift_command(self):
        frame = encode_lift_command(9, "up")
        body, crc_text = frame.decode().strip()[1:].split("*")
        self.assertEqual(body, "LIFT,9,UP")
        self.assertEqual(int(crc_text, 16), crc16_ccitt_false(body.encode()))

    def test_encode_lift_rejects_unknown_action(self):
        with self.assertRaisesRegex(ValueError, "UP, DOWN, or STOP"):
            encode_lift_command(1, "home")

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
