# Interface and usage

## BLE interface contract (project-local, not canonical L2)

GATT service `0000ff00-0000-1000-8000-00805f9b34fb`, advertised name `F32C-Gimbal`:

| Characteristic | Property | Purpose |
|---|---|---|
| FF01 | Write | WiFi provisioning (SSID + password, stored to NVS) |
| FF02 | Read + Notify | System status (WiFi state, IP, RSSI) |
| FF03 | Write | Motor/gimbal JSON commands |
| FF04 | Notify | Responses, scan results, and log events |

Commands are single-line JSON on FF03; events are single-line JSON on FF04. Ack/timeout: a command that produces no motor-bus response within 500 ms returns an `error` event with the reason; a full bus scan takes about 2 s and returns one `scan_result` event with all discovered motors.

Command set (`cmd` values): `scan`, `enable`, `disable`, `set_mode`, `set_speed`, `set_angle`, `set_multi_angle`, `set_accel`, `query`, `save`, `clear_total`, `set_zero`, `factory_reset`, `setaddr`, `test`, `gimbal_config`, `jog`, `move`, `center`, `zero`. Each carries `addr` plus command-specific fields.

Events: `cmd_result`, `scan_result`, `query_result`, `log`, `error`, `gimbal_state`, `wifi_status`, `sys_status`.

Capability mismatch and unsupported requests return an explicit `error` event; there is no silent downgrade. Both ends (C++ firmware and Dart app) implement this contract and must be changed together.

## F32C wire protocol (device-local)

Frame: `7A addr func data... bcc 7B` where `bcc` is the XOR of all preceding bytes. Key function codes: `06`/`05` enable/disable, `00` mode (0 speed, 2 single-turn absolute position with T-curve), `01` speed RPM int16, `03` single-turn absolute angle in 0.1-degree units, `0E` query, `0A` set current position as zero. Angles are accepted in `[0,360)`; the control layers convert negative angles modulo 360.

## Usage notes

- Motor power must be 8-15 V from an independent supply; never power motors from USB 5 V. Common ground between the gateway and motor bus is required.
- The web console (`python src/tools/web_app.py`, http://127.0.0.1:5000) discovers serial ports, scans the bus, and drives single or dual motors over USB-TTL; it is the fastest way to verify a motor before wireless bring-up.
- Android Bluetooth permissions are declared in the app manifest; iOS requires adding `NSBluetoothAlwaysUsageDescription` when regenerating platform folders.
