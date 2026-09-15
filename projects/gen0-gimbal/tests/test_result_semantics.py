"""写入结果语义回归：发送完成不等于设备执行确认。"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src", "tools"))

from f32c_protocol import F32CMotor, calc_bcc  # noqa: E402


class FakeSerial:
    def __init__(self, written=None):
        self.is_open = True
        self.writes = []
        self._written = written

    def reset_input_buffer(self):
        pass

    def write(self, frame):
        self.writes.append(bytes(frame))
        return len(frame) if self._written is None else self._written

    def flush(self):
        pass


class TestWriteResultSemantics(unittest.TestCase):
    def setUp(self):
        self.motor = F32CMotor(port="", addr=0x02, debug=False)

    def test_write_without_ack_is_transport_success_only(self):
        fake = FakeSerial()
        self.motor._ser = fake

        result = self.motor.set_speed(10)

        self.assertTrue(result.valid)
        self.assertFalse(result.device_confirmed)
        self.assertIn("命令已发送", result.parsed_text)
        self.assertEqual(len(fake.writes), 1)

    def test_short_write_is_failure(self):
        frame_len = len(self.motor._build_frame(self.motor.FC_SET_SPEED, b"\x00\x0A"))
        self.motor._ser = FakeSerial(written=frame_len - 1)

        result = self.motor.set_speed(10)

        self.assertFalse(result.valid)
        self.assertFalse(result.device_confirmed)
        self.assertIn("写入未完成", result.parsed_text)

    def test_feedback_is_device_confirmed(self):
        body = bytes([0x7A, 0x02, self.motor.RT_VOLTAGE, 0x00, 0x00, 0x04, 0xB0])
        frame = body + bytes([calc_bcc(body), 0x7B])

        result = self.motor._parse_response(frame)

        self.assertTrue(result.valid)
        self.assertTrue(result.device_confirmed)

if __name__ == "__main__":
    unittest.main()
