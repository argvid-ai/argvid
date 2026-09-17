# Project context

## Public problem

A 2-axis fixed smartphone gimbal built from hobby brushless motor modules currently has no reproducible public control stack. This project publishes the full driver/control boundary so that a public app can interoperate with a documented device, and so that reviewers can check the safety behavior of the execution layer.

## Delivery boundary

Complete public project delivery (repository scope inclusion basis 4) plus a reproducible reference implementation (basis 2):

- `src/firmware`: ESP32-S3 Arduino firmware acting as a BLE gateway. It receives JSON commands, enqueues them on a FreeRTOS queue, executes them on the main loop against the F32C UART bus, and pushes results and logs back as BLE notifications.
- `src/app`: Flutter mobile app for BLE scanning and connection, WiFi provisioning, per-motor commands, and dual-axis gimbal control (cross keypad jog and virtual joystick position following).
- `src/tools`: Python implementation of the same F32C protocol over USB-TTL with a command-line tool and a Flask web debug console; it doubles as the executable specification that the C++ port was derived from.

## Dependencies and provenance

All dependencies are publicly available and listed with identity, version, and license in [THIRD_PARTY.md](THIRD_PARTY.md). No weights, datasets, media assets, or hardware design sources are included. The F32C wire protocol semantics were independently documented from behavior observed on hardware; vendor manuals and vendor example code are excluded and not redistributed.

## Accepted decisions and conflicts

The maintainer-approved public-safe task brief for Gen0.5 gimbal driver/control authorizes this delivery. The GATT draft PR #2 was not merged and is not adopted; the BLE UUID and JSON schema here are recorded as a project-local interface, and any shared contract would go through root RFC review. No conflicts with accepted root architecture are known.

## Known limitations

- Hardware-in-the-loop validation is pending; no hardware safety certification is claimed.
- The web console and CLI assume a direct USB-TTL serial connection and were validated on Windows hosts.
- The firmware targets the ESP32-S3 Arduino core and ArduinoJson 7.x; no other toolchain is claimed.
