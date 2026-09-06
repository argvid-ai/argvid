"""P1-4 回归测试：回包地址校验。

复审复现：地址不匹配的反馈帧被标记为 valid=True（C++ 与 Python 均如此），
总线串扰/他机回包会被当成命令成功。修复后地址不匹配必须 valid=False。
纯主机测试：直接构造帧字节，无需硬件。
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src", "tools"))

from f32c_protocol import (  # noqa: E402
    F32CMotor,
    MotorResponse,
    calc_bcc,
    FRAME_HEADER,
    FRAME_TAIL,
)


def build_frame(addr: int, func: int = 0x04, data: bytes = b"\x00\x00\x0c\x40") -> bytes:
    """构造一帧合法反馈帧（BCC 正确）"""
    body = bytes([FRAME_HEADER, addr, func]) + data
    return body + bytes([calc_bcc(body), FRAME_TAIL])


class TestAddressCheck(unittest.TestCase):
    def setUp(self):
        self.motor = F32CMotor(port="", debug=False)
        self.motor.addr = 0x02

    def test_addr_mismatch_is_invalid(self):
        """P1-4 核心：地址不匹配的回包不得标记为成功"""
        r = self.motor._parse_response(build_frame(0x03))  # 期望 02 实际 03
        self.assertFalse(r.valid)
        self.assertTrue(r.bcc_ok)          # BCC 本身是过的
        self.assertIn("地址不匹配", r.parsed_text)

    def test_addr_match_is_valid(self):
        """地址匹配 + BCC 正常：正常成功路径不受影响"""
        r = self.motor._parse_response(build_frame(0x02))
        self.assertTrue(r.valid)
        self.assertTrue(r.bcc_ok)
        self.assertEqual(r.type_code, 0x04)   # 电压反馈
        self.assertEqual(r.value, 0x0C40)     # 3136 -> 31.36V

    def test_short_frame_invalid(self):
        r = self.motor._parse_response(b"\x7a\x02")
        self.assertFalse(r.valid)

    def test_bad_bcc_invalid(self):
        frame = bytearray(build_frame(0x02))
        frame[-2] ^= 0xFF   # 破坏 BCC
        r = self.motor._parse_response(bytes(frame))
        self.assertFalse(r.valid)
        self.assertFalse(r.bcc_ok)

    def test_response_dataclass_shape(self):
        """MotorResponse 字段形状（C++/Python 两端契约）"""
        r = MotorResponse(b"", True, True, 0, 0, "")
        self.assertTrue(r.valid)


if __name__ == "__main__":
    unittest.main()
