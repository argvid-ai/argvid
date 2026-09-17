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

Transport rules: one JSON object per write/notify, UTF-8, no framing. Single messages must stay under 244 bytes (the current firmware never exceeds this; if you add larger events you must add MTU framing first). Commands produce at most one response event each, except `scan` which returns a single `scan_result` after ~2 s.

## 2. Safety behaviors (caller-visible, L0)

These are enforced in firmware and cannot be disabled from the app:

- **Disconnect fail-stop (P1-1).** On BLE disconnect the firmware drops all queued commands and forces both axes into speed mode at 0 RPM (holding torque; chosen over `disable` so the vertical axis does not sag under gravity). Commands written while disconnected are discarded. Requires hardware-in-the-loop verification before any safety claim.
- **Stop bypass (P1-2).** `jog` with `dir=0` is routed through atomic flags around the 12-slot command queue: a stop is never dropped due to a full queue, and stale queued motion commands are flushed so they do not execute after the stop.
- **Limits (P1-3).** Tilt-axis targets outside ±90° are explicitly rejected with an error (no silent clamping) on `set_angle`; multi-turn commands on the tilt axis are checked against ±90° in the signed origin-relative coordinate; `set_speed` is capped at ±300 RPM; unknown `jog` axes are rejected. Pan ±180° covers the full single-turn range.
- **Response validation (P1-4).** A feedback frame whose address does not match the commanded address is a failure (`valid=false`) in both the C++ firmware and the Python library.
- **Mode cache correctness (P1-5).** Mode/enable failures abort the command with an error instead of reporting success; external `set_mode` / `disable` / `factory_reset` / `setaddr` on a gimbal axis invalidates the cached mode so the next command re-synchronizes.

## 3. JSON contract

### 3.1 Commands (write to FF03 / FF01)

All motor commands accept an optional `"addr"` (1–127, default `0x02` on the bus).

| `cmd` | Fields | Behavior / errors |
|---|---|---|
| `scan` | — | Scans bus addresses 1–16 (~2 s). Emits `scan_result`; auto-configures gimbal if ≥2 motors found. |
| `gimbal_config` | `pan`, `tilt` (1–127) | Sets axis motor IDs. Error if out of range. Emits `gimbal_state`. |
| `jog` | `axis` (`pan`\|`tilt`), `dir` (1\|-1\|0), `speed` (RPM) | Speed-mode nudge (debug interface; the app uses position stepping). `dir=0` stops (guaranteed delivery, see §2). Errors: unknown axis, gimbal not configured. Speed clamped to 300 RPM. |
| `move` | `pan` (−180…180), `tilt` (−90…90) | **Multi-turn absolute position (mode 1, T-curve)** relative to the origin; 0.1° resolution; axes clamp at bounds; pan/tilt frames are sent back-to-back without waiting for the pan reply so both axes start together. Error if gimbal not configured. Emits `gimbal_state`. |
| `center` | — | `move(0, 0)` back to the origin. |
| `origin` | — | Marks both axes' current position as the multi-turn origin (clears accumulated multi-turn angle); subsequent position control is relative to it. |
| `zero` | — | Sets both axes' current position as single-turn 0°; follow with `save` for persistence. |
| `enable` / `disable` | — | Motor enable/disable. `disable` on a gimbal axis invalidates its mode cache. |
| `set_mode` | `mode` (0–4; 0=speed, 1=multi-turn position T-curve, 2=single-turn position T-curve) | Error outside 0–4. Invalidates gimbal axis cache. |
| `set_speed` | `rpm` (−300…300) | Error beyond ±300. |
| `set_angle` | `angle` [0,360) | Single-turn absolute. Tilt-axis targets beyond ±90° are **rejected** (error, not clamped). |
| `set_multi_angle` | `angle` | Multi-turn absolute (signed, origin-relative). Tilt-axis targets beyond ±90° are **rejected**. |
| `set_accel` | `accel` | Acceleration (rev/s²). Cached for `get_params`. |
| `get_params` | — | Returns the session-cached PID/speed/accel values (the protocol cannot read them back; `-1` = not set this power cycle) plus a live-measured acceleration. Emits `params_result`. |
| `query` | `type`: `voltage`\|`speed`\|`total_angle`\|`mech_angle`\|`accel` | Emits `query_result`. Error on unknown type. |
| `save` | — | Persist parameters to flash. |
| `clear_total` | — | Reset accumulated multi-turn angle. |
| `set_zero` | — | Set current position as single-turn zero (single motor). |
| `factory_reset` | — | Restore factory defaults. Invalidates gimbal axis cache. |
| `setaddr` | `new_addr` (1–127) | Change motor bus address; control switches to the new address. |
| `set_speed_kp` / `set_speed_ki` / `set_pos_kp` / `set_pos_ki` | `val` | PID gains. Successful values are cached for `get_params`. |
| `test` | — | Connectivity test (voltage/mech-angle/speed queries). |

WiFi provisioning (write to FF01): `{"ssid": "...", "pass": "..."}`. Credentials are stored in NVS and auto-reconnect on reboot; no `pass` echo in any event.

Timeouts: a command whose motor-bus response does not arrive within 500 ms fails with an `error` event stating the reason.

### 3.2 Events (notify from FF04 / FF02)

| `event` | Fields | Notes |
|---|---|---|
| `cmd_result` | `ok` (bool), `msg` | Result of one command. |
| `scan_result` | `ok`, `motors: [{id, volt}]` | One event after the full bus scan. |
| `query_result` | `addr`, `type`, `value`, `text` | `value` is physical-unit converted (V, RPM, 0.1°-based degrees). |
| `params_result` | `addr`, `speed_kp`, `speed_ki`, `pos_kp`, `pos_ki`, `accel`, `speed` | Session-cached tuning values; `-1`/`null` = not set this power cycle (motor uses flash parameters); `accel` is live-measured when readable. |
| `gimbal_state` | `pan`, `tilt`, `pan_angle`, `tilt_angle` | Pushed after gimbal-affecting commands. |
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

m.enable()                    # -> MotorResponse(valid, bcc_ok, type_code, value, parsed_text)
m.set_speed(100)              # RPM, int16
m.set_single_angle(180.0)     # [0, 360)
m.query(F32CMotor.RT_VOLTAGE) # type codes: RT_SPEED/RT_TOTAL_ANGLE/RT_MECH_ANGLE/RT_ACCEL/RT_VOLTAGE

motors = m.scan_bus(start=1, end=16)   # [{addr, addr_hex, volt}]
m.connectivity_test()                   # {voltage, mech_angle, speed, summary}
```

`MotorResponse.valid` is false for short frames, bad BCC, timeouts, **and address mismatches (P1-4)**. The C++ class `F32CMotor` in `src/firmware/esp32-firmware/f32c_protocol.{h,cpp}` mirrors this API one-to-one and must be kept in sync (Python is the reference).

## 6. Flutter app layers (`src/app/lib`)

- `ble/command_sink.dart` — `abstract interface class CommandSink { Future<void> sendCmd(Map<String, dynamic>); }`, the seam the control layer depends on (test double injection).
- `ble/ble_service.dart` — scan/connect/MTU/discovery/notify dispatch/auto-reconnect (3 attempts); implements `CommandSink`.
- `ble/ble_uuids.dart` — UUID constants matching §1.
- `control/gimbal_controller.dart` — jog emission, 130 ms move throttling with final-position send on release, pan ±180°/tilt ±90° clamping before transport, 0.1° rounding.
- `models/` — `Motor`, `GimbalInfo`, `WifiInfo` (event payload decoding).

## 7. Verification commands

From `projects/gen0-gimbal/` (full matrix and evidence status in [tests/README.md](../tests/README.md)):

```text
python -m py_compile src/tools/f32c_protocol.py src/tools/test_cli.py src/tools/web_app.py
python -m unittest discover -s tests -v          # 13 host tests
arduino-cli compile --fqbn esp32:esp32:esp32s3:CDCOnBoot=cdc src/firmware/esp32-firmware
flutter test                                      # from src/app, 17 tests
```
