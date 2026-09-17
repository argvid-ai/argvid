# Gen0.5 gimbal driver and control

This project delivers a complete public driver/control boundary for a 2-axis fixed smartphone gimbal: an ESP32-S3 BLE gateway firmware, a host serial protocol library with a web debug console, and a Flutter mobile app that consumes the device interface. It was delivered through a maintainer-approved public-safe task brief for Gen0.5 gimbal driver/control (activation authority recorded in [BRIEF.md](BRIEF.md)). It is proposed, not accepted; no release or compatibility claim is made.

## Hardware identity

- Actuator: 2x F32C brushless motor modules (pan address 1, tilt address 2; configurable 1-127) on a shared UART bus, 115200 baud, TTL 3.3V.
- Gateway: ESP32-S3 Dev Module, USB CDC on boot, GPIO17 TX2 / GPIO18 RX2 to the motor bus.
- App host: Android or iOS device running the Flutter app. No device serial numbers are recorded anywhere in this project.

## Boundary and safety

- The BLE JSON command boundary and the F32C wire protocol are project-local device interfaces, not canonical L2 semantics. A shared-contract proposal would go through root RFC review.
- L0 behavior lives in firmware: tilt is clamped to plus/minus 90 degrees, pan to plus/minus 180 degrees at the control layer, commands are disabled when the BLE link drops, and the ESP32 task watchdog remains enabled. Model output or app logic never bypasses these limits.
- Fail-safe on BLE disconnect (review fix P1-1): the disconnect callback drops all queued commands and sets an atomic event; the main loop then forces both axes into speed mode at 0 RPM (holding torque, chosen over `disable` to keep the vertical axis from sagging under gravity). This stop mechanism still requires hardware-in-the-loop verification.
- Stop-command bypass (P1-2): joystick-release stops (`jog` with `dir=0`) are routed through atomic flags instead of the command queue, so a full queue can never drop a stop, and queued stale motion commands are flushed when a stop arrives.
- Single-motor console commands honor the gimbal limits (P1-3): out-of-range tilt angles are explicitly rejected on both `set_angle` and `set_multi_angle` (signed origin-relative check), speeds are capped at plus/minus 300 RPM, and unknown jog axes are rejected instead of silently treated as tilt. Motor-console position feedback limiting during jog (e.g. refusing further same-direction jog past a hardware limit) requires real-device behavior and remains pending.
- Protocol responses with a mismatched address are treated as failures (P1-4) in both the C++ firmware and the Python reference implementation, with deterministic host regression tests.
- Mode cache is written only after a successful mode switch, and external `set_mode`/`disable`/`factory_reset`/`setaddr` on a gimbal axis invalidates the cache (P1-5), so stale-mode false successes cannot occur.
- The Python web console is localhost-only by default and requires a per-launch random token on every `/api/*` call (P1-7); serial transactions (`set_addr` + command) are serialized by a lock (P1-6) so concurrent requests cannot produce wrong-axis frames.
- Unrun hardware-in-the-loop checks are pending, not passed. Automated host tests do not certify hardware safety.

## Layout

- [src/firmware](src/README.md): ESP32-S3 Arduino firmware (BLE gateway, F32C protocol, gimbal control, WiFi provisioning, JSON command routing).
- [src/app](src/README.md): Flutter app (BLE scan/connect, WiFi provisioning, motor console, dual-axis gimbal UI).
- [src/tools](src/README.md): Python F32C protocol library, CLI tool, and Flask web debug console over USB-TTL.
- [tests](tests/README.md): host verification commands and evidence status.
- [fixtures](fixtures/README.md): synthetic fixture policy.
- [docs](docs/README.md): interface contract and usage notes.

## Setup and verification

All paths below are relative to `projects/gen0-gimbal/`. CI runs the same checks from the repository root via `.github/workflows/gimbal.yml` (firmware compile with the pinned Arduino toolchain, Flutter unit tests, Python module compile).

Firmware, two supported routes (both pinned to esp32 core 3.3.11 + ArduinoJson 7.4.3):

- Arduino CLI (reproducible compile check; arduino-cli 1.5.1): from `projects/gen0-gimbal/` run `arduino-cli compile --fqbn esp32:esp32:esp32s3:CDCOnBoot=cdc src/firmware/esp32-firmware`; exit 0 means the firmware compiles. First run needs the core and library installed: `arduino-cli config init --additional-urls https://espressif.github.io/arduino-esp32/package_esp32_index.json`, `arduino-cli core update-index`, `arduino-cli core install esp32:esp32@3.3.11`, `arduino-cli lib install ArduinoJson@7.4.3`.
- Arduino IDE (2.x, bundled arduino-cli 1.5.1): install the ESP32 board package 3.3.11 and ArduinoJson 7.4.3, open `src/firmware/esp32-firmware/esp32-firmware.ino`, select ESP32S3 Dev Module with USB CDC On Boot enabled, compile and upload, then watch the serial monitor at 115200 for the ready banner.

App: requires the Flutter stable SDK. From `projects/gen0-gimbal/src/app` run `flutter pub get` then `flutter test`; all unit tests must pass. The suites cover app control behavior (position stepping, relative joystick dragging with 130 ms throttling and final-position send on release, single-axis levers, the motion-speed command sequence, pan plus/minus 180 / tilt plus/minus 90 clamping, 0.1-degree rounding) plus model, tuning-parameter, and log-formatting logic; they do not exercise BLE transport or firmware execution on hardware (see [tests/README.md](tests/README.md)). Platform folders are regenerated with `flutter create . --project-name gimbal_app` and are intentionally not committed.

Tools: requires Python 3.12+ and `pip install -r src/tools/requirements.txt`. From `projects/gen0-gimbal/` run `python -m py_compile src/tools/f32c_protocol.py src/tools/test_cli.py src/tools/web_app.py`; exit status 0 with no output means all three modules compile. Run the Python unit tests with `python -m unittest discover -s tests -v` (protocol address check, web auth and concurrency; 13 tests must pass). The web console is started with `python src/tools/web_app.py` and served at http://127.0.0.1:5000 with a per-launch token printed on startup.

Not run in this delivery: real-device firmware flash, BLE end-to-end integration, and hardware-in-the-loop gimbal motion tests; all remain pending.

## Limitations

- No canonical L2 contract is claimed or accepted; the GATT draft PR #2 was not merged and is not adopted here.
- Third-party dependency licenses are recorded in [THIRD_PARTY.md](THIRD_PARTY.md); review is required before acceptance.
- Vendor manuals and vendor example code for the F32C motors are excluded for licensing reasons; only independently documented protocol semantics are included.
