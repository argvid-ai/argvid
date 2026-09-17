# Fixtures

Synthetic-only. No recorded logs, captured BLE traces, or hardware media are committed.

Currently this project ships no fixture files. Planned synthetic fixtures (pending):

- F32C frame encoder cases: command frames with expected byte sequences and BCC checksums, valid and invalid.
- JSON command/event schema cases for the BLE boundary, valid and invalid.
- Angle normalization cases (negative to modulo-360 conversion, tilt clamping at plus/minus 90).

Fixture additions must record provenance in [THIRD_PARTY.md](../THIRD_PARTY.md) if any non-synthetic input is ever proposed.
