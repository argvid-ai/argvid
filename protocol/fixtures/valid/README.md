# Valid protocol fixtures

## Responsibility

Provide minimal and representative messages that every conforming implementation must accept.

## Non-goals

Performance benchmarks, production traces, or real user recordings.

## Inputs and outputs

Inputs are approved root schemas; outputs are synthetic, license-clean fixture files.

## Dependencies

Depends on `protocol/schema/` and versioning decisions.

## Invariants

Fixtures contain only approved synthetic public data and declare the protocol version they target.

## Start condition

Start with the first schema pull request.

## Ownership and review

Protocol owner; adapter owners review representative coverage.

## Verification

Every fixture must validate and be exercised by conformance tests.

## Public use cases

Supply synthetic accepted examples for every implementation of the same public contract.

## License and data

Source and documentation default to Apache-2.0. Use synthetic or explicitly licensed public inputs with recorded provenance; publication rights require human review. Third-party assets, weights, datasets, and hardware design sources need separate license review. This documented boundary is not an activated implementation, released compatibility guarantee, or completed HIL result.
