# Changelog

## 0.2.1

Framework v0.2.1 is a release packaging hardening patch for v0.2.

- Adds a curated usage kit release artifact containing user guide, Provider Contract catalog, schemas, runnable samples, manifest, and verification commands.
- Adds release workflow validation for the usage kit before checksum, signing, and GitHub Release publication.
- Aligns sample result `framework_version` values with the immutable framework artifact version `0.2.1`.

Known boundaries:

- DSL and contract artifacts remain at public contract version `v0.2`.
- The usage kit is documentation and sample packaging only; it does not add new provider runtime capability.

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
