# Architecture mapping

| Area | Status | Rationale |
|---|---|---|
| L4 Experience | implemented | App entrypoint and Session/Today Compose screens provide capture, results, playback and confirmed local deletion. |
| L3 Decision | not-applicable | The user directly requests rescue; no scene understanding, composition model or planner is included. |
| L2 Contract | not-applicable | No canonical shared contract is implemented. Simulator types are project-specific; future shared schemas require root RFC/conformance work. |
| L1.5 Orchestration | implemented | core/domain and feature state holders validate capabilities, serialize actions, gate frames and coordinate stop/rescue/save. |
| L1 Adapter | implemented | adapter/capture translates local camera operations to CameraX; adapter/gimbal supplies only an in-process semantic simulator. |
| L0 Execution | not-applicable | Android/platform camera execution is external; no physical gimbal firmware, deterministic hardware limits or validated hardware stop/watchdog is delivered. |
| Media | implemented | Proxy frame timing, buffering, encoding, MediaStore assets, Room metadata and playback are handled locally. |
| Transport | implemented | Simulated link lifecycle, acknowledgments, ordering and failure visibility are tested in process; no BLE/network transport is included. |
| Evaluation | implemented | Synthetic fixtures and host tests cover capture, session, simulation, media, deletion and playback. Device/HIL checks are pending. |
| Data Governance | implemented | No runtime network permission; backup disabled; local deletion receipts, synthetic fixture provenance and dependency records define handling boundaries. |

The six layers/four planes retain the root Argvid meanings. Orchestration cannot replace L0 safety; simulation is not hardware evidence. Root `protocol` and `conformance` remain the authority for future shared semantics. The `pre-alpha` label is an unversioned stage, not compatibility certification.

## Source flow

`app` composes `feature/session` and `feature/today`. Session orchestration uses `core/domain`, `adapter/capture` and the `adapter/gimbal` simulator. Capture rescue uses `data/media` for encoding/MediaStore writes and two-table Room storage. Today uses that local catalog and Media3 playback. `testing/fixtures` provides synthetic local state-transition cases to domain tests. The simulator's degree-based models are not serialized canonical L2 or motor commands.

`data/media/schemas` contains Room's generated local storage baseline for `sessions` and `moments`. It is not a copied protocol schema tree. The distinct application ID isolates its version-1 database; there is no destructive migration or cross-app data access.

The Activity retains one runtime/player owner alongside its feature ViewModels. Configuration recreation replaces only UI surfaces and reuses that owner; camera restart binds to the current lifecycle and preview. Stop cancels pending starts, and restart waits for prior moment work. Playback releases its player on background/navigation and the surviving surface observes and attaches the next player identity. Owner disposal performs finite camera teardown, not background recovery.

The host performs camera binding inside CaptureSessionController's transition lock, invalidating any cached stop result before the binding begins. Frames remain gated until moment work permits session start. Stop therefore covers the latest binding even during the moment-waiting gap; binding failure/cancellation tears down its partial sampler while still owning that lock.

A successful gallery publication is retained with its encoded staging metadata before catalog insertion. Catalog failure enters a distinct recoverable state; retry writes only the catalog, while abandon and a new rescue cannot drop the pending relationship. Stop, catalog-insert cancellation and session restart preserve this process-local recovery state. It is not process-death recovery. A saved Room row first links its staging path with cleanup-pending status. Only successful discard clears this link; Today deletion also consumes it and keeps retry status until both known local media copies are absent. These are project-local storage and lifecycle rules, not canonical L2 or hardware guarantees. Simulator Hold/Manual controls invalidate earlier queued motion transitions.
