# Source layout

All delivered source lives here. Every component is self-contained and buildable from publicly documented steps in the project [README](../README.md).

- `firmware/`: ESP32-S3 Arduino sketch. `esp32-firmware.ino` is the entrypoint; `config.h` holds pins, BLE UUIDs, and protocol constants; `f32c_protocol.*` builds and parses F32C UART frames with BCC checksums; `ble_service.*` exposes the GATT server; `cmd_handler.*` routes JSON commands through a FreeRTOS queue; `gimbal_controller.*` implements dual-axis jog/move with clamped limits; `wifi_manager.*` provisions station WiFi into NVS. `platformio.ini` is an optional PlatformIO configuration.
- `app/`: Flutter application. `lib/main.dart` is the entrypoint; `lib/ble/` is the BLE client and UUID contract (`command_sink.dart` defines the send interface the control layer depends on); `lib/control/gimbal_controller.dart` throttles jog/move commands and clamps angles to the gimbal bounds before transport; `lib/models/`, `lib/pages/`, `lib/widgets/`, `lib/utils/` hold state, screens, widgets, and log formatting; `test/` holds host unit tests (control behavior: throttling/clamping/jog, plus models and log formatting). Platform folders are regenerated via `flutter create . --project-name gimbal_app` and are not committed.
- `tools/`: Python implementation of the F32C protocol over USB-TTL. `f32c_protocol.py` is the protocol library, `test_cli.py` an interactive bus tool (scan, select, readdress), `web_app.py` the Flask web debug console, `requirements.txt` the pinned minimums.

The Python `tools/f32c_protocol.py` and the C++ `firmware/f32c_protocol.cpp` implement the same wire protocol and must be kept in sync; the Python version is the reference implementation.
