# Changelog

## 0.2.3

Framework v0.2.3 is a PI-run framework fix patch for executable provider suites.

- Supports external NATS runtime connections through `env://NATS_CONNECTION` without leaking raw connection values into stdout, result JSON, or evidence.
- Makes packaged provider contract resolution independent of the current working directory by bundling a generated provider contract index.
- Prints owner-actionable failure codes in CLI output when provider execution fails.
- Applies materialized evidence classification policy consistently across result JSON and provider evidence.
- Fixes the full contract-baseline mixed provider runtime path for WireMock HTTP mock, JDBC, and NATS.
- Hardens external NATS protocol handling so TCP endpoints that do not speak NATS fail with `NATS_CONNECTION_FAILED`, and empty observations are not cached as matched events.

Known boundaries:

- DSL and contract artifacts remain at public contract version `v0.2`.
- This release does not add new provider families; it fixes runtime execution, evidence classification, and packaged contract lookup for existing v0.2 provider suites.

## 0.2.2

Framework v0.2.2 is a PI-run hardening patch for v0.2 suite-mode execution.

- Routes direct provider suites and parent suite child execution through the shared suite-mode dispatcher.
- Adds a runnable dummy REST suite, executable artifact-compare sample, `pi-run` alias, YAML report output, and clearer CLI help.
- Deprecates RP-mode execution for PI-run validation and makes profile validation consistent across `validate`, `run --dry-run`, and `run`.
- Standardizes suite summary status taxonomy and provider capability output paths for report consumption.
- Expands dependency security triage to cover MEDIUM/HIGH/CRITICAL findings with documented suppressions.

Known boundaries:

- DSL and contract artifacts remain at public contract version `v0.2`.
- This release does not add new provider runtime families; it hardens suite-mode execution and evidence/report behavior.

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
