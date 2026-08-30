# Benchmarks

## Responsibility

Define public, reproducible measurements for protocol and reference-implementation behavior.

## Non-goals

Marketing claims, application quality scoring, personal-data datasets, or unreproducible leaderboards.

## Inputs and outputs

Inputs are public fixtures and benchmark definitions; outputs are versioned metrics with environment metadata.

## Dependencies

Depends on stable conformance entry points and license-clean data.

## Invariants

Every result records revision, environment, input provenance, and unexecuted checks.

## Start condition

Start after the first reference path passes conformance.

## Ownership and review

Benchmark owner with affected module review.

## Verification

Re-run published commands and compare against declared tolerances.

## Public use cases

Measure reproducible public latency, resource use, or contract behavior in the Evaluation plane with declared inputs and environment.

## License and data

Source and documentation default to Apache-2.0. Use synthetic or explicitly licensed public inputs with recorded provenance; publication rights require human review. Third-party assets, weights, datasets, and hardware design sources need separate license review. This documented boundary is not an activated implementation, released compatibility guarantee, or completed HIL result.
