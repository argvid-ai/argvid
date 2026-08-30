# Adapters

## Responsibility

Translate public L2 contracts into device or simulator operations while preserving semantics and capability limits.

## Non-goals

Scene interpretation, product UI, capture decision strategies, or bypassing device safety.

## Inputs and outputs

Inputs are target intents and capabilities; outputs are device operations, explicit degraded states, and evidence.

## Dependencies

Depends on approved root protocol contracts and device APIs. Higher layers depend on adapter interfaces.

## Invariants

No silent capability clamping; no L2 motor details; lost contact becomes an explicit safe state.

## Start condition

An adapter starts when its device boundary, simulator, fixtures, and acceptance evidence are defined.

## Ownership and review

Adapter owner plus protocol and safety reviewers.

## Verification

Shared conformance fixtures, adapter tests, simulator checks, and hardware evidence where applicable.

## Public use cases

Map an approved public contract to a replay or device endpoint so complete public projects can reuse the same semantics. Orchestration belongs at L1.5; device translation belongs at L1.

## License and data

Source and documentation default to Apache-2.0. Use synthetic or explicitly licensed public inputs with recorded provenance; publication rights require human review. Third-party assets, weights, datasets, and hardware design sources need separate license review. This documented boundary is not an activated implementation, released compatibility guarantee, or completed HIL result.
