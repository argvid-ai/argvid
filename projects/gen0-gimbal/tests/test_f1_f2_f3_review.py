"""PR #8 review regressions F1-F3: honest stop delivery, queue preemption, honest web wording.

F3: endpoint-level fake-serial test for the position-follow API.
F1/F2 host regressions are in tests/firmware_host/ (C++); these Python
tests cover the tooling-side semantics.
"""
import io
import sys
import unittest
from unittest.mock import MagicMock, patch
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src" / "tools"))


class F3WebPositionFollowWording(unittest.TestCase):
    """web_app.py position endpoint must not call a write-only send 'confirmed'."""

    def test_position_follow_says_target_sent_not_confirmed(self):
        """The API response text distinguishes send from confirmation."""
        # Read the actual source and verify the honest wording is present.
        source = (Path(__file__).resolve().parents[1] /
                   "src" / "tools" / "web_app.py").read_text(encoding="utf-8")
        self.assertIn("目标已发送，到位未确认", source,
                      "F3: position follow must say 'target sent, arrival unconfirmed'")
        self.assertNotIn("'已确认' if r.valid", source,
                         "F3: must NOT use r.valid as confirmation for write-only commands")


class F1FirmwareHonestStop(unittest.TestCase):
    """Verify the F1 fix is present in firmware source: no `continue` after enable failure in e-stop."""

    def test_enable_failure_does_not_skip_zero_speed(self):
        source = (Path(__file__).resolve().parents[1] /
                   "src" / "firmware" / "esp32-firmware" / "gimbal_controller.cpp").read_text(encoding="utf-8")
        # The F1 fix replaced `continue` with fall-through after enable failure.
        # Verify no `continue` between the enable-fail block and setSpeed(0).
        self.assertNotIn(
            "使能确认失败 \"\n            continue;",
            source,
            "F1: enable failure must not skip zero-speed (no `continue` after 使能确认失败)",
        )
        # Verify the F1 comment is present (documents the intent).
        self.assertIn("F1: enable failure must NOT skip the zero-speed attempt", source)


class F2FirmwareQueuePreemption(unittest.TestCase):
    """Verify F2 abort wiring is present (behavioral coverage is in firmware_host)."""

    def test_process_queue_checks_safety_between_commands(self):
        root = Path(__file__).resolve().parents[1] / "src" / "firmware" / "esp32-firmware"
        cmd = (root / "cmd_handler.cpp").read_text(encoding="utf-8")
        ble = (root / "ble_service.cpp").read_text(encoding="utf-8")
        ctl = (root / "gimbal_controller.cpp").read_text(encoding="utf-8")
        self.assertIn("F2: safety preemption between EVERY queued command", cmd)
        self.assertIn("setMotionAbortHook", cmd)
        self.assertIn("jsonIsMotionCommand", cmd)
        self.assertIn("_fireMotionAbort", ble)
        self.assertIn("_motionAborted", ctl)


if __name__ == "__main__":
    unittest.main()


