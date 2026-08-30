# Conformance

## Responsibility

Provide a shared compatibility ruler for protocols, simulators, adapters, and host-tested firmware.

## Non-goals

Product quality scoring, model-quality evaluation, full hardware certification, or vendor-specific integration tests.

## Inputs and outputs

Inputs are approved root schemas and fixtures. Outputs are deterministic pass, fail, or pending results with evidence.

## Dependencies

Depends on `protocol/`; all public implementations depend on its observable semantics.

## Invariants

The same fixture means the same thing across replay and gimbal paths; unexecuted hardware checks remain pending.

## Start condition

Start when the first schema and paired fixtures exist.

## Ownership and review

Protocol owner and the affected adapter owner.

## Verification

Run against valid and invalid fixtures, simulator outputs, and later hardware-in-the-loop evidence.

## Public use cases

Compare observable contract behavior across public app, adapter, simulator, and execution paths.

## License and data

Source and documentation default to Apache-2.0. Use synthetic or explicitly licensed public inputs with recorded provenance; publication rights require human review. Third-party assets, weights, datasets, and hardware design sources need separate license review. This documented boundary is not an activated implementation, released compatibility guarantee, or completed HIL result.
