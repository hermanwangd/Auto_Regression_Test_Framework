# Track B — Golden E2E Implementation Plan

> For agentic workers: implement this plan task-by-task with review after each task. Track B proves the framework lifecycle with a deterministic framework-owned fake provider; it does not implement heterogeneous provider scenarios.

## 1. Summary

Track B proves that the v0.2 public interface can run one complete framework verification flow end-to-end using checked-in sample artifacts. It converts Track A dry-run artifacts into an executable golden sample that validates, runs, writes evidence/result JSON, and reports review-ready framework verification output.

Track B evidence is framework verification evidence only. It is not downstream Product/RP release evidence.

## 2. Scope and Non-goals

In scope:

- Public CLI flow for `validate`, `run`, and `report`.
- Suite manifest loading from `samples/golden_e2e/suite_manifest.yaml`.
- DSL, Provider Contract, Provider Instance, Execution Profile, Environment Binding, target, fixture, execute, verify, evidence, result, and report lifecycle.
- Deterministic framework-owned fake provider.
- Happy path plus framework validation/report failure paths.

Out of scope:

- WireMock, JDBC, NATS, Kafka, REST/gRPC, K8s, VM, external runner, Oracle, DB2, SIT, or downstream product deployment runtime.
- Product/RP/RU topology interpretation.
- Phase 2 Agent Skill.
- Release governance, waiver workflow, release gate, Go/No-Go, Allure, and ReportPortal.

## 3. Track B Prerequisites from Track A

Track B starts only after these Track A artifacts exist and parse:

- `docs/02-architecture/contracts/test_case_dsl.v0.2.schema.yaml`
- `docs/02-architecture/contracts/provider_contract.v0.2.schema.yaml`
- `docs/02-architecture/contracts/provider_instance.v0.2.schema.yaml`
- `docs/02-architecture/contracts/execution_profile.v0.2.schema.yaml`
- `docs/02-architecture/contracts/environment_binding.v0.2.schema.yaml`
- `docs/02-architecture/contracts/suite_manifest.v0.2.schema.yaml`
- `docs/02-architecture/contracts/result.v0.2.schema.yaml`
- `docs/02-architecture/contracts/evidence_folder_structure.v0.2.md`
- `docs/02-architecture/contracts/validation_error_taxonomy.v0.2.yaml`
- `docs/02-architecture/contracts/secret_guardrails.v0.2.yaml`
- `docs/02-architecture/contracts/framework_usage_interface.v0.2.md`

## 4. Golden E2E Flow

1. Load `samples/golden_e2e/suite_manifest.yaml`.
2. Select `GOLDEN-E2E-TC-001`.
3. Resolve `samples/golden_e2e/execution_profiles/local_golden.yaml`.
4. Resolve Provider Instance `sample-fake-runtime`.
5. Resolve Provider Contract `sample_fake_provider`.
6. Resolve `samples/golden_e2e/environment_bindings/local_golden.yaml`.
7. Validate target resolution through `provider_id` and `profile`.
8. Set up `fixtures/setup_fixture.yaml`.
9. Execute `execute_sample` through the fake provider.
10. Capture `actual_json`, `actual_text`, and `execution_log`.
11. Run `value_equals` and `json_match`.
12. Write evidence index, logs, setup/cleanup evidence, actual output, assertion evidence, diff artifact, result JSON, and batch summary.
13. Run report against generated result JSON.
14. Confirm report is review-ready.

## 5. Framework-owned Sample Provider

Provider type: `sample_fake_provider`.

The fake provider is local, deterministic, self-contained, and framework-owned. It reads checked-in sample input and expected-result artifacts, produces a deterministic actual JSON artifact, writes execution logs, and supports setup/cleanup evidence. It must not call external endpoints, open DB connections, publish messages, execute shell scripts, or inspect Product/RP/RU labels.

Implementation target:

- `src/main/java/com/specdriven/regression/provider/SampleFakeProvider.java`
- `src/test/java/com/specdriven/regression/provider/SampleFakeProviderTest.java`

Allowed operations:

- `setup_fixture`
- `execute_sample`
- `cleanup_fixture`

## 6. Sample Artifact Set

Checked-in sample files:

- `samples/golden_e2e/suite_manifest.yaml`
- `samples/golden_e2e/test_case.yaml`
- `docs/02-architecture/contracts/provider-contracts/sample_fake_provider.yaml`
- `samples/golden_e2e/provider_instances/sample_fake_instance.yaml`
- `samples/golden_e2e/execution_profiles/local_golden.yaml`
- `samples/golden_e2e/environment_bindings/local_golden.yaml`
- `samples/golden_e2e/fixtures/input.json`
- `samples/golden_e2e/fixtures/setup_fixture.yaml`
- `samples/golden_e2e/fixtures/cleanup_fixture.yaml`
- `samples/golden_e2e/expected_results/expected_output.json`
- `samples/golden_e2e/evidence/expected_evidence_index.yaml`
- `samples/golden_e2e/result/expected_result_shape.json`

Generated runtime output should be written under a temporary output root during tests, for example `target/golden-e2e/<batch_id>/`, not committed.

## 7. CLI Behavior

Track B adds a framework-verification suite path to the public CLI without replacing the Product Repo path:

```bash
regress validate --suite samples/golden_e2e/suite_manifest.yaml
regress run --suite samples/golden_e2e/suite_manifest.yaml --profile local_golden
regress report --result <generated_result_json>
```

Required behavior:

- `validate` returns `0` for valid sample artifacts and `1` for validation failures.
- `run` returns `0` when the golden sample passes and `1` when verification fails.
- `run` generates deterministic-shape `batch_id` and `run_id`.
- `run` writes standard result JSON and evidence folder.
- `report` consumes generated result JSON.
- `report` returns `1` when result JSON is missing or invalid.
- Usage errors return `2`.

## 8. Runtime Lifecycle

Execution order:

```text
CLI
-> SuiteManifestLoader
-> DSL validation
-> Provider Contract validation
-> Provider Instance validation
-> Execution Profile resolution
-> Environment Binding resolution
-> Target resolution
-> Fixture setup
-> Fake provider execute
-> Verify/assertion
-> Fixture cleanup
-> Evidence writer
-> Result writer
-> Report reader
```

The runtime must not hardcode `samples/golden_e2e` inside implementation classes. Paths come from CLI arguments and suite manifest references.

## 9. Result JSON Requirements

Generated result JSON must include:

- `framework_version`
- `dsl_version`
- `suite_id`
- `batch_id`
- `run_id`
- `test_case_id`
- `profile`
- `status`
- `start_time`
- `end_time`
- `duration_ms`
- `labels`
- `source_refs`
- `step_results`
- `verify_results`
- `evidence_refs`
- `failure`

Failed results must include technical failure classification.

## 10. Evidence Folder Requirements

Evidence folder must include:

- `evidence_index.yaml` or `evidence_index.json`
- `logs/execution.log`
- `fixture/setup.yaml`
- `fixture/cleanup.yaml`
- `actual/actual_output.json`
- `expected/expected_output.ref`
- `assertions/status_is_ok.yaml`
- `assertions/output_matches_expected_json.yaml`
- `diffs/output_matches_expected_json.diff`
- `batch/batch.yaml`

Every evidence record must include `evidence_classification: framework_verification_only` or equivalent metadata and must not contain raw secrets.

## 11. Happy Path Tests

| Test | Command | Expected |
|---|---|---|
| Golden validate | `regress validate --suite samples/golden_e2e/suite_manifest.yaml` | Exit `0`; selected suite/test/provider/profile are reported. |
| Golden run | `regress run --suite samples/golden_e2e/suite_manifest.yaml --profile local_golden` | Exit `0`; batch ID, run ID, result JSON path, and evidence path are printed. |
| Golden report | `regress report --result <generated_result_json>` | Exit `0`; report status is `review_ready`. |
| Result schema | JUnit loads generated result JSON and validates required fields | All required result fields exist. |
| Evidence shape | JUnit checks generated evidence folder | Required evidence files exist and are masked. |

## 12. Failure Path Tests

| Failure | Expected Result |
|---|---|
| Invalid DSL | Blocks before execution with `schema_error`. |
| Missing Provider Instance | Blocks before execution with `target_resolution_error`. |
| Unsupported operation | Blocks before execution with provider contract validation error. |
| Missing expected result | Blocks before verification or fails with `EXPECTED_RESULT_MISSING`. |
| Verification mismatch | Writes failed result JSON and diff evidence. |
| Missing evidence reference | Report or validation fails with `evidence_not_complete`. |
| Raw secret in DSL or binding | Blocks with `secret_resolution_error`. |
| Invalid result JSON | `regress report --result` returns `1` with owner-actionable error. |

## 13. Definition of Ready

- Track A schema artifacts exist.
- Track A sample artifacts parse.
- CLI validate/run/report contracts exist.
- Result schema exists.
- Evidence folder spec exists.
- `sample_fake_provider` contract exists.
- `sample-fake-runtime` Provider Instance exists.
- `local_golden` Execution Profile exists.
- `local_golden` Environment Binding exists.

## 14. Definition of Done

1. Golden E2E runs through public CLI.
2. Framework-owned sample provider executes deterministically.
3. DSL, Provider Contract, Provider Instance, Execution Profile, and Environment Binding are validated before execution.
4. Target resolution happens through Environment Binding, not hardcoded runtime paths.
5. Fixture setup evidence is produced.
6. Fixture cleanup evidence is produced.
7. Execute step captures declared outputs.
8. At least one value verify passes.
9. At least one artifact verify passes.
10. Verification mismatch produces failed result JSON and diff evidence.
11. Standard result JSON conforms to result schema.
12. Evidence folder conforms to evidence spec.
13. Report consumes generated result JSON.
14. All required failure paths are covered.
15. Evidence is labeled as framework verification evidence.
16. Traceability matrix entry exists.
17. Evidence matrix entry exists.
18. Documentation is updated.
19. Track C provider scenarios can reuse the same lifecycle without changing the public contract.

## 15. First PR Plan

First implementation PR should include:

1. Golden E2E sample loader and validation tests.
2. Fake provider contract/instance resolution.
3. Fake provider execution with setup/execute/cleanup evidence.
4. Result JSON writer for Golden E2E output.
5. Report consumption by `--result`.
6. Happy path and failure path tests.

## 16. Work Breakdown

| Task | Files | Acceptance |
|---|---|---|
| B1 CLI suite path | `RegressionCommand.java`, `RegressionCommandTest.java` | `validate --suite`, `run --suite --profile`, and `report --result` parse and return correct usage errors. |
| B2 Sample artifact reader | `runtime/GoldenSuiteLoader.java`, `runtime/GoldenSuiteLoaderTest.java` | Loads suite, DSL, provider contract, provider instance, profile, binding, fixtures, expected result. |
| B3 Validation chain | `schema`, `dsl`, `provider`, `environment`, `runtime` tests | Invalid DSL, missing instance, unsupported operation, raw secret, and missing binding block before execution. |
| B4 Fake provider | `SampleFakeProvider.java`, `ProviderRuntimeRegistry.java`, tests | Executes setup, sample operation, cleanup without external services. |
| B5 Verification | `AssertionEngine.java`, tests | `value_equals` and `json_match` pass; mismatch writes diff evidence. |
| B6 Evidence/result | `EvidenceWriter.java`, result writer, tests | Required evidence files and standard result JSON are written. |
| B7 Report | `ResultReportService.java`, `RegressionCommand.java`, `ResultReportServiceTest.java` | `report --result` consumes result JSON and rejects invalid JSON. |
| B8 Integration | `GoldenE2EIT.java`, packaged CLI smoke | Public CLI happy path and failure paths pass. |

## 17. Risks and Mitigation

- Risk: fake provider becomes a hidden production provider. Mitigation: name and evidence classify it as framework verification only.
- Risk: CLI suite-path mode diverges from Product Repo mode. Mitigation: keep lifecycle services shared after artifact loading.
- Risk: sample paths become hardcoded. Mitigation: tests run from copied temp directories.
- Risk: report becomes release governance. Mitigation: Track B report only consumes result JSON and reports review-ready framework evidence.

## 18. Open Questions

- Should `--profile` become the primary CLI term while `--env` remains a Product Repo compatibility alias?
- Should `report --result` accept only one result JSON in Track B, or a future result index file as well?
- Should Golden E2E batch/run IDs be deterministic in tests or generated with a stable prefix plus timestamp?

## Task Breakdown Table

| Order | Task | Depends On | Verification |
|---:|---|---|---|
| 1 | Add CLI suite-path parsing | Track A CLI contract | `RegressionCommandTest` usage cases |
| 2 | Load golden sample artifact graph | samples/golden_e2e | Loader unit tests |
| 3 | Validate full artifact graph | loader | validation failure tests |
| 4 | Execute fake provider lifecycle | validation | fake provider unit tests |
| 5 | Write result/evidence | fake provider | evidence/result tests |
| 6 | Consume result report | result writer | report tests |
| 7 | Run public CLI Golden E2E | all prior tasks | `GoldenE2EIT` |

## File Path Proposal

- `src/main/java/com/specdriven/regression/runtime/GoldenSuiteLoader.java`
- `src/main/java/com/specdriven/regression/provider/SampleFakeProvider.java`
- `src/main/java/com/specdriven/regression/result/ResultWriter.java`
- `src/main/java/com/specdriven/regression/report/ResultReportService.java`
- `src/test/java/com/specdriven/regression/integration/GoldenE2EIT.java`
- `src/test/java/com/specdriven/regression/provider/SampleFakeProviderTest.java`
- `src/test/java/com/specdriven/regression/runtime/GoldenSuiteLoaderTest.java`

## Acceptance Criteria for Track B

- AC-B001 validates the golden suite with `regress validate --suite samples/golden_e2e/suite_manifest.yaml`.
- AC-B002 runs the golden suite with `regress run --suite samples/golden_e2e/suite_manifest.yaml --profile local_golden`.
- AC-B003 reports the generated result with `regress report --result <generated_result_json>`.
- AC-B004 blocks invalid DSL, missing Provider Instance, unsupported operation, raw secret, and missing expected result before unsafe execution.
- AC-B005 writes framework verification evidence and standard result JSON.
- AC-B006 verification mismatch writes failed result JSON and diff evidence.
- AC-B007 report rejects missing or invalid result JSON.
- AC-B008 no Product/RP/RU labels influence execution decisions.

## Immediate First PR Checklist

- [ ] Add CLI suite-path parsing tests.
- [ ] Add Golden suite loader tests.
- [ ] Add fake provider unit tests.
- [ ] Add Golden E2E happy path integration test.
- [ ] Add failure path tests listed in Section 12.
- [ ] Run `./mvnw -Dtest='RegressionCommandTest,GoldenSuiteLoaderTest,SampleFakeProviderTest' test`.
- [ ] Run `./mvnw -Dit.test=GoldenE2EIT verify`.
- [ ] Confirm evidence is written under `target/` and not committed.
