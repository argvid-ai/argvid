# Reference director

## Responsibility

Provide a minimal, inspectable public reference that turns prepared observations into capture intent and target pose.

## Non-goals

Product-specific quality tuning, hosted model routing, undisclosed prompts, or direct device control.

## Inputs and outputs

Inputs are public observation examples and capabilities; outputs are public contracts plus decision evidence.

## Dependencies

Depends only on approved root protocol semantics and public reference data.

## Invariants

Outputs remain explainable, capability-aware, and unable to override deterministic safety.

## Start condition

Start after the minimum protocols and replay conformance path exist.

## Ownership and review

Reference director owner with protocol review.

## Verification

Deterministic example tests and conformance checks over replay fixtures.

## Public use cases

Provide an independent, reproducible L3 decision baseline and explainable output for public replay and project integration.

## License and data

Source and documentation default to Apache-2.0. Use synthetic or explicitly licensed public inputs with recorded provenance; publication rights require human review. Third-party assets, weights, datasets, and hardware design sources need separate license review. This documented boundary is not an activated implementation, released compatibility guarantee, or completed HIL result.
