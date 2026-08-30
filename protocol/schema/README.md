# Protocol schemas

## Responsibility

Store machine-readable public contract schemas.

## Non-goals

Generated client code, unreviewed extensions, runtime device drivers, or undocumented experimental fields.

## Inputs and outputs

Inputs are accepted contract semantics. Outputs are versioned schemas consumed by fixtures and conformance tests.

## Dependencies

Depends on protocol decisions and versioning rules; consumers depend on approved root schemas.

## Invariants

Every field has defined units, optionality, bounds, and compatibility meaning.

## Start condition

Start after the minimum contract RFC is accepted.

## Ownership and review

Protocol owner with safety review for safety-related fields.

## Verification

Schema validation over all valid and invalid fixtures plus compatibility checks.

## Public use cases

Define the single shared schema source for public projects and adapters; projects reuse root schemas rather than copy them.

## License and data

Source and documentation default to Apache-2.0. Use synthetic or explicitly licensed public inputs with recorded provenance; publication rights require human review. Third-party assets, weights, datasets, and hardware design sources need separate license review. This documented boundary is not an activated implementation, released compatibility guarantee, or completed HIL result.
