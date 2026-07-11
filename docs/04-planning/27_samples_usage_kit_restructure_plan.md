# Samples and Usage Kit v0.3 Restructure Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` or `executing-plans` to implement this plan task-by-task. Keep Maven memory bounded with `MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m'`. This is a sample-layout, usage-kit, docs, and test-path migration. Do not change provider runtime behavior unless a path migration reveals a real broken contract.

## 1. Goal

Make `samples/` the stable public sample surface for Framework v0.3 without forcing users to choose between a v0.2 tree and a separate `v0_3_dsl` tree.

The v0.3 release inherits the existing v0.2 sample directory skeleton, upgrades canonical sample content to v0.3 DSL, and keeps old v0.2 artifacts only as source-tree backup/deprecated material under `samples/90-compatibility/legacy-v0.2/`. The v0.3 usage kit must expose v0.3 canonical samples as the default surface.

## 2. Normative Decisions

- Keep top-level sample categories stable: `00-getting-started`, `10-contract-baseline`, `20-provider-capability-p0`, `30-cross-provider-groups`, `40-evidence-reporting`, `80-negative`, and `90-compatibility`.
- Remove `samples/v0_3_dsl` from the public path surface. It is a staging path only and must not ship as a primary usage-kit entrypoint.
- Canonical v0.3 leaf suites use inherited paths, `suite_manifest.yaml`, `manifest_version: v0.3`, and v0.3 test cases.
- v0.2 executable artifacts stay in the source tree under `samples/90-compatibility/legacy-v0.2/` with `DEPRECATED.md`. They do not ship in the v0.3 usage kit.
- No root-level legacy aliases such as `samples/golden_e2e`, `samples/provider_capability`, `samples/contract_baseline`, or `samples/evidence_hardening` ship in the v0.3 usage kit.
- `wiremock_http_mock` is v0.2 compatibility material. The v0.3 HTTP mock/client sample is `http_mock + rest_client`, published at the inherited path `samples/20-provider-capability-p0/http/rest_client_with_wiremock/`.
- Only inherited path names may retain legacy implementation terms such as `wiremock`. Active v0.3 suite ids, READMEs, support-matrix labels, manifest ids, docs, and evidence labels must use `http_mock + rest_client` terminology.
- Sample path labels are stable public labels, not exact Provider Contract names. Do not rename inherited paths just because Provider Contract terminology changes.
- `samples/10-contract-baseline/mixed_wiremock_jdbc_nats/` keeps its historical path for path stability. Its README and suite metadata must explain the v0.3 semantics.
- `samples/v0_3_dsl/multi_test/` becomes `samples/20-provider-capability-p0/verification/multi_test_shared_env/` as a positive shared-Env_Profile verification sample. It is not a cross-provider sample.
- Do not modify `*.v0.2.yaml` contract artifacts to describe v0.3 sample paths. Put v0.3 sample path contracts in the usage-kit manifest, current operations docs, or a v0.3 registry if one is created.

## 3. Target Structure

```text
samples/
  README.md

  00-getting-started/
    golden_e2e/
      suite_manifest.yaml                 # v0.3 canonical
      test_cases/
      env_profiles/

  10-contract-baseline/
    mixed_wiremock_jdbc_nats/
      suite_manifest.yaml                 # v0.3 canonical mixed baseline
      test_cases/
      env_profiles/
      README.md

  20-provider-capability-p0/
    suite_manifest.yaml                   # v0.3 suite group
    http/
      rest_client_with_wiremock/          # v0.3 http_mock + rest_client canonical
    data/
      jdbc/
    messaging/
      nats/
      kafka/
      ibm_mq/
      kafka_ibm_mq_mixed/
    rpc/
      soap_mock/
      grpc_mock/
    verification/
      common_verify/
      artifact_compare/
      polling_observer/
      multi_test_shared_env/

  30-cross-provider-groups/
    mock_server_cross_verify/
      suite_manifest.yaml                 # v0.3 suite group
      rest_wiremock_http/
      soap_mock_http_client/
      grpc_mock_grpc_client/

  40-evidence-reporting/
    evidence_hardening/
      valid_result.json
      evidence/

  80-negative/
    suite_manifest.yaml
    bindings/
    legacy-fields/
    operations/
    refs/
    runtime/
    secrets/
    target-resolution/

  90-compatibility/
    README.md
    DEPRECATED_PATHS.md
    dummy_rest/
      DEPRECATED.md
    legacy-v0.2/
      DEPRECATED.md
      00-getting-started/
      10-contract-baseline/
      20-provider-capability-p0/
      30-cross-provider-groups/
      40-evidence-reporting/
```

## 4. Canonical Sample Registry

Every row below is a public sample-path contract for v0.3. Implementation must use exact destinations from this registry.

Field rules:

- `root_dir`: required for all entries and always a directory.
- `entrypoint_manifest`: required for `leaf` and `suite_group`; `null` for `evidence_fixture` and `documentation_group`.
- `default_profile`: required for `leaf`; `null` for `suite_group`, `evidence_fixture`, and `documentation_group`.
- `suite_id`: required for `leaf` and `suite_group`; `null` for `evidence_fixture` and `documentation_group`.
- `ships_in_usage_kit`: required boolean for every entry.
- `public_contract`: `true` means downstream users may depend on the path.

| Sample ID | root_dir | entrypoint_manifest | kind | default_profile | suite_id | ships_in_usage_kit | public_contract | Notes |
|---|---|---|---|---|---|---|---|---|
| `golden_e2e` | `samples/00-getting-started/golden_e2e` | `suite_manifest.yaml` | `leaf` | `local_v03` | `GOLDEN-E2E-v0.3` | true | true | First runnable v0.3 sample. |
| `mixed_wiremock_jdbc_nats` | `samples/10-contract-baseline/mixed_wiremock_jdbc_nats` | `suite_manifest.yaml` | `leaf` | `local_v03` | `MIXED-CONTRACT-BASELINE-v0.3` | true | true | Historical path kept; v0.3 semantics documented in README/metadata. |
| `provider_capability_p0` | `samples/20-provider-capability-p0` | `suite_manifest.yaml` | `suite_group` | `null` | `PROVIDER-CAPABILITY-P0-v0.3` | true | true | Aggregates v0.3 supported provider capability samples. |
| `rest_client_with_wiremock` | `samples/20-provider-capability-p0/http/rest_client_with_wiremock` | `suite_manifest.yaml` | `leaf` | `local_v03` | `HTTP-MOCK-REST-CLIENT-v0.3` | true | true | v0.3 `http_mock + rest_client`; WireMock is implementation detail. |
| `jdbc` | `samples/20-provider-capability-p0/data/jdbc` | `suite_manifest.yaml` | `leaf` | `local_v03` | `JDBC-v0.3` | true | true | Local H2 and external JDBC profile validation. |
| `nats` | `samples/20-provider-capability-p0/messaging/nats` | `suite_manifest.yaml` | `leaf` | `local_v03` | `NATS-v0.3` | true | true | Local/client capability plus external profile validation. |
| `kafka` | `samples/20-provider-capability-p0/messaging/kafka` | `suite_manifest.yaml` | `leaf` | `local_v03` | `KAFKA-v0.3` | true | true | Local/client capability plus external profile validation. |
| `ibm_mq` | `samples/20-provider-capability-p0/messaging/ibm_mq` | `suite_manifest.yaml` | `leaf` | `local_v03` | `IBM-MQ-v0.3` | true | true | Local/client capability plus external profile validation. |
| `kafka_ibm_mq_mixed` | `samples/20-provider-capability-p0/messaging/kafka_ibm_mq_mixed` | `suite_manifest.yaml` | `leaf` | `local_v03` | `KAFKA-IBM-MQ-MIXED-v0.3` | true | true | Positive mixed messaging sample. |
| `soap_mock` | `samples/20-provider-capability-p0/rpc/soap_mock` | `suite_manifest.yaml` | `leaf` | `local_v03` | `SOAP-MOCK-REST-CLIENT-v0.3` | true | true | v0.3 SOAP mock plus REST client stimulus. |
| `grpc_mock` | `samples/20-provider-capability-p0/rpc/grpc_mock` | `suite_manifest.yaml` | `leaf` | `local_v03` | `GRPC-MOCK-GRPC-CLIENT-v0.3` | true | true | v0.3 gRPC mock plus gRPC client stimulus. |
| `common_verify` | `samples/20-provider-capability-p0/verification/common_verify` | `suite_manifest.yaml` | `leaf` | `local_v03` | `COMMON-VERIFY-v0.3` | true | true | JSON/schema/common verifier sample. |
| `artifact_compare` | `samples/20-provider-capability-p0/verification/artifact_compare` | `suite_manifest.yaml` | `leaf` | `local_v03` | `ARTIFACT-COMPARE-v0.3` | true | true | File/artifact comparison sample. |
| `polling_observer` | `samples/20-provider-capability-p0/verification/polling_observer` | `suite_manifest.yaml` | `leaf` | `local_v03` | `POLLING-OBSERVER-v0.3` | true | true | Polling observation sample. |
| `multi_test_shared_env` | `samples/20-provider-capability-p0/verification/multi_test_shared_env` | `suite_manifest.yaml` | `leaf` | `local_v03` | `MULTI-TEST-v0.3` | true | true | Positive multi-test sample sharing one Env_Profile. |
| `mock_server_cross_verify` | `samples/30-cross-provider-groups/mock_server_cross_verify` | `suite_manifest.yaml` | `suite_group` | `null` | `MOCK-SERVER-CROSS-VERIFY-v0.3` | true | true | Cross-provider mock/client group. |
| `mock_server_cross_verify_rest` | `samples/30-cross-provider-groups/mock_server_cross_verify/rest_wiremock_http` | `suite_manifest.yaml` | `group_child` | `local_v03` | `MOCK-SERVER-REST-v0.3` | true | false | Child path is stable inside the group; docs should point to the group root. |
| `mock_server_cross_verify_soap` | `samples/30-cross-provider-groups/mock_server_cross_verify/soap_mock_http_client` | `suite_manifest.yaml` | `group_child` | `local_v03` | `MOCK-SERVER-SOAP-v0.3` | true | false | Child path is stable inside the group; docs should point to the group root. |
| `mock_server_cross_verify_grpc` | `samples/30-cross-provider-groups/mock_server_cross_verify/grpc_mock_grpc_client` | `suite_manifest.yaml` | `group_child` | `local_v03` | `MOCK-SERVER-GRPC-v0.3` | true | false | Child path is stable inside the group; docs should point to the group root. |
| `evidence_hardening` | `samples/40-evidence-reporting/evidence_hardening` | `null` | `evidence_fixture` | `null` | `null` | true | true | Result/evidence validation fixture rooted at `valid_result.json`. |
| `negative` | `samples/80-negative` | `suite_manifest.yaml` | `suite_group` | `null` | `NEGATIVE-v0.3` | true | true | Validation/runtime failure fixtures. |
| `compatibility` | `samples/90-compatibility` | `null` | `documentation_group` | `null` | `null` | true | true | Deprecated compatibility notes only; not a supported capability gate. |

Expected-failure manifests under `mock_server_cross_verify` may remain inside the group only when the parent group manifest declares explicit `expected_status`. Standalone negative/failure samples belong under `samples/80-negative/`.

## 5. Exact Path Rewrite Contract

The v0.3 usage-kit manifest must include a `path_rewrites[]` list matching this table. These rewrites are documentation and machine-readable migration hints; the old paths do not ship in the v0.3 usage kit.

| old_path | new_path | scope |
|---|---|---|
| `samples/v0_3_dsl/golden` | `samples/00-getting-started/golden_e2e` | directory |
| `samples/v0_3_dsl/http_mock_rest_client` | `samples/20-provider-capability-p0/http/rest_client_with_wiremock` | directory |
| `samples/v0_3_dsl/data/jdbc` | `samples/20-provider-capability-p0/data/jdbc` | directory |
| `samples/v0_3_dsl/messaging/nats` | `samples/20-provider-capability-p0/messaging/nats` | directory |
| `samples/v0_3_dsl/messaging/kafka` | `samples/20-provider-capability-p0/messaging/kafka` | directory |
| `samples/v0_3_dsl/messaging/ibm_mq` | `samples/20-provider-capability-p0/messaging/ibm_mq` | directory |
| `samples/v0_3_dsl/messaging/kafka_ibm_mq_mixed` | `samples/20-provider-capability-p0/messaging/kafka_ibm_mq_mixed` | directory |
| `samples/v0_3_dsl/rpc/soap_mock_rest_client` | `samples/20-provider-capability-p0/rpc/soap_mock` | directory |
| `samples/v0_3_dsl/rpc/grpc_mock_grpc_client` | `samples/20-provider-capability-p0/rpc/grpc_mock` | directory |
| `samples/v0_3_dsl/verification/common_verify` | `samples/20-provider-capability-p0/verification/common_verify` | directory |
| `samples/v0_3_dsl/verification/artifact_compare` | `samples/20-provider-capability-p0/verification/artifact_compare` | directory |
| `samples/v0_3_dsl/verification/polling_observer` | `samples/20-provider-capability-p0/verification/polling_observer` | directory |
| `samples/v0_3_dsl/multi_test` | `samples/20-provider-capability-p0/verification/multi_test_shared_env` | directory |
| `samples/v0_3_dsl/negative/bindings/invalid_runtime_mode` | `samples/80-negative/bindings/invalid_runtime_mode` | directory |
| `samples/v0_3_dsl/negative/bindings/missing_required_binding` | `samples/80-negative/bindings/missing_required_binding` | directory |
| `samples/v0_3_dsl/negative/bindings/unknown_binding_key` | `samples/80-negative/bindings/unknown_binding_key` | directory |
| `samples/v0_3_dsl/negative/legacy_fields/data_binding` | `samples/80-negative/legacy-fields/data_binding` | directory |
| `samples/v0_3_dsl/negative/operations/unsupported_input` | `samples/80-negative/operations/unsupported_input` | directory |
| `samples/v0_3_dsl/negative/operations/unsupported_operation` | `samples/80-negative/operations/unsupported_operation` | directory |
| `samples/v0_3_dsl/negative/refs/forward_step_ref` | `samples/80-negative/refs/forward_step_ref` | directory |
| `samples/v0_3_dsl/negative/refs/invalid_artifact_ref` | `samples/80-negative/refs/invalid_artifact_ref` | directory |
| `samples/v0_3_dsl/negative/refs/symlink_escape` | `samples/80-negative/refs/symlink_escape` | directory |
| `samples/v0_3_dsl/negative/runtime/cleanup_failure_preservation` | `samples/80-negative/runtime/cleanup_failure_preservation` | directory |
| `samples/v0_3_dsl/negative/secrets/raw_secret_dsl` | `samples/80-negative/secrets/raw_secret_dsl` | directory |
| `samples/v0_3_dsl/negative/secrets/raw_secret_env` | `samples/80-negative/secrets/raw_secret_env` | directory |
| `samples/v0_3_dsl/negative/target_resolution/missing_env_profile_target` | `samples/80-negative/target-resolution/missing_env_profile_target` | directory |
| `samples/v0_3_dsl/negative/target_resolution/missing_provider_contract` | `samples/80-negative/target-resolution/missing_provider_contract` | directory |
| `samples/v0_3_dsl/negative/target_resolution/unknown_target` | `samples/80-negative/target-resolution/unknown_target` | directory |
| `samples/golden_e2e` | `samples/00-getting-started/golden_e2e` | directory |
| `samples/contract_baseline` | `samples/10-contract-baseline/mixed_wiremock_jdbc_nats` | directory |
| `samples/provider_capability` | `samples/20-provider-capability-p0` | directory |
| `samples/evidence_hardening` | `samples/40-evidence-reporting/evidence_hardening` | directory |

## 6. v0.2 Backup Inventory

All v0.2 executable artifacts are source-tree only and must be archived under one clearly deprecated root. Once a v0.3 canonical leaf is promoted, all remaining v0.2 executable artifacts for that leaf must live only under this backup root or under `90-compatibility/dummy_rest`.

| Existing v0.2 Path | Source-tree Backup Path |
|---|---|
| `samples/00-getting-started/golden_e2e/` | `samples/90-compatibility/legacy-v0.2/00-getting-started/golden_e2e/` |
| `samples/10-contract-baseline/mixed_wiremock_jdbc_nats/` | `samples/90-compatibility/legacy-v0.2/10-contract-baseline/mixed_wiremock_jdbc_nats/` |
| `samples/20-provider-capability-p0/http/wiremock_http_mock/` | `samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/http/wiremock_http_mock/` |
| `samples/20-provider-capability-p0/http/rest_client_with_wiremock/` | `samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/http/rest_client_with_wiremock/` |
| `samples/20-provider-capability-p0/data/jdbc/` | `samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/` |
| `samples/20-provider-capability-p0/messaging/nats/` | `samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/nats/` |
| `samples/20-provider-capability-p0/messaging/kafka/` | `samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/kafka/` |
| `samples/20-provider-capability-p0/messaging/ibm_mq/` | `samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/ibm_mq/` |
| `samples/20-provider-capability-p0/messaging/kafka_ibm_mq_mixed/` | `samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/kafka_ibm_mq_mixed/` |
| `samples/20-provider-capability-p0/rpc/soap_mock/` | `samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/rpc/soap_mock/` |
| `samples/20-provider-capability-p0/rpc/grpc_mock/` | `samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/rpc/grpc_mock/` |
| `samples/20-provider-capability-p0/verification/common_verify/` | `samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/verification/common_verify/` |
| `samples/20-provider-capability-p0/verification/artifact_compare/` | `samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/verification/artifact_compare/` |
| `samples/20-provider-capability-p0/verification/polling_observer/` | `samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/verification/polling_observer/` |
| `samples/30-cross-provider-groups/mock_server_cross_verify/` | `samples/90-compatibility/legacy-v0.2/30-cross-provider-groups/mock_server_cross_verify/` |
| `samples/40-evidence-reporting/evidence_hardening/` | `samples/90-compatibility/legacy-v0.2/40-evidence-reporting/evidence_hardening/` |
| `samples/90-compatibility/dummy_rest/` | `samples/90-compatibility/dummy_rest/` |

## 7. Public Path Rules

- New docs must never point users to `samples/v0_3_dsl`.
- `samples/*/suite_manifest.yaml` at canonical paths must be v0.3 unless the directory is explicitly deprecated.
- `samples/90-compatibility/legacy-v0.2/` paths are backup artifacts, not quickstart paths.
- Deprecated directories must contain `DEPRECATED.md` with replacement guidance.
- Generated indexes, README navigation, usage-kit manifests, and support matrices must ignore `samples/90-compatibility/legacy-v0.2/**` as active samples.
- Canonical suite groups under `00/10/20/30/40/80` must not reference `samples/90-compatibility/legacy-v0.2/` or `samples/90-compatibility/dummy_rest/`.
- Only suites rooted under `90-compatibility/` may reference deprecated or compatibility material.
- Suite groups must keep child refs inside their own directory tree.
- v0.3 test cases must not contain `provider_id`, `provider_instance`, `parameters`, `bind_as`, `data_binding`, `datasets`, `fixtures`, or `expected_results`.
- v0.3 suite targets must resolve Provider Contracts by stable contract id, not by folder name.
- Do not mutate versioned `v0.2` contract files to advertise v0.3 sample paths.

## 8. Usage Kit Manifest Contract

The v0.3 usage kit exposes only inherited canonical v0.3 sample roots and compatibility notices. It does not ship `samples/90-compatibility/legacy-v0.2/`.

```text
usage-kit/
  samples/
    00-getting-started/
    10-contract-baseline/
    20-provider-capability-p0/
    30-cross-provider-groups/
    40-evidence-reporting/
    80-negative/
    90-compatibility/
      README.md
      DEPRECATED_PATHS.md
      dummy_rest/
```

The manifest shape must be schema-backed and machine-readable:

```yaml
sample_layout_version: v3
sample_public_interface: v0.3
default_quickstart_suite: samples/00-getting-started/golden_e2e/suite_manifest.yaml
default_provider_capability_suite: samples/20-provider-capability-p0/suite_manifest.yaml
default_sample_roots:
  - samples/00-getting-started
  - samples/10-contract-baseline
  - samples/20-provider-capability-p0
  - samples/30-cross-provider-groups
  - samples/40-evidence-reporting
  - samples/80-negative
  - samples/90-compatibility
canonical_samples:
  - id: golden_e2e
    root_dir: samples/00-getting-started/golden_e2e
    entrypoint_manifest: suite_manifest.yaml
    kind: leaf
    default_profile: local_v03
    suite_id: GOLDEN-E2E-v0.3
    ships_in_usage_kit: true
    public_contract: true
  - id: provider_capability_p0
    root_dir: samples/20-provider-capability-p0
    entrypoint_manifest: suite_manifest.yaml
    kind: suite_group
    default_profile: null
    suite_id: PROVIDER-CAPABILITY-P0-v0.3
    ships_in_usage_kit: true
    public_contract: true
  - id: evidence_hardening
    root_dir: samples/40-evidence-reporting/evidence_hardening
    entrypoint_manifest: null
    result_fixture: valid_result.json
    kind: evidence_fixture
    default_profile: null
    suite_id: null
    ships_in_usage_kit: true
    public_contract: true
compatibility_aliases: []
path_rewrites:
  - old_path: samples/v0_3_dsl/golden
    new_path: samples/00-getting-started/golden_e2e
    scope: directory
deprecated_paths:
  - old_path: samples/v0_3_dsl
    applies_to_descendants: true
    status: removed_from_usage_kit
    shipped_in_usage_kit: false
    warning_artifact: samples/90-compatibility/DEPRECATED_PATHS.md
  - old_path: samples/provider_capability
    applies_to_descendants: true
    status: removed_from_usage_kit
    shipped_in_usage_kit: false
    warning_artifact: samples/90-compatibility/DEPRECATED_PATHS.md
legacy_v0_2_backups:
  shipped_in_usage_kit: false
  source_tree_only: true
  source_tree_location: samples/90-compatibility/legacy-v0.2
```

`canonical_samples[]` must contain one entry for every registry row with `ships_in_usage_kit: true`. `path_rewrites[]` must contain every row from the Exact Path Rewrite Contract. The release verification must fail if the usage-kit zip contains `samples/v0_3_dsl`, `samples/golden_e2e`, `samples/provider_capability`, `samples/contract_baseline`, `samples/evidence_hardening`, or `samples/90-compatibility/legacy-v0.2`.

## 9. Files To Update

Samples:

- `samples/README.md`
- every canonical `suite_manifest.yaml`
- every v0.3 `test_cases/*.yaml`
- every `env_profiles/*.yaml`
- `DEPRECATED.md` files for backup and compatibility-only paths
- `samples/90-compatibility/DEPRECATED_PATHS.md`

Scripts:

- `scripts/release/build-usage-kit.sh`
- `scripts/release/verify-usage-kit.sh`
- `scripts/release/verify-v0-3-runtime-samples.sh`
- `scripts/release/verify-supported-provider-samples.sh`
- `scripts/release/verify-wiremock-external-base-url.sh`
- `scripts/ci/check-public-support-contract.sh` if it asserts sample paths

Docs:

- `docs/09-operations/test_framework_user_guide.md`
- `docs/09-operations/provider_support_matrix.md`
- `docs/09-operations/quickstart.md`
- `docs/09-operations/external_runtime_setup.md`
- `docs/08-release/framework/framework_release_readiness.md`
- Current v0.3 specs/design/test plan if they mention `samples/v0_3_dsl`

Do not update `docs/02-architecture/contracts/provider_capability_registry.v0.2.yaml` to advertise v0.3 paths. If sample paths must be machine-readable, use the v0.3 usage-kit manifest or create a v0.3 registry.

Tests:

- `src/test/java/com/specdriven/regression/cli/DslV03*Test.java`
- `src/test/java/com/specdriven/regression/cli/*ProviderCapability*Test.java`
- `src/test/java/com/specdriven/regression/release/ReleaseUsageKitVerificationTest.java`
- `src/test/java/com/specdriven/regression/release/SampleLayoutContractTest.java`
- `src/test/java/com/specdriven/regression/contracts/FrameworkPublicInterfaceContractTest.java`

## 10. Implementation Tasks

### Task 1: Add Sample Inventory And Public Path Contract Tests

- [ ] Add a pre-migration inventory fixture that records each current v0.3 staging sample with:
  - `source_root`
  - `canonical_root`
  - `suite_id`
  - `manifest_version`
  - normalized test case ids
  - normalized SHA-256 for `suite_manifest.yaml`, `env_profiles/**`, `test_cases/**`, fixtures, and expected artifacts
  - expected `kind` from the Canonical Sample Registry
- [ ] Add a post-migration inventory assertion with a bijection: every pre-migration sample id appears exactly once at its canonical destination, and no extra unregistered v0.3 sample appears.
- [ ] Add a backup inventory assertion: every v0.2 source-tree backup listed in the v0.2 Backup Inventory exists, carries `DEPRECATED.md`, preserves expected file counts and normalized hashes, and is absent from the usage-kit zip.
- [ ] Assert inherited canonical roots exist.
- [ ] Assert `samples/v0_3_dsl` is absent from current public docs, release scripts, usage-kit manifest output, and non-historical tests.
- [ ] Assert canonical suite manifests are v0.3 unless the directory contains `DEPRECATED.md`.
- [ ] Assert canonical suite groups do not reference `samples/90-compatibility/legacy-v0.2/` or `samples/90-compatibility/dummy_rest/`.
- [ ] Assert release scripts and Java tests use canonical sample roots from shared constants or explicit canonical path tables, not ad hoc legacy paths.

Verification:

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q -Dtest=SampleLayoutContractTest test
```

### Task 2: Backup v0.2 Artifacts In The Compatibility Root

- [ ] Move current v0.2 leaf suite artifacts into `samples/90-compatibility/legacy-v0.2/**` according to the v0.2 Backup Inventory.
- [ ] Add `DEPRECATED.md` to each backup root.
- [ ] Keep `dummy_rest` as deprecated compatibility-only material in `90-compatibility/dummy_rest/`.
- [ ] Do not delete old artifacts until v0.3 replacements pass validation.

Verification:

```bash
find samples/90-compatibility/legacy-v0.2 -name DEPRECATED.md -print
rg -n '90-compatibility/legacy-v0.2|90-compatibility/dummy_rest' samples/*/suite_manifest.yaml samples/*/*/suite_manifest.yaml || true
```

Expected: backup markers exist; canonical suite groups do not point to backup or compatibility paths.

### Task 3: Promote v0.3 Staging Samples To Inherited Paths

- [ ] Move `samples/v0_3_dsl/golden` to `samples/00-getting-started/golden_e2e`.
- [ ] Move provider capability samples into `samples/20-provider-capability-p0/**` according to the Canonical Sample Registry.
- [ ] Move `samples/v0_3_dsl/multi_test` to `samples/20-provider-capability-p0/verification/multi_test_shared_env`.
- [ ] Move negative samples into `samples/80-negative`.
- [ ] Remove `samples/v0_3_dsl` after all references are updated.

Verification:

```bash
rg -n 'samples/v0_3_dsl|v0_3_dsl' samples docs scripts src/test || true
```

Expected: no current public references remain. If a historical planning document keeps the old term, it must explicitly say "staging path" or "superseded".

### Task 4: Rebuild Suite Groups

- [ ] Convert `samples/20-provider-capability-p0/suite_manifest.yaml` to v0.3 suite group semantics.
- [ ] Ensure all provider capability child suites use inherited canonical paths.
- [ ] Ensure `20-provider-capability-p0` does not reference `wiremock_http_mock`, `legacy-v0.2`, or `90-compatibility`.
- [ ] Rebuild `30-cross-provider-groups/mock_server_cross_verify/suite_manifest.yaml` with exact child ids:
  - `REST-001`: `rest_wiremock_http/suite_manifest.yaml`, `expected_status: passed`
  - `REST-002`: `rest_wiremock_http/suite_manifest_failure.yaml`, `expected_status: failed`
  - `SOAP-001`: `soap_mock_http_client/suite_manifest.yaml`, `expected_status: passed`
  - `SOAP-002`: `soap_mock_http_client/suite_manifest_failure.yaml`, `expected_status: failed`
  - `GRPC-001`: `grpc_mock_grpc_client/suite_manifest.yaml`, `expected_status: passed`
  - `GRPC-002`: `grpc_mock_grpc_client/suite_manifest_failure.yaml`, `expected_status: failed`
- [ ] Treat those child paths as stable group-internal contracts. Public docs should point users to the group root unless explaining internals.
- [ ] Keep standalone failure samples under `80-negative`.

Verification:

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q -Dtest=DslV03CommandTest,DslV03ProviderRuntimeExecutionCommandTest test
```

### Task 5: Update Usage Kit Build And Manifest Verification

- [ ] Stop copying `samples/v0_3_dsl`.
- [ ] Copy inherited canonical sample roots only.
- [ ] Exclude `samples/90-compatibility/legacy-v0.2/` from the v0.3 usage kit.
- [ ] Emit `sample_layout_version: v3`.
- [ ] Emit `sample_public_interface: v0.3`.
- [ ] Emit explicit `canonical_samples[]`, `compatibility_aliases: []`, `path_rewrites[]`, and `deprecated_paths[]`.
- [ ] Verify `canonical_samples[]` has exact set equality with the Canonical Sample Registry rows that ship in the usage kit.
- [ ] Verify usage-kit README points to inherited paths.
- [ ] Verify usage-kit does not expose root-level v0.2 aliases.

Verification:

```bash
bash scripts/release/build-usage-kit.sh 0.3.0
bash scripts/release/verify-usage-kit.sh target/spec-driven-auto-regression-0.3.0-usage-kit.zip 0.3.0
unzip -p target/spec-driven-auto-regression-0.3.0-usage-kit.zip usage-kit/usage-kit-manifest.yaml
jar tf target/spec-driven-auto-regression-0.3.0-usage-kit.zip | rg 'samples/(v0_3_dsl|golden_e2e|provider_capability|contract_baseline|evidence_hardening)|samples/90-compatibility/legacy-v0\\.2' && exit 1 || true
```

### Task 6: Add Extracted Usage-Kit Execution Gate

- [ ] Extract the generated usage-kit zip into a temporary directory.
- [ ] From inside the extracted `usage-kit/`, run `validate`, `run`, `report`, and `validate-evidence` against `samples/00-getting-started/golden_e2e/suite_manifest.yaml`.
- [ ] Run at least one provider capability sample from the extracted kit.
- [ ] Run one `80-negative` sample and assert the expected owner-actionable failure code.
- [ ] Assert generated result JSON and evidence directories exist under the extracted-kit execution location.
- [ ] Assert every command resolves sample paths from the extracted kit or from `usage-kit-manifest.yaml`; commands must not rely on repository `cwd`.

Verification:

```bash
WORK_DIR="$(mktemp -d target/usage-kit-extract.XXXXXX)"
(cd "$WORK_DIR" && jar --extract --file ../spec-driven-auto-regression-0.3.0-usage-kit.zip)
JAR="$PWD/target/spec-driven-auto-regression-0.3.0.jar"
(
  cd "$WORK_DIR/usage-kit"
  java -Xmx512m -jar "$JAR" validate --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml --profile local_v03
  RUN_OUT="$(java -Xmx512m -jar "$JAR" run --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml --profile local_v03)"
  RESULT_JSON="$(printf '%s\n' "$RUN_OUT" | awk -F': ' '/^result_json:/ {print $2; exit}')"
  test -n "$RESULT_JSON"
  test -s "$RESULT_JSON"
  java -Xmx512m -jar "$JAR" report --result "$RESULT_JSON"
  java -Xmx512m -jar "$JAR" validate-evidence --result "$RESULT_JSON"
  test -d "$(dirname "$RESULT_JSON")"
)
```

The final implementation must put the full extracted-kit workflow in `scripts/release/verify-usage-kit.sh`, including provider capability and negative sample assertions, rather than leaving it as a manual command.

### Task 7: Update Docs And Support Matrix

- [ ] Replace all current v0.3 sample paths with inherited paths.
- [ ] Mark v0.2 samples as source-tree backup/deprecated, not current usage-kit content.
- [ ] Ensure only inherited paths may retain `wiremock`; active suite ids, docs, README labels, support matrix labels, manifest ids, and evidence labels use `http_mock + rest_client`.
- [ ] Ensure `wiremock_http_mock` is described as v0.2 compatibility and `http_mock + rest_client` is the v0.3 HTTP mock/client sample.
- [ ] Update quickstart commands.
- [ ] Add `samples/90-compatibility/DEPRECATED_PATHS.md` and link it from `samples/README.md`.

Verification:

```bash
rg -n 'samples/v0_3_dsl|samples/golden_e2e|samples/provider_capability|samples/contract_baseline|samples/evidence_hardening' docs scripts src/test
```

Expected: no current public guidance uses those paths. Any remaining occurrence must be in a historical planning note or explicit deprecation/path-rewrite table.

### Task 8: Negative And Boundary Sample Verification

- [ ] Run one positive canonical sample.
- [ ] Run one `80-negative` validation failure sample and assert the expected failure code.
- [ ] Run this table-driven suite-group boundary matrix:

| Scenario | Expected status | Required assertion |
|---|---|---|
| missing child manifest | `run_status: blocked` | owner-actionable missing child path; no `batch_id`, `run_id`, `suite_summary_json`, or `allure_results_dir` |
| duplicate child id | `run_status: blocked` | deterministic duplicate id failure code; no run artifacts |
| malformed child YAML | `run_status: blocked` | deterministic invalid YAML failure code; no provider runtime |
| child ref escape | `run_status: blocked` | deterministic path escape failure code; no run artifacts |

- [ ] Assert blocked preflight failures do not produce `batch_id`, `run_id`, `suite_summary_json`, or `allure_results_dir`.

Verification:

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q -Dtest=DslV03NegativeSampleCommandTest,SampleLayoutContractTest test
```

### Task 9: Registry And Manifest Parity Verification

- [ ] Add a contract test that parses the Canonical Sample Registry expected set and the built usage-kit manifest.
- [ ] Assert exact id set equality for every `ships_in_usage_kit: true` entry.
- [ ] Assert `root_dir`, `entrypoint_manifest`, `kind`, `default_profile`, `suite_id`, `ships_in_usage_kit`, and `public_contract` match the registry.
- [ ] Assert every `path_rewrites[]` entry matches the Exact Path Rewrite Contract.
- [ ] Assert no manifest entry points to `samples/v0_3_dsl`, root-level v0.2 aliases, or `samples/90-compatibility/legacy-v0.2`.

Verification:

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q -Dtest=ReleaseUsageKitVerificationTest,SampleLayoutContractTest test
```

### Task 10: Release Verification

- [ ] Run v0.3 runtime sample verification from inherited paths.
- [ ] Run extracted usage-kit verification.
- [ ] Run provider support contract check.
- [ ] Run focused unit tests for moved-path contracts.
- [ ] Run full bounded unit test suite before release.

Verification:

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q test
scripts/release/verify-v0-3-runtime-samples.sh 0.3.0
scripts/ci/check-public-support-contract.sh
git diff --check
```

## 11. Acceptance Criteria

- `samples/` has one primary public interface: v0.3.
- The inherited v0.2 directory skeleton remains stable.
- `samples/v0_3_dsl` is removed or treated only as a superseded staging reference in old planning docs.
- v0.2 artifacts are discoverable only as source-tree deprecated backup artifacts under `samples/90-compatibility/legacy-v0.2/`.
- v0.2 backup artifacts are not shipped in the v0.3 usage kit.
- Usage-kit quickstart uses inherited canonical paths.
- Usage-kit manifest exposes an explicit v0.3 sample contract, exact `path_rewrites[]`, and compatibility/deprecation table.
- Support matrix sample paths point to inherited canonical paths.
- Canonical suite groups do not reference backup or compatibility paths.
- v0.3 happy path, failure path, and boundary path samples remain executable.
- Registry, usage-kit manifest, and extracted usage-kit execution are verified by automated tests/scripts.
- Release verification proves usage-kit samples from the extracted zip, not only from the source tree.

## 12. Risks And Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Downstream scripts rely on removed root aliases | Medium | Manifest `path_rewrites[]` and `deprecated_paths[]` list replacement paths; usage-kit ships `DEPRECATED_PATHS.md`; no silent alias behavior. |
| Existing tests rely on `samples/v0_3_dsl` paths | Medium | Add path constants and update tests in one slice after moving samples. |
| v0.3 suite group semantics are incomplete | High | Add group validation tests before moving `20-provider-capability-p0` to v0.3 group. |
| v0.2 users expect old canonical paths | Medium | Keep v0.2 artifacts under source-tree `90-compatibility/legacy-v0.2/` and point exact v0.2 users to older release assets. |
| WireMock naming confusion returns | Medium | Keep `wiremock_http_mock` in v0.2 backup only; active v0.3 labels use `http_mock + rest_client`; only inherited path names may retain `wiremock`. |
| Usage kit zip diverges from repo layout | High | Verify zip contents and run commands from an extracted usage kit in release scripts. |

## 13. Closed Decisions

- v0.2 backup artifacts do not ship in the v0.3 usage kit.
- v0.2 backup artifacts move under `samples/90-compatibility/legacy-v0.2/`.
- No v0.3 root-level compatibility aliases are generated.
- `samples/20-provider-capability-p0/http/rest_client_with_wiremock/` remains the canonical HTTP mock/client path for v0.3.
- `wiremock_http_mock` moves out of the active canonical branch and becomes v0.2 backup/compatibility material only.
- `samples/v0_3_dsl/multi_test/` becomes `samples/20-provider-capability-p0/verification/multi_test_shared_env/`.
- `samples/10-contract-baseline/mixed_wiremock_jdbc_nats/` keeps its historical path for path stability.
