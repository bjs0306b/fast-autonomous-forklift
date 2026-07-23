import unittest

from forklift_teleop.protocol import (
    crc16_ccitt_false,
    encode_command,
    frame_body,
    next_sequence,
    parse_ack,
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


if __name__ == "__main__":
    unittest.main()
