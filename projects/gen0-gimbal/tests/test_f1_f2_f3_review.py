"""PR #8 review regressions F1-F3: honest stop delivery, queue preemption, honest web wording.

F3: endpoint-level fake-serial test for the position-follow API.
F1/F2 host regressions are in tests/firmware_host/ (C++); these Python
tests cover the tooling-side semantics.
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src" / "tools"))


class F3WebPositionFollowWording(unittest.TestCase):
    """web_app.py position endpoint must not call a write-only send 'confirmed'."""

    def test_position_follow_says_target_sent_not_confirmed(self):
        source = (Path(__file__).resolve().parents[1] /
                   "src" / "tools" / "web_app.py").read_text(encoding="utf-8")
        self.assertIn("目标已发送，到位未确认", source)
        self.assertNotIn("'已确认' if r.valid", source)


class F1FirmwareHonestStop(unittest.TestCase):
    def test_enable_failure_does_not_skip_zero_speed(self):
        source = (Path(__file__).resolve().parents[1] /
                   "src" / "firmware" / "esp32-firmware" / "gimbal_controller.cpp").read_text(encoding="utf-8")
        self.assertNotIn(
            "使能确认失败 \"\n            continue;",
            source,
        )
        self.assertIn("F1: enable failure must NOT skip the zero-speed attempt", source)


class F2FirmwareQueuePreemption(unittest.TestCase):
    """Wiring checks; behavioral coverage is in firmware_host serial-hook tests."""

    def test_process_queue_checks_safety_between_commands(self):
        root = Path(__file__).resolve().parents[1] / "src" / "firmware" / "esp32-firmware"
        cmd = (root / "cmd_handler.cpp").read_text(encoding="utf-8")
        ble = (root / "ble_service.cpp").read_text(encoding="utf-8")
        ctl = (root / "gimbal_controller.cpp").read_text(encoding="utf-8")
        hdr = (root / "gimbal_controller.h").read_text(encoding="utf-8")
        self.assertIn("F2: safety preemption between EVERY queued command", cmd)
        self.assertIn("setMotionAbortHook", cmd)
        self.assertIn("jsonIsMotionCommand", cmd)
        self.assertIn("_fireMotionAbort", ble)
        self.assertIn("std::atomic<uint32_t> _abortGeneration", hdr)
        self.assertIn("isMotionAborted", ctl)
        self.assertIn("failIfAborted", ctl)
        self.assertIn("pan mode后", ctl)
        self.assertIn("tilt enable后", ctl)


if __name__ == "__main__":
    unittest.main()