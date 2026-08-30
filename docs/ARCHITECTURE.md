# Architecture

## Objective

Separate user experience, decisions, contracts, orchestration, translation, and deterministic execution. A complete public project may implement, reuse, or explicitly omit layers while retaining the contract and safety boundaries.

## Canonical layers

| Layer | Responsibility | Boundary |
|---|---|---|
| L4 Experience | User interaction, tasks, results | No direct hardware drive |
| L3 Decision | Scene understanding, composition, plans | No device commands or safety bypass |
| L2 Contract | Intent, capability, target, state, evidence | No device-specific execution |
| L1.5 Orchestration | Validation, scheduling, preemption, state, resources | No L2 semantic change or L0 safety substitute |
| L1 Adapter | Generic contract to device operation | No silent capability downgrade |
| L0 Execution | Deterministic control, limits, emergency stop, watchdog | Model output is never a safety guarantee |

The normal direction is L4 → optional L3 → L2 → L1.5 → L1 → L0. L2 is the shared semantic boundary, not a mandatory running service. A user may supply intent directly without L3. Upward state and evidence make execution observable. Adapter-specific commands remain below L2; scheduling and preemption belong at L1.5 rather than being hidden in a decision model.

## Cross-cutting planes

- Media: frame/sample descriptors, timing, and permitted media flows.
- Transport: delivery, ordering, connection lifecycle, timeout, and reconnection behavior.
- Evaluation: conformance, safety tests, benchmarks, and reproducible evidence.
- Data Governance: provenance, licenses, privacy, retention, and publication review.

Each project explicitly maps all six layers and all four planes. A plane can span several layers; it is not an extra control layer.

## L2 concepts

`CaptureIntent`, `DeviceCapability`, `TargetPose`, `SafetyStatus`, and `EvidenceRecord` reserve concepts, not stable schemas. Fields, units, constraints, compatibility, and failure semantics require root RFCs and paired valid/invalid fixtures. Projects reuse [protocol](../protocol/README.md) and [conformance](../conformance/README.md); they do not copy schema trees.

## Safety and validation

L0 enforces bounds and deterministic stop/watchdog behavior. Lost contact leads to a safe stopped state. Models offer proposals; unsupported capabilities are explicitly degraded or rejected. Orchestration can cancel/preempt work but cannot substitute for physical safety enforcement.

Synthetic replay, simulation, host tests, and hardware-in-the-loop checks are distinct evidence classes. An unrun hardware test remains pending. No safety certification is claimed.

## Public composition

An app contributor can own L4, an integration contributor L1.5, and a device contributor L1/L0, while sharing L2 contracts and all relevant planes. These are public contribution roles, not organizational assignments. The [collaboration guide](CONTRIBUTOR_WORKFLOW.md) explains handoffs. The [project template](../projects/_template/README.md) is a proposal and does not activate implementation.

## Open decisions

Exact schemas/units, capability granularity, transport mappings, media/evidence handling, hardware source licensing, and hardware acceptance thresholds require reviewed RFCs. `pre-alpha` is the current unversioned protocol stage; release compatibility requires an approved immutable protocol identity and human review.
