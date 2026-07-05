# Changelog

## 0.2.5

Framework v0.2.5 is a usage-kit sample layout and compatibility patch for the v0.2 suite-mode interface.

- Adds runtime-mode sample Provider Instances for mock/stub/ephemeral-style examples without changing the public Provider Contract model.
- Restructures checked-in samples into canonical usage-kit groups: `00-getting-started`, `10-contract-baseline`, `20-provider-capability-p0`, `30-cross-provider-groups`, `40-evidence-reporting`, and `90-compatibility`.
- Keeps one-release generated legacy sample aliases inside the usage-kit zip so existing release-asset paths continue to resolve while users migrate to canonical paths.
- Adds release verification for canonical and legacy sample paths, including the Provider Capability suite group, evidence hardening sample, and compatibility-only dummy REST suite.
- Documents the sample layout, migration mapping, compatibility strategy, and release verification expectations.

Sample layout migration:

| Legacy usage-kit path | Canonical usage-kit path |
|---|---|
| `samples/golden_e2e/` | `samples/00-getting-started/golden_e2e/` |
| `samples/contract_baseline/` | `samples/10-contract-baseline/mixed_wiremock_jdbc_nats/` |
| `samples/provider_capability/` | `samples/20-provider-capability-p0/` |
| `samples/provider_capability/mock_server_cross_verify/` | `samples/30-cross-provider-groups/mock_server_cross_verify/` |
| `samples/provider_capability/dummy_rest/` | `samples/90-compatibility/dummy_rest/` |
| `samples/evidence_hardening/` | `samples/40-evidence-reporting/evidence_hardening/` |

Known boundaries:

- DSL and contract artifacts remain at public contract version `v0.2`.
- This release does not add new provider runtimes; it changes sample packaging, documentation, and release verification.
- Legacy sample aliases are generated only in the v0.2.5 usage-kit release artifact and remain deprecated; new documentation points to canonical sample paths.
- Kafka and IBM MQ native runtime execution still requires externally provisioned endpoints and release secrets; the framework consumes bindings and does not start brokers or queue managers.

## 0.2.4

Framework v0.2.4 is a provider runtime and release-readiness patch for the v0.2 suite-mode interface.

- Keeps the public runtime interface limited to canonical suite-mode commands: `validate`, `run`, `run --dry-run`, `report`, and `validate-evidence`.
- Standardizes public provider support claims on `support_status` and removes runtime lifecycle terms from the support-status model.
- Adds native external-client runtime baselines for Kafka and IBM MQ while keeping broker and queue-manager provisioning outside the framework.
- Adds external Env_Profile samples for Kafka and IBM MQ, plus release sample verification for supported provider claims.
- Hardens report/evidence release coverage, including `report --format text`, `report --format yaml`, and `validate-evidence`.
- Supports project-provisioned WireMock `base_url` consumption without starting framework-managed WireMock for that profile.

Known boundaries:

- DSL and contract artifacts remain at public contract version `v0.2`.
- Kafka and IBM MQ native runtime execution requires externally provisioned endpoints and release secrets; the framework consumes bindings and does not start brokers or queue managers.
- Certificate chain trust and build provenance are not proven by this release unless implemented by a later release pipeline.

## 0.2.3

Framework v0.2.3 is a framework fix patch for executable provider suites.

- Supports external NATS runtime connections through `env://NATS_CONNECTION` without leaking raw connection values into stdout, result JSON, or evidence.
- Makes packaged provider contract resolution independent of the current working directory by bundling a generated provider contract index.
- Prints owner-actionable failure codes in CLI output when provider execution fails.
- Applies materialized evidence classification policy consistently across result JSON and provider evidence.
- Fixes the full contract-baseline mixed provider runtime path for WireMock HTTP mock, JDBC, and NATS.
- Hardens external NATS protocol handling so TCP endpoints that do not speak NATS fail with `NATS_CONNECTION_FAILED`, and empty observations are not cached as matched events.
- Allows the release workflow to use restored Dependency-Check data from fallback cache keys instead of requiring an exact daily cache key hit.

Known boundaries:

- DSL and contract artifacts remain at public contract version `v0.2`.
- This release does not add new provider families; it fixes runtime execution, evidence classification, and packaged contract lookup for existing v0.2 provider suites.

## 0.2.2

Framework v0.2.2 is a suite-mode hardening patch for v0.2 execution.

- Routes direct provider suites and parent suite child execution through the shared suite-mode dispatcher.
- Adds a runnable dummy REST suite, executable artifact-compare sample, YAML report output, and clearer CLI help.
- Deprecates RP-mode execution and makes profile validation consistent across `validate`, `run --dry-run`, and `run`.
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
- Documents provider runtime support by mode so owners can distinguish framework capability from local/CI mock evidence and `contract_only` future modes.

Known boundaries:

- DSL and contract artifacts remain at public contract version `v0.2`.
- Kafka and IBM MQ native/ephemeral modes remain `contract_only` in this release.
- Mock provider evidence is valid for framework verification and local/CI dependency substitution, not downstream SIT/preprod release evidence.
