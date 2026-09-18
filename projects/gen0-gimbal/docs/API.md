# Gen0.5 gimbal — API reference

Interfaces exposed by this project: the BLE GATT boundary consumed by the Flutter app, the JSON command/event contract carried over it, the firmware safety behaviors that callers must not assume away, the web console HTTP API, and the Python `F32CMotor` library. All interfaces are project-local (not canonical L2); changes require firmware and app to be updated together.

## 1. BLE GATT boundary

| Item | Value |
|---|---|
| Service UUID | `0000ff00-0000-1000-8000-00805f9b34fb` |
| Advertised name | `F32C-Gimbal` |
| MTU | 247 (negotiated; app requests on Android, automatic on iOS) |
| Connection params | 7.5–15 ms interval, 4 s supervision timeout (requested on connect) |

Characteristics:

| Characteristic | UUID suffix | Properties | Purpose |
|---|---|---|---|
| WiFi config | `FF01` | Write | Provisioning payload (see §3.1) |
| System status | `FF02` | Read + Notify | `wifi_status` / `sys_status` events |
| Motor command | `FF03` | Write | One JSON command per write (≤ 240 bytes) |
| Response / log | `FF04` | Notify | All other events (see §3.2) |

Writes remain one UTF-8 JSON object per write, within the negotiated MTU limit. For FF02/FF04 notifications, objects that fit within `min(peer MTU, 247) - 3` bytes remain plain JSON. Longer objects (for example, a scan's TX/RX log batch) use the following binary framing. This local repair requires the paired App 1.0.1 or later to receive long messages; an older App cannot decode the new fragments.

Each fragment contains an 8-byte header followed by raw UTF-8 bytes: `F3 2C`, then unsigned 16-bit little-endian `message_id`, `byte_offset`, and `total_byte_length`. The header is included in the MTU payload limit. Maximum message length is 8192 bytes. Offsets start at zero and must be contiguous; a new message starts with offset zero. Short JSON events do not use this header. FF02 reads still return the complete JSON value.

The App assembles each characteristic independently and decodes UTF-8/JSON only after all bytes arrive. Empty values are ignored. Missing, out-of-order, truncated or oversized packets produce a visible error; an incomplete message times out after 3 seconds without a fragment. Disconnect resets assembly. There is no application-level retransmission; repeat a read-only query after a receive error. The firmware yields between fragments and abandons the remainder when a stop or disconnect is pending. These checks do not replace real-device delivery and stop-latency validation.

Commands may also emit log and state events. `scan` emits a single `scan_result` after the bus scan, followed by state/log events.

## 2. Safety behaviors (caller-visible, L0)

These are enforced in firmware and cannot be disabled from the app:

- **Disconnect fail-stop (P1-1).** On BLE disconnect the firmware drops all queued commands and sends both axes to speed mode at 0 RPM. The write has no immediate motor ACK, so the event reports the stop command as sent; holding torque and stop latency require query or hardware-in-the-loop verification. Commands written while disconnected are discarded.
- **Stop bypass (P1-2).** `jog` with `dir=0` is routed through atomic flags around the 12-slot command queue: a stop is never dropped due to a full queue, and stale queued motion commands are flushed so they do not execute after the stop.
- **Limits (P1-3).** Tilt-axis targets outside ±90° are explicitly rejected with an error (no silent clamping) on `set_angle`; multi-turn commands on the tilt axis are checked against ±90° in the signed origin-relative coordinate; `set_speed` is capped at ±300 RPM; unknown `jog` axes are rejected. Pan ±180° covers the full single-turn range.
- **Response validation (P1-4).** A feedback frame whose address does not match the commanded address is a failure (`valid=false`) in both the C++ firmware and the Python library. For C++ write commands without an immediate reply, `valid=true` means the send path completed; it does not confirm execution, target arrival, persistence, or holding torque. Only a valid query/feedback frame sets `device_confirmed=true`.
- **Mode cache correctness (P1-5).** Mode/enable failures abort the command with an error instead of reporting success; external `set_mode` / `disable` / `factory_reset` / `setaddr` on a gimbal axis invalidates the cached mode so the next command re-synchronizes.

## 3. JSON contract

### 3.1 Commands (write to FF03 / FF01)

All motor commands accept an optional `"addr"` (1–127, default `0x02` on the bus).

| `cmd` | Fields | Behavior / errors |
|---|---|---|
| `scan` | — | Scans bus addresses 1–16 (~2 s). Emits `scan_result`; auto-configures gimbal if ≥2 motors found. Loads the saved parameter snapshot into gateway memory only; does not write motor gains or nonzero speed. |
| `gimbal_config` | `pan`, `tilt` (1–127) | Sets axis motor IDs. Error if out of range. Emits `gimbal_state`. |
| `jog` | `axis` (`pan`\|`tilt`), `dir` (1\|-1\|0), `speed` (RPM) | Speed-mode nudge (debug interface; the app uses position stepping). `dir=0` stops (guaranteed delivery, see §2). Errors: unknown axis, gimbal not configured. Speed clamped to 300 RPM. |
| `move` | `pan` (−180…180), `tilt` (−90…90), optional `speed` (0…300 RPM) | **Multi-turn position (mode 1, T-curve)**, 0.1° resolution. Only supplied axes move. Each explicit request prepares mode/enable/speed again to handle motor-only reboots. Fresh total-angle feedback selects the nearest equivalent pan target; tilt never wraps and an out-of-range measured tilt stops/rejects the request. The response confirms transmission, not arrival. Emits `gimbal_state`. |
| `center` | optional `speed` (0…300 RPM) | Uses the same measured-position planning as `move(0, 0)`: pan returns to the nearest equivalent `360*k` origin, tilt returns within its ±90° interval. For example, pan 730° returns to 720°, then `move(pan=5)` targets 725°. Invalid feedback or unsafe tilt rejects motion. |
| `origin` | — | Marks both axes' current position as the multi-turn origin (clears accumulated multi-turn angle); the firmware verifies each clear by querying total angle; subsequent position control is relative to it. |
| `zero` | — | Sets both axes' current position as single-turn 0°; follow with `save` for persistence. |
| `enable` / `disable` | — | Motor enable/disable. `disable` on a gimbal axis invalidates its mode cache. |
| `set_mode` | `mode` (0–4; 0=speed, 1=multi-turn position T-curve, 2=single-turn position T-curve) | Error outside 0–4. Invalidates gimbal axis cache. |
| `set_speed` | `rpm` (−300…300) | Immediate motor-console speed write; can start motion in speed mode. Does not change the stored position speed. Error beyond ±300. |
| `set_position_speed` | `rpm` (0…300) | Configures the gateway's position speed only. Sends no motor speed frame; safe to use after `jog(dir=0)`. Applied with the next explicit position request. Requires the firmware paired with App 1.0.3 (2004). |
| `set_angle` | `angle` [0,360) | Single-turn absolute. Tilt-axis targets beyond ±90° are **rejected** (error, not clamped). |
| `set_multi_angle` | `angle` | Multi-turn absolute (signed, origin-relative). Tilt-axis targets beyond ±90° are **rejected**. |
| `set_accel` | `accel` | Acceleration (rev/s²). Cached for `get_params`. |
| `get_params` | — | Returns gateway-cached PID/position speed (the protocol cannot read these back; `-1` = no gateway snapshot) plus live-measured acceleration. Emits `params_result`. |
| `query` | `type`: `voltage`\|`speed`\|`total_angle`\|`mech_angle`\|`accel` | Emits `query_result`. Error on unknown type. |
| `save` | — | Saves motor parameters to flash and the gateway snapshot to NVS. Position speed is held in gateway NVS; runtime gains/acceleration are re-applied only with explicit position intent, not during scan/config. A gateway snapshot requires one save after the desired values are set. |
| `clear_total` | — | Reset accumulated multi-turn angle; this write command is followed by a total-angle query for confirmation because it does not provide an immediate ACK frame. |
| `set_zero` | — | Set current position as single-turn zero (single motor). |
| `factory_reset` | — | Restore factory defaults. Invalidates gimbal axis cache. |
| `setaddr` | `new_addr` (1–127) | Change motor bus address; control switches to the new address. |
| `set_speed_kp` / `set_speed_ki` / `set_pos_kp` / `set_pos_ki` | `val` | PID gains. Successful values are cached for `get_params`. |
| `test` | — | Connectivity test (voltage/mech-angle/speed queries). |

WiFi provisioning (write to FF01): `{"ssid": "...", "pass": "..."}`. Credentials are stored in NVS and auto-reconnect on reboot; no `pass` echo in any event.

Timeouts: a query or other command that requests a motor-bus response and does not receive one within 500 ms fails with an `error` event stating the reason. Write-only commands do not wait for an ACK.

### 3.2 Events (notify from FF04 / FF02)

| `event` | Fields | Notes |
|---|---|---|
| `cmd_result` | `ok` (bool), `msg` | Result of one command. For write-only motor commands, `ok=true` means the send path completed; `msg` explicitly says when execution/arrival is unconfirmed. |
| `scan_result` | `ok`, `motors: [{id, volt}]` | One event after the full bus scan. |
| `query_result` | `addr`, `type`, `value`, `text` | `value` is physical-unit converted (V, RPM, 0.1°-based degrees). |
| `params_result` | `addr`, `speed_kp`, `speed_ki`, `pos_kp`, `pos_ki`, `accel`, `speed` | Runtime values cached by the gateway; after scan/config, values restored from gateway NVS are shown. `-1`/`null` = no gateway snapshot is available; `accel` is live-measured when readable. |
| `gimbal_state` | `pan`, `tilt`, `pan_angle`, `tilt_angle` | Pushed after gimbal-affecting commands. The angle fields are the latest requested target in the origin-relative coordinate; they are not measured position. Use `query_result` for measured feedback. |
| `wifi_status` (FF02) | `status`: `disconnected`\|`connecting`\|`connected`, `ip`?, `ssid`?, `rssi`? | On change plus every 5 s while connected. |
| `sys_status` (FF02) | `ble`, `pan`, `tilt`, `pan_angle`, `tilt_angle` | Every 5 s while a client is connected. |
| `log` | `lines: ["[TX] 7A 02 …", "[RX] …", …]` | Rate-limited hex TX/RX trace. |
| `error` | `msg` | Parse failures, timeouts, rejected commands, and the disconnect fail-stop notice. |

## 4. Web console HTTP API (`src/tools/web_app.py`)

Server binds `127.0.0.1` by default (`--webhost 0.0.0.0` opts out explicitly, with warnings). Every `/api/*` call requires the per-launch random token as `?token=` or an `X-Auth-Token` header (401 otherwise). The startup banner prints the ready-to-use URL with the token embedded.

| Endpoint | Parameters | Purpose |
|---|---|---|
| `GET /api/ports` | — | Enumerate serial ports. |
| `GET /api/connect` | `port`, `baud` (default 115200) | Open USB-TTL. |
| `GET /api/disconnect` | — | Close serial. |
| `GET /api/scan` | `start`, `end` (1–127) | Bus scan; auto-configures gimbal. |
| `GET /api/use` | `addr` | Select motor for single-motor commands. |
| `GET /api/setaddr` | `new` | Change motor address. |
| `GET /api/cmd` | `name`, `val` | Single-motor command (`enable`, `disable`, `setspeed`, `setangle`, `setmode`, `save`, `test`, …). |
| `GET /api/pid` | `type`, `val` | PID gains. |
| `GET /api/custom` | `cmd` | Raw protocol text command (incl. `scan`, `use`, `setaddr`). |
| `GET /api/gimbal/config` | `pan`, `tilt` | Axis IDs. |
| `GET /api/gimbal/jog` | `axis`, `dir`, `speed` | Nudge. |
| `GET /api/gimbal/move` | `pan`, `tilt` | Position follow (130 ms throttled by the page). |
| `GET /api/gimbal/center` / `zero` | — | Recenter / set zero. |
| `GET /api/log` | `clear` (optional) | Fetch or clear the log pane. |

Concurrency (P1-6): every `set_addr` + command pair is serialized by a lock, so parallel HTTP requests cannot interleave into wrong-axis frames.

## 5. Python library (`src/tools/f32c_protocol.py`)

```python
from f32c_protocol import F32CMotor

m = F32CMotor(port="COM7", addr=0x02, debug=True)
m.connect()

m.enable()                    # -> MotorResponse(..., parsed_text, device_confirmed=False)
m.set_speed(100)              # RPM, int16
m.set_single_angle(180.0)     # [0, 360)
m.query(F32CMotor.RT_VOLTAGE) # type codes: RT_SPEED/RT_TOTAL_ANGLE/RT_MECH_ANGLE/RT_ACCEL/RT_VOLTAGE

motors = m.scan_bus(start=1, end=16)   # [{addr, addr_hex, volt}]
m.connectivity_test()                   # {voltage, mech_angle, speed, summary}
```

`MotorResponse.valid` is false for short frames, bad BCC, timeouts, write failures, **and address mismatches (P1-4)**. For write-only commands, `valid=true` means the serial/BLE send path completed; `device_confirmed=false` because the motor provides no immediate ACK. Only a valid, address-matched feedback frame sets both `valid=true` and `device_confirmed=true`. The C++ class `F32CMotor` in `src/firmware/esp32-firmware/f32c_protocol.{h,cpp}` follows the same result distinction.

## 6. Flutter app layers (`src/app/lib`)

- `ble/command_sink.dart` — `abstract interface class CommandSink { Future<bool> sendCmd(Map<String, dynamic>); }`; `true` means the BLE write was accepted by the transport, not that the motor executed or reached its target. This is the seam the control layer depends on (test double injection).
- `ble/ble_service.dart` — scan/connect/MTU/discovery/notify dispatch/auto-reconnect (3 attempts); implements `CommandSink`.
- `ble/ble_uuids.dart` — UUID constants matching §1.
- `control/gimbal_controller.dart` — jog emission, 130 ms move throttling with final-position send on release, pan ±180°/tilt ±90° clamping before transport, 0.1° rounding.
- `models/` — `Motor`, `GimbalInfo`, `WifiInfo` (event payload decoding). `GimbalInfo.panAngle/tiltAngle` are target display values; they must not be presented as measured position.

## 7. Verification commands

From `projects/gen0-gimbal/` (full matrix and evidence status in [tests/README.md](../tests/README.md)):

```text
python -m py_compile src/tools/f32c_protocol.py src/tools/test_cli.py src/tools/web_app.py
python -m unittest discover -s tests -v          # 16 host tests
arduino-cli compile --fqbn esp32:esp32:esp32s3:CDCOnBoot=cdc src/firmware/esp32-firmware
flutter test                                      # from src/app, 17 tests
```
