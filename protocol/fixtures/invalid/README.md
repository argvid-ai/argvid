# Invalid protocol fixtures

## Responsibility

Document messages that conforming implementations must reject or degrade explicitly.

## Non-goals

Fuzzing corpora, exploit payloads, or production incident data.

## Inputs and outputs

Inputs are schema constraints and known failure modes; outputs are synthetic invalid examples with expected outcomes.

## Dependencies

Depends on approved root schemas, safety rules, and compatibility decisions.

## Invariants

Each fixture states exactly why it is invalid and the expected error or degraded result.

## Start condition

Start with the first schema pull request.

## Ownership and review

Protocol owner with safety review for unsafe or ambiguous cases.

## Verification

Every fixture must fail validation for the documented reason.

## Public use cases

Prove explicit rejection or degradation for synthetic invalid or unsupported requests.

## License and data

Source and documentation default to Apache-2.0. Use synthetic or explicitly licensed public inputs with recorded provenance; publication rights require human review. Third-party assets, weights, datasets, and hardware design sources need separate license review. This documented boundary is not an activated implementation, released compatibility guarantee, or completed HIL result.
