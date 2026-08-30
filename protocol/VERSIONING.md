# Protocol versioning

Protocol versions use semantic versioning once `v0.1.0-alpha.0` is released.

- Patch: clarification or validation fix that does not change accepted meaning.
- Minor: additive fields or capabilities that old consumers can safely ignore.
- Major: removed fields, changed meaning, incompatible validation, or changed safety semantics.

During pre-alpha, breaking changes are allowed but must still be identified, documented, and paired with fixture updates. Every release records supported adapter versions and known incompatibilities.
