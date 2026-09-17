# Architecture mapping

| Area | Status | Rationale |
|---|---|---|
| L4 Experience | implemented | App entrypoint and Session/Today Compose screens provide capture, results, playback and confirmed local deletion. |
| L3 Decision | not-applicable | The user directly requests rescue; no scene understanding, composition model or planner is included. |
| L2 Contract | not-applicable | No canonical shared contract is implemented. Simulator types are project-specific; future shared schemas require root RFC/conformance work. |
| L1.5 Orchestration | implemented | core/domain and feature state holders validate capabilities, serialize actions, gate frames and coordinate stop/rescue/save. |
| L1 Adapter | implemented | adapter/capture translates local camera operations to CameraX; adapter/gimbal supplies the in-process semantic simulator (default) and an opt-in F32C BLE bridge for a real gimbal behind explicit source selection. |
| L0 Execution | not-applicable | Android/platform camera execution is external; no physical gimbal firmware, deterministic hardware limits or validated hardware stop/watchdog is delivered. |
| Media | implemented | Proxy frame timing, buffering, encoding, MediaStore assets, Room metadata and playback are handled locally. |
| Transport | implemented | Simulated link lifecycle, acknowledgments, ordering and failure visibility are tested in process. The opt-in F32C BLE transport (scan/GATT/telemetry polling) is project-local, field-tested on one approved device, and is not a canonical L2 transport. |
| Evaluation | implemented | Synthetic fixtures and host tests cover capture, session, simulation, media, deletion and playback. Device/HIL checks are pending. |
| Data Governance | implemented | No runtime network permission; backup disabled; local deletion receipts, synthetic fixture provenance and dependency records define handling boundaries. |

The six layers/four planes retain the root Argvid meanings. Orchestration cannot replace L0 safety; simulation is not hardware evidence. Root `protocol` and `conformance` remain the authority for future shared semantics. The `pre-alpha` label is an unversioned stage, not compatibility certification.

## Source flow

`app` composes `feature/session` and `feature/today`. Session orchestration uses `core/domain`, `adapter/capture` and the `adapter/gimbal` simulator (default) or F32C BLE bridge (explicit opt-in). Capture rescue uses `data/media` for encoding/MediaStore writes and two-table Room storage. Today uses that local catalog and Media3 playback. `testing/fixtures` provides synthetic local state-transition cases to domain tests. `adapter/capture` also hosts the reviewed BlazeFace face-detection pipeline for the opt-in tracking feature. Neither the simulator's degree-based models nor the BLE bridge's JSON protocol are serialized canonical L2 or motor commands.

`data/media/schemas` contains Room's generated local storage baseline for `sessions` and `moments`. It is not a copied protocol schema tree. The distinct application ID isolates its version-1 database; there is no destructive migration or cross-app data access.

Foreground audio uses platform AudioRecord in `adapter/capture`, a bounded PCM16 ring in `core/domain`, and AAC-LC encoding in `data/media`. `AudioVideoRescueBuffer` gates rescue on both tracks and trims audio to the owned video's request window; `AudioVideoSampler` tears down both sources. AudioTimestamp MONOTONIC timestamps align with camera timestamps converted from BOOTTIME when camera metadata declares REALTIME. UNKNOWN camera clocks retain the platform's approximate uptime basis; actual synchronization remains a device check. The muxer registers both output formats before starting and writes one MP4. No new Room columns, canonical contracts, runtime libraries or standalone audio files are introduced.

Today observes all saved catalog candidates ordered newest first and preserves selection by moment ID. Per-selection URI verification rejects stale asynchronous results; switching assets releases playback. Confirmation, deletion retry and receipt clearing retain the original moment ID independently of the current selection. This uses the existing schema without a migration.

The Activity retains one runtime/player owner alongside its feature ViewModels. Configuration recreation replaces only UI surfaces and reuses that owner; camera restart binds to the current lifecycle and preview. Stop cancels pending starts, and restart waits for prior moment work. Playback releases its player on background/navigation and the surviving surface observes and attaches the next player identity. Owner disposal performs finite camera teardown, not background recovery.

The host performs camera binding inside CaptureSessionController's transition lock, invalidating any cached stop result before the binding begins. Frames remain gated until moment work permits session start. Stop therefore covers the latest binding even during the moment-waiting gap; binding failure/cancellation tears down its partial sampler while still owning that lock.

A successful gallery publication is retained with its encoded staging metadata before catalog insertion. Catalog failure enters a distinct recoverable state; retry writes only the catalog, while abandon and a new rescue cannot drop the pending relationship. Stop, catalog-insert cancellation and session restart preserve this process-local recovery state. It is not process-death recovery. A saved Room row first links its staging path with cleanup-pending status. Only successful discard clears this link; Today deletion also consumes it and keeps retry status until both known local media copies are absent. These are project-local storage and lifecycle rules, not canonical L2 or hardware guarantees. Simulator Hold/Manual controls invalidate earlier queued motion transitions.
