# Replay adapter

## Responsibility

Replay recorded or synthetic inputs deterministically through the public capture pipeline.

## Non-goals

Live camera control, real user media ingestion, or production retention policy.

## Inputs and outputs

Inputs are synthetic or approved recordings and timestamps; outputs are protocol events and evidence records.

## Dependencies

Depends on protocol fixtures and public media abstractions.

## Invariants

Runs are reproducible, input provenance is recorded, and no restricted media is committed.

## Start condition

Start after the first contract fixtures and synthetic replay example exist.

## Ownership and review

Gen0 adapter owner with protocol review.

## Verification

Deterministic replay tests against shared conformance fixtures.

## Public use cases

Reproduce capture behavior from synthetic observations without a live device; compare evidence across public implementations.

## License and data

Source and documentation default to Apache-2.0. Use synthetic or explicitly licensed public inputs with recorded provenance; publication rights require human review. Third-party assets, weights, datasets, and hardware design sources need separate license review. This documented boundary is not an activated implementation, released compatibility guarantee, or completed HIL result.
