# Conformance tests

## Responsibility

Hold executable compatibility tests shared by public implementations.

## Non-goals

Implementation-specific unit tests or tests that require undisclosed datasets.

## Inputs and outputs

Inputs are public fixtures and implementation adapters; outputs are deterministic test results and evidence summaries.

## Dependencies

Depends on `protocol/` and public adapter entry points.

## Invariants

Tests do not special-case a vendor to hide incompatibility.

## Start condition

Start after the first fixtures exist.

## Ownership and review

Conformance owner with protocol review.

## Verification

Run locally and in CI on every contract or adapter change.

## Public use cases

Run the same synthetic acceptance cases locally and in CI; structural repository checks are not a substitute for actual conformance.

## License and data

Source and documentation default to Apache-2.0. Use synthetic or explicitly licensed public inputs with recorded provenance; publication rights require human review. Third-party assets, weights, datasets, and hardware design sources need separate license review. This documented boundary is not an activated implementation, released compatibility guarantee, or completed HIL result.
