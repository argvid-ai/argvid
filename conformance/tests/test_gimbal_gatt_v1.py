from __future__ import annotations

import json
import struct
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
ADAPTER = ROOT / "adapters" / "gimbal-gen05"
SPEC_PATH = ADAPTER / "spec" / "gatt-v1.json"
FIXTURES = ADAPTER / "fixtures"


def load_hex(relative_path: str) -> bytes:
    return bytes.fromhex((FIXTURES / relative_path).read_text(encoding="utf-8"))


def validate_setpoint(payload: bytes, *, received_after_ms: int = 0) -> str | None:
    if len(payload) != 16:
        return "INVALID_LENGTH"
    version, _, _, _, _, _, deadline_ms, _ = struct.unpack("<BBHhhHHI", payload)
    if version != 1:
        return "UNSUPPORTED_VERSION"
    if received_after_ms > deadline_ms:
        return "DEADLINE_EXPIRED"
    return None


def validate_capability(payload: bytes) -> str | None:
    if len(payload) != 20:
        return "INVALID_LENGTH"
    (
        version,
        axis_flags,
        pan_min_cdeg,
        pan_max_cdeg,
        tilt_min_cdeg,
        tilt_max_cdeg,
        max_dps_cdeg,
        state_hz,
        capability_flags,
        watchdog_ms,
        _,
        _,
        _,
        _,
    ) = struct.unpack("<BBhhhhHBBHBBBB", payload)
    if version != 1:
        return "UNSUPPORTED_VERSION"
    if axis_flags & ~0b11 or capability_flags & ~0b1_1111:
        return "UNSUPPORTED_FLAGS"
    if pan_min_cdeg > pan_max_cdeg or tilt_min_cdeg > tilt_max_cdeg:
        return "INVALID_RANGE"
    if max_dps_cdeg == 0 or state_hz == 0:
        return "INVALID_CAPABILITY"
    if not 1 <= watchdog_ms <= 500:
        return "INVALID_WATCHDOG"
    return None


class GimbalGattV1ConformanceTests(unittest.TestCase):
    def test_spec_keeps_adapter_details_out_of_l2(self) -> None:
        spec = json.loads(SPEC_PATH.read_text(encoding="utf-8"))
        self.assertEqual("adapter/gimbal-gen05", spec["layer"])
        self.assertEqual("little", spec["endianness"])
        self.assertLessEqual(max(packet["size_bytes"] for packet in spec["packets"].values()), 20)
        self.assertEqual(6, len(set(spec["uuids"].values())))

    def test_setpoint_fixture_is_exactly_16_bytes_and_little_endian(self) -> None:
        payload = load_hex("valid/manual-setpoint.hex")
        self.assertEqual(16, len(payload))
        self.assertEqual((1, 0, 7, 1250, -500, 3000, 400, 0x11223344), struct.unpack("<BBHhhHHI", payload))
        self.assertIsNone(validate_setpoint(payload))

    def test_idle_state_fixture_decodes_exactly(self) -> None:
        payload = load_hex("valid/idle-state.hex")
        self.assertEqual(16, len(payload))
        self.assertEqual((1, 0, 7, 1250, -500, 0, 250, 1000), struct.unpack("<BBHhhHhI", payload))

    def test_unknown_version_is_rejected(self) -> None:
        self.assertEqual("UNSUPPORTED_VERSION", validate_setpoint(load_hex("invalid/unknown-version.hex")))

    def test_expired_deadline_is_rejected_separately_from_decoding(self) -> None:
        case = json.loads((FIXTURES / "invalid" / "expired-deadline.json").read_text(encoding="utf-8"))
        payload = bytes.fromhex(case["packet_hex"])
        self.assertEqual(case["expected_error"], validate_setpoint(payload, received_after_ms=case["received_after_ms"]))

    def test_capability_rejects_inverted_ranges_and_unsafe_watchdog(self) -> None:
        inverted_pan = struct.pack("<BBhhhhHBBHBBBB", 1, 3, 100, -100, -4500, 4500, 3000, 10, 3, 400, 1, 0, 0, 1)
        unsafe_watchdog = struct.pack("<BBhhhhHBBHBBBB", 1, 3, -9000, 9000, -4500, 4500, 3000, 10, 3, 501, 1, 0, 0, 1)
        self.assertEqual("INVALID_RANGE", validate_capability(inverted_pan))
        self.assertEqual("INVALID_WATCHDOG", validate_capability(unsafe_watchdog))


if __name__ == "__main__":
    unittest.main()
