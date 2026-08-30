# Protocol

## Responsibility

Define versioned, device-independent contracts for capture intent, device capability, target pose, safety status, and evidence records.

## Non-goals

Device drivers, capture decision logic, motor commands, media storage, and model weights.

## Inputs and outputs

Inputs are reviewed RFCs and compatibility requirements. Outputs are schemas, semantic rules, fixtures, and version notes.

## Dependencies

Depends only on public standards and accepted decisions. Adapters and directors depend on this module, never the reverse.

## Invariants

Contracts remain device-independent; unsupported capability is explicit; safety state is observable; semantic changes are versioned.

## Start condition

Implementation starts when an issue defines the minimum field set, examples, invalid cases, and version impact.

## Ownership and review

Initial owner: repository maintainer. Protocol and safety review are required for semantic changes.

## Verification

Validate schemas, valid and invalid fixtures, backward-compatibility expectations, and conformance behavior.

## Public use cases

Let independent public applications and devices agree on intent, capabilities, targets, state, and evidence without knowing device-specific execution.

## License and data

Source and documentation default to Apache-2.0. Use synthetic or explicitly licensed public inputs with recorded provenance; publication rights require human review. Third-party assets, weights, datasets, and hardware design sources need separate license review. This documented boundary is not an activated implementation, released compatibility guarantee, or completed HIL result.
