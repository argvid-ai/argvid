# Publication policy

Everything sent to a public branch, fork, draft PR, issue, release, artifact, or CI log is publication. Review before the first push, not just before merge; repeat review when scope or content changes.

## Before publication

1. State the [inclusion basis and placement outcome](REPOSITORY_SCOPE.md).
2. Confirm the contributor has rights and authority to publish each source, document, dependency, model weight, dataset, media asset, and hardware design.
3. Use synthetic fixtures by default. Record provenance and license for any approved public input; remove credentials and unnecessary personal information.
4. Inspect the exact staged files, generated output, sample logs, commit content, and prospective CI output. Check for local paths, secrets, unpublished context, and accidental bundled data.
5. Confirm the project can be understood and checked using public materials only; document all required dependencies and steps.
6. Run offline gates and the applicable conformance/host/simulation checks. Record unrun device/HIL checks as pending.
7. Obtain human review for publication/IP, safety, dependency licensing, and compatibility claims as applicable.

Authentication, Write access, an approved coding issue, and automated checks do not independently grant publication/IP permission. An Agent cannot grant that permission. Unknown rights or missing review means `EXCLUDE_FROM_PUBLIC_REPOSITORY`; stop and resolve the issue without posting excluded details publicly.

## Data and licenses

Apache-2.0 is the default source/documentation license, not a blanket license for imported dependencies, weights, datasets, recordings, or design sources. Record source, immutable version, license, permitted use/redistribution, notices, and reviewer evidence in the project's THIRD_PARTY record. Hardware design sources need explicit reviewed licensing before publication.

Avoid committing data when a synthetic fixture proves the behavior. Redaction alone does not establish permission. Logs and screenshots need the same review as source files.

## Limits of automation

Context/project checks verify structure, local paths, canonical mappings, and documented commands. They do not perform a comprehensive secret scan, interpret licenses, certify safety, or approve publication. CODEOWNERS suggests review routing; repository protection settings and actual review determine enforcement. Security reports follow [SECURITY.md](../SECURITY.md), not a public issue.
