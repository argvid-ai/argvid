# Media boundary

## Responsibility

Define public media descriptors, synthetic samples, and safe handling rules used by reference implementations.

## Non-goals

Hosting real user media, production retention, dataset indexing services, or model-training corpora.

## Inputs and outputs

Inputs are synthetic or explicitly licensed assets; outputs are descriptors and small reproducible samples.

## Dependencies

Depends on public licensing and privacy decisions.

## Invariants

No personal, restricted, or ambiguously licensed media is committed.

## Start condition

Start after a media-descriptor RFC and asset provenance template are accepted.

## Ownership and review

Media owner with privacy and license review.

## Verification

Provenance checks, size limits, and fixture reproducibility.

## Public use cases

Share synthetic, licensed frame/sample descriptors for replay and public project integration across the Media and Data Governance planes.

## License and data

Source and documentation default to Apache-2.0. Use synthetic or explicitly licensed public inputs with recorded provenance; publication rights require human review. Third-party assets, weights, datasets, and hardware design sources need separate license review. This documented boundary is not an activated implementation, released compatibility guarantee, or completed HIL result.
