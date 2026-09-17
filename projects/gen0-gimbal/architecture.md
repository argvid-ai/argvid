# Architecture mapping

| Area | Status | Rationale |
|---|---|---|
| L4 Experience | implemented | Flutter app pages for BLE connect, WiFi provisioning, motor console, and dual-axis gimbal control. |
| L3 Decision | not-applicable | The user supplies intent directly through the UI; no scene understanding or composition exists here. |
| L2 Contract | not-applicable | The BLE JSON boundary is a project-local device interface, not canonical L2 semantics; a shared contract would require root RFC review. |
| L1.5 Orchestration | implemented | App-side jog throttling with mode caching and a firmware FreeRTOS command queue serialize and bound commands without changing their semantics. |
| L1 Adapter | implemented | ESP32-S3 BLE gateway translates JSON commands into F32C UART frames; BLE UUIDs and the JSON event schema are documented in docs. |
| L0 Execution | implemented | Firmware clamps tilt to plus/minus 90 and pan to plus/minus 180 degrees, disables motion on link loss, and keeps the ESP32 task watchdog enabled. |
| Media | not-applicable | No media capture, processing, or delivery exists in this project. |
| Transport | implemented | BLE GATT with MTU negotiation and automatic reconnect, plus USB serial transport for the host tools. |
| Evaluation | implemented | flutter test host tests cover pure app logic; device and hardware-in-the-loop checks are pending. |
| Data Governance | implemented | Dependency identity, version, and license are recorded in THIRD_PARTY.md; no credentials, logs, or personal data are included. |

Root [architecture](../../docs/ARCHITECTURE.md) governs this project. L3 remains not-applicable because intent comes directly from the user. Orchestration does not change L2 semantics or replace L0 safety; L0 limits are enforced in firmware and cannot be disabled by app or model output. This mapping grants no release compatibility.
