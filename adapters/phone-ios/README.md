# iOS phone adapter

## Responsibility

Define the public bridge between Argvid contracts and permitted iOS capture capabilities.

## Non-goals

Application UI, cloud services, undocumented platform APIs, or App Store credentials.

## Inputs and outputs

Inputs are capture intents and reported phone capability; outputs are platform-safe capture operations and evidence.

## Dependencies

Depends on approved root protocols and documented Apple APIs.

## Invariants

Platform permission denial and unsupported capture controls remain explicit.

## Start condition

Start after the protocol baseline and a public capability mapping RFC are accepted.

## Ownership and review

iOS adapter owner with protocol and privacy review.

## Verification

Fixture-driven mapping tests and device tests with non-sensitive media.

## Public use cases

Expose permitted phone capture capability to a public application through a reusable contract boundary; application UI belongs in its project.

## License and data

Source and documentation default to Apache-2.0. Use synthetic or explicitly licensed public inputs with recorded provenance; publication rights require human review. Third-party assets, weights, datasets, and hardware design sources need separate license review. This documented boundary is not an activated implementation, released compatibility guarantee, or completed HIL result.
