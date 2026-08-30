# CAD sources

## Responsibility

Hold approved, versionable reference design sources and export instructions.

## Non-goals

Unlicensed vendor files, production tooling, opaque binaries without provenance, or unpublished industrial design.

## Inputs and outputs

Inputs are reviewed mechanical requirements; outputs are editable sources, checksums, and reproducible exports.

## Dependencies

Depends on the hardware license decision and BOM interfaces.

## Invariants

Every source declares tool/version, units, origin, license, and export procedure.

## Start condition

Start after the hardware license and first mechanical review are approved.

## Ownership and review

Mechanical owner with hardware and license review.

## Verification

Open the source in the declared tool and reproduce documented exports and dimensions.

## Public use cases

Make approved design sources independently editable and their exports reproducible.

## License and data

Source and documentation default to Apache-2.0. Use synthetic or explicitly licensed public inputs with recorded provenance; publication rights require human review. Third-party assets, weights, datasets, and hardware design sources need separate license review. This documented boundary is not an activated implementation, released compatibility guarantee, or completed HIL result.
