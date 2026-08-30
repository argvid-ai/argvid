# Replay example

## Responsibility

Provide the smallest public end-to-end example from synthetic input to protocol evidence.

## Non-goals

Production quality, real user media, cloud deployment, or application-specific directing behavior.

## Inputs and outputs

Inputs are synthetic, license-clean samples; outputs are deterministic intent, target, and evidence records.

## Dependencies

Depends on approved public fixtures, replay adapter, and reference-director.

## Invariants

The example runs offline and contains no credentials or personal data.

## Start condition

Start after the replay adapter and first conformance runner exist.

## Ownership and review

Replay owner with documentation and protocol review.

## Verification

A single documented command reproduces checked-in expected output.

## Public use cases

Teach the public intent-to-evidence flow using synthetic input and the reference-director with no credentials.

## License and data

Source and documentation default to Apache-2.0. Use synthetic or explicitly licensed public inputs with recorded provenance; publication rights require human review. Third-party assets, weights, datasets, and hardware design sources need separate license review. This documented boundary is not an activated implementation, released compatibility guarantee, or completed HIL result.
