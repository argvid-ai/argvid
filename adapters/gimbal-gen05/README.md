# Gen0.5 gimbal adapter

## Responsibility

Bridge public target poses and capability negotiation to the reference fixed-gimbal control boundary.

## Non-goals

Mobile robot navigation, application-specific motion style, direct safety bypass, or undisclosed hardware commands.

## Inputs and outputs

Inputs are capability and target-pose messages; outputs are bounded commands, safety status, and evidence.

## Dependencies

Depends on protocol contracts, a simulator, and host-testable firmware interfaces.

## Invariants

Lost contact stops motion; bounds are enforced below the model; unsupported requests become degraded results.

## Start condition

Start after protocol fixtures and a gimbal simulator are accepted.

The GATT v1 boundary is proposed in [RFC 0001](../../docs/rfcs/0001-gen05-gimbal-gatt-v1.md). Its UUIDs and fixtures are non-canonical until protocol and safety review accepts the RFC. Simulator work may use the semantic adapter boundary, but production device integrations must pin only an accepted fixture commit.

## Ownership and review

Gen0.5 adapter owner with mandatory protocol and safety review.

## Verification

Shared fixtures, simulator conformance, firmware host tests, then hardware-in-the-loop evidence.

Current evidence: byte-level fixture conformance is automated; simulator, firmware host, and physical hardware checks remain pending. A check that has not run is never reported as passed.

## Public use cases

Validate generic target poses against a simulator, then a reviewed public fixed-gimbal setup, without bypassing local limits.

## License and data

Source and documentation default to Apache-2.0. Use synthetic or explicitly licensed public inputs with recorded provenance; publication rights require human review. Third-party assets, weights, datasets, and hardware design sources need separate license review. This documented boundary is not an activated implementation, released compatibility guarantee, or completed HIL result.
