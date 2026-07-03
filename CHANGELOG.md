# Changelog

## 0.2.0

Framework v0.2.0 is the first immutable pre-release artifact for the product-agnostic Auto Regression Test Framework.

- Aligns Maven artifact identity, standard result `framework_version`, and release documentation on `0.2.0`.
- Adds CI gates for Maven verification, critical coverage, contract/evidence consistency, secret scanning, SBOM generation, and dependency vulnerability scanning.
- Adds release automation for tag validation, jar packaging, SBOMs, checksums, keyless signatures, release notes, and GitHub Release artifact publication.
- Documents provider runtime support by mode so owners can distinguish production-ready framework capability from local/CI mock evidence and contract-only future modes.

Known boundaries:

- DSL and contract artifacts remain at public contract version `v0.2`.
- Kafka and IBM MQ native/ephemeral modes remain contract-only in this release.
- Mock provider evidence is valid for framework verification and local/CI dependency substitution, not downstream SIT/preprod release evidence.
