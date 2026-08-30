# ONVIF PTZ adapter

## Responsibility

Map public target-pose semantics to standards-based PTZ camera capabilities.

## Non-goals

Vendor credentials, surveillance workflows, deployment management, or undocumented camera commands.

## Inputs and outputs

Inputs are capability and target-pose contracts; outputs are bounded PTZ operations, status, and evidence.

## Dependencies

Depends on public protocols and applicable ONVIF specifications.

## Invariants

Authentication data never enters source control and unsupported axes are reported explicitly.

## Start condition

Start after a capability-mapping RFC and simulator endpoint are available.

## Ownership and review

PTZ adapter owner with protocol and security review.

## Verification

Simulator conformance followed by opt-in device tests.

## Public use cases

Exercise public capability/target semantics with a simulator before opt-in, authorized camera testing.

## License and data

Source and documentation default to Apache-2.0. Use synthetic or explicitly licensed public inputs with recorded provenance; publication rights require human review. Third-party assets, weights, datasets, and hardware design sources need separate license review. This documented boundary is not an activated implementation, released compatibility guarantee, or completed HIL result.
