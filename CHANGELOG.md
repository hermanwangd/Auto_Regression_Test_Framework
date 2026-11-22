# Changelog

## 0.3.2

Framework v0.3.2 completes the v0.3.1 external-profile resolution correction with native acceptance coverage and JDBC runtime hardening.

- Adds checked-in v0.3 external REST and gRPC client samples plus release-gate validation of their explicit native profiles.
- Resolves vendor JDBC drivers from `REGRESS_DRIVER_PATH` and reports unavailable drivers as owner-actionable configuration failures.
- Classifies JDBC connection failures as `DB_CONNECTION_FAILED` without emitting undeclared Provider Contract outputs.
- Requires evidence only for JDBC operations that actually executed, preserving validation and configuration failures as valid, mask-safe results.

Known boundaries:

- Public CI validates external profile selection structurally; external REST, gRPC, JDBC, Kafka, IBM MQ, and NATS execution still requires caller-provisioned endpoints, credentials, and JDBC drivers where applicable.
- This patch does not change the public DSL v0.3, Provider Contract, Env_Profile, result, or evidence schemas.

## 0.3.1

Framework v0.3.1 is v0.3.0 plus the explicit Env_Profile resolution fix.

- Makes an explicit `run --profile` authoritative for canonical v0.3 plans, resolved targets, provider steps, result JSON, and evidence.
- Blocks missing selected-profile bindings and environment values before provider execution instead of falling back to `default_profile`.
- Adds release-gate dry-runs for external NATS, Kafka, and IBM MQ profiles, rejecting any local/mock fallback without requiring external infrastructure.
- Uses MQCSP authentication when the IBM MQ native client is given a password and records safe MQ completion/reason codes in connection failures.

Known boundaries:

- External JDBC, Kafka, IBM MQ, and NATS execution still requires owner-provisioned endpoints, credentials, and drivers where applicable.
- This patch does not change the public DSL v0.3, Provider Contract, Env_Profile, result, or evidence schemas.

## 0.3.0

Framework v0.3.0 establishes the v0.3 contract-first runtime surface.

- Adds typed Provider Contract inputs and outputs, output sensitivity controls, and fail-closed contract enforcement.
- Compiles each suite into one canonical v0.3 execution plan for validation, dry-run, and runtime execution.
- Validates cross-step generated bindings, declared output availability, and illegal scalar output subpaths before provider invocation.
- Redacts sensitive and undeclared runtime outputs from result documents and evidence.
- Adds deterministic v0.3 reference resolution for `artifact://`, `step://`, `generated://`, and `env://` references.
- Adds a runnable mixed-provider E2E sample that proves JDBC query output flows to a REST request, then through NATS evidence verification.
- Hardens the sample fake-provider cleanup handoff, NATS test-start observation boundary, usage-kit packaging, and release sample gates.

Known boundaries:

- Provider Contracts define the public v0.3 runtime surface; provider-specific runtimes retain their documented support boundaries.
- External JDBC, Kafka, IBM MQ, and NATS execution still requires owner-provisioned endpoints, credentials, and drivers where applicable.
- Product, Release Package, and Release Unit topology remain outside the framework runtime.

## 0.2.7

Framework v0.2.7 is an enterprise adoption hardening release for the v0.2 suite-mode interface.

- Adds `report --format json` as a public report output for standard result JSON, with result/evidence validation before success.
- Proves project-provisioned WireMock external `base_url` consumption in the release provider sample guard.
- Adds JDBC Oracle/DB2 driver discovery diagnostics through `--driver-path`, `--driver-dir`, `REGRESS_DRIVER_PATH`, `usage-kit/drivers/`, and `doctor drivers`.
- Adds usage-kit JDBC driver placeholders without bundling vendor JDBC driver binaries.
- Adds usage-kit `QUICKSTART`, `TROUBLESHOOTING`, `DRIVER_SETUP`, `EXTERNAL_RUNTIME_SETUP`, and explicit deprecated legacy sample path warnings.
- Adds schema drift validation between canonical docs contracts and packaged `schemas/`.
- Adds root onboarding docs, security/contribution/release/support guides, and provider dependency policy.

Known boundaries:

- DSL and contract artifacts remain at public contract version `v0.2`.
- Product/RP orchestration remains outside the framework runtime public interface.
- Native external JDBC, Kafka, and IBM MQ execution still requires owner-provisioned endpoints, credentials, and driver/runtime dependencies where applicable.

## 0.2.6

Framework v0.2.6 simplifies the public environment configuration model for the v0.2 suite-mode interface.

- Makes Env_Profile the canonical public runtime environment artifact for new samples; legacy split `execution_profiles/` and `environment_bindings/` remain compatibility inputs only.
- Migrates checked-in samples and usage-kit samples to Suite Manifest + Test Cases + Provider Instances + Env_Profiles.
- Adds Env_Profile `bindings` support while preserving deprecated `binding_keys` compatibility where older artifacts still need it.
- Tightens selected-profile validation across `validate`, `run --dry-run`, and `run`, including deprecated `targets.*.profile` conflict detection.
- Keeps external JDBC, Kafka, and IBM MQ native runtime evidence optional for public CI while validating their external Env_Profile contracts.
- Hardens release verification for usage-kit sample shape, supported provider samples, suite group summaries, CLI help, and critical delivery coverage.

Sample layout migration:

| Generated legacy path | Canonical path |
|---|---|
| `samples/golden_e2e/` | `samples/00-getting-started/golden_e2e/` |
| `samples/contract_baseline/` | `samples/10-contract-baseline/mixed_wiremock_jdbc_nats/` |
| `samples/provider_capability/` | `samples/20-provider-capability-p0/` |
| `samples/provider_capability/mock_server_cross_verify/` | `samples/30-cross-provider-groups/mock_server_cross_verify/` |
| `samples/provider_capability/dummy_rest/` | `samples/90-compatibility/dummy_rest/` |
| `samples/evidence_hardening/` | `samples/40-evidence-reporting/evidence_hardening/` |

Known boundaries:

- DSL and contract artifacts remain at public contract version `v0.2`.
- Product/RP orchestration remains outside the framework runtime public interface; owners or Agent Skills must generate suite-mode artifacts before invoking the framework.
- Kafka and IBM MQ native runtime execution still requires externally provisioned endpoints and release secrets; the framework consumes bindings and does not start brokers or queue managers.
- Legacy sample aliases are generated only in the usage-kit release artifact and remain deprecated; new documentation points to canonical sample paths.

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
