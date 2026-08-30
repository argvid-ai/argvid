# Public projects

This directory holds complete, self-contained public project deliveries reviewed under [repository scope](../docs/REPOSITORY_SCOPE.md). It contains the experimental, local-camera [Gen0 Camera for Android](gen0-android/README.md) and [a proposed template](_template/README.md). The Gen0 project is not a stable protocol, physical-device, or upstream-activation claim.

Activate a new upstream project through maintainer-confirmed scope via an Issue or maintainer-approved public-safe task brief, then follow [project requirements](../docs/PROJECTS.md). Personal local experiments need no upstream approval but are not accepted project activation. Every project preserves root contracts, maps all six layers/four planes, documents existing entrypoints and actual verification, and records license/data provenance. Do not copy root protocol schemas into a project. A task brief cannot override accepted architecture or self-authorize reserved changes.

Run `make project-check` for all projects, `./tools/project-check projects/gen0-android` for the experimental Android project, or `./tools/project-check projects/_template` for the template. Publication review is required before the first public push.
