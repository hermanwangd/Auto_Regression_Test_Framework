# Framework Public Interface v0.2

Status: framework-owned current-stage contract.

This contract documents the v0.2 public interface used by developers, CI, release pipelines, framework runtime modules, provider configuration, and future Phase 2 Agent Skills. Controlled breaking changes are allowed before v1.0 when the interface contract, artifact contracts, acceptance criteria, implementation plan, and test plan are updated first. CLI commands are only one part of the interface.

The canonical user-facing runtime model is documented in `docs/09-operations/test_framework_user_guide.md`:

```text
DSL target
  -> provider_id
  -> Provider Instance
  -> provider_type
  -> Framework built-in Provider Contract catalog
  -> selected Env_Profile
  -> Env_Profile.providers.<provider_id>.binding_keys
```

New v0.2 documentation and generated artifacts must use Provider Contract, Provider Instance, Env_Profile, DSL Test Case, CLI, and Evidence Contract terminology. Existing `execution_profile` and `environment_binding` artifacts remain compatibility inputs until the runtime migration is complete, but they are no longer the preferred public authoring model. Internal implementation packages may keep legacy names temporarily, but those names are not public runtime interfaces.

## Controlled Interface Change Rules

- Public commands, required options, exit-code meaning, stable stdout keys, DSL fields, generated artifact fields, framework built-in provider contract fields, provider instance fields, environment binding fields, evidence paths, and input artifact locations are part of the v0.2 contract.
- Interface changes require updates to this file, artifact contracts, AC, implementation plan, and test plan before code changes.
- Additive optional fields are allowed when old callers still work.
- Renaming commands, changing required options, changing exit-code meaning, renaming/removing DSL fields, renaming/removing provider contract or provider instance fields, or removing output keys requires a compatibility decision.
- Phase 2 Agent Skills may wrap invocation commands and generate artifacts, but framework maturity is measured against this public interface directly.

## Stable Interface Families

| Interface Family | Stable Contract Files | Stable Surface |
|---|---|---|
| Invocation | This file | Runtime commands, required/optional options, exit codes, stable stdout keys, and non-runtime support boundary. |
| Test Definition | `test_case_dsl.v0.2.schema.yaml`, `suite_manifest.v0.2.schema.yaml` | DSL identity/status/source refs, labels, compatible profiles, optional data catalog, operation inputs, targets, setup, execute, expected refs, verify, evidence, runtime policy, suite/test/tag selection. |
| Env_Profile | `env_profile.v0.2.schema.yaml`; compatibility inputs: `execution_profile.v0.2.schema.yaml`, `environment_binding.v0.2.schema.yaml` | Environment selection, execution mode, provider runtime modes, target environment values, secret refs, readiness refs, dependency model, constraints, and provider binding keys. |
| Provider Runtime Configuration | `provider_contract.v0.2.schema.yaml`, `provider_instance.v0.2.schema.yaml`, `provider_capability_registry.v0.2.yaml`, `provider_plugin_contract.md`, `verify_plugin_contract.md` | Provider type, provider ID, valid instance shape, runtime modes, executable runtime modes, public `support_status`, allowed operations, allowed input keys, required inputs, required binding keys, defaults, output refs, evidence outputs, and failure codes. |
| Result and Evidence Output | `result.v0.2.schema.yaml`, `evidence.v0.2.schema.yaml`, `evidence_folder_structure.v0.2.md`, `validation_error_taxonomy.v0.2.yaml`, `secret_guardrails.v0.2.yaml` | Standard result shape, deterministic validation errors, failure classification, batch/run evidence locations, assertion evidence, cleanup evidence, report evidence, and masking rules. |
| P0/P1 Contract Catalog | `p0_provider_verify_catalog.v0.2.md` plus provider contract files | Contract and provider capability baseline for WireMock HTTP mock, `rest_client` HTTP request samples, PR-008 WireMock-backed SOAP/gRPC mock contracts, JDBC, NATS/event, P1 Kafka and IBM MQ client providers, polling, JSON/schema/file, fixture injection, reporting, and dry-run readiness. |

## Invocation Interface: Runtime Commands

| Command | Required Options | Optional Options | Success | Blocking / Failure |
|---|---|---|---|---|
| `regress validate` | `--suite <suite_manifest_path>` | `--profile <env_profile_id>`, `--test-case <test-case-id>`, `--tag <tag>` | Validates DSL, suite manifest, Env_Profile, Provider Instance, framework Provider Contract catalog, secret guardrails, and evidence/result contract readiness without provider execution. | Returns non-zero and prints deterministic owner-actionable validation errors. |
| `regress run` | `--suite <suite_manifest_path>` | `--profile <env_profile_id>`, `--dry-run`, `--test-case <test-case-id>`, `--tag <tag>` | Creates one batch ID and one run ID for the selected suite execution, records per-test outcomes in `test_results[]`, and writes run/batch evidence. | Blocks before unsafe provider dispatch when validation, binding, environment, provider contract, provider instance, expected-result, or secret checks fail. |
| `regress report` | `--result <generated_result_json>` | `--format text|yaml` default `text` | Produces review-ready coverage/evidence summary from a standard result JSON and its evidence index. | Returns non-zero when evidence is incomplete, coverage is not review-ready, result JSON is missing, or result schema is invalid. |
| `regress validate-evidence` | `--result <generated_result_json>` | none | Validates result JSON, evidence index, evidence refs, required evidence, and masking guardrails. | Returns non-zero when evidence is missing, inconsistent, or contains raw secrets. |

Non-runtime boundary:

- Product/RP orchestration wrappers, repo initialization, readiness checks, test generation, and expected-result drafting are outside the v0.2.4 framework runtime public interface.
- Product/RP tooling may generate suite-mode artifacts, then invoke the canonical runtime commands above.
- Any remaining compatibility command in implementation code is not a release gate and must not appear in usage-kit release verification.
- `regress report --format json` is not a v0.2.4 public report contract and must return usage error exit `2`.

## Stable Exit Codes

| Exit Code | Meaning |
|---:|---|
| `0` | Command completed and the requested framework result is ready. |
| `1` | Framework validation, execution, evidence, or report completed with blocked, failed, or not-review-ready status. |
| `2` | Command usage error, such as unknown command, missing required option, missing option value, or unsupported option value. |

## CLI Contract Details

### `regress validate`

Syntax:

```bash
regress validate --suite samples/golden_e2e/suite_manifest.yaml [--profile <profile>]
regress validate --suite samples/provider_capability/mock_server_cross_verify/suite_manifest.yaml [--profile <profile>]
```

Suite-mode requires `--suite <suite_manifest_path>` and resolves artifacts relative to the suite manifest. A standard suite may include multiple checked-in tests in `tests[]`; all selected tests share one suite-level profile from CLI `--profile` or `suite_manifest.profile`. Compatibility aggregation manifests may use `child_suites[]` to point at checked-in child suite manifests. The command validates selected checked-in approved DSL tests and generated framework artifacts only. It must not start providers, provision dependencies, mutate fixtures, write run evidence, or infer Product/RP/RU topology.

Machine-readable output must include `status`, `valid`, `errors`, `warnings`, `selected_tests`, `provider_instances_used`, `provider_contracts_used`, `env_profiles_used`, and `owner_action`. Compatibility output may also include `environment_bindings_used` until migration completes. Errors follow `validation_error_taxonomy.v0.2.yaml`.

### `regress run --dry-run`

Syntax:

```bash
regress run --suite samples/golden_e2e/suite_manifest.yaml --dry-run
regress run --suite samples/provider_capability/mock_server_cross_verify/suite_manifest.yaml --dry-run
```

Dry-run performs all validation and planning through DSL target resolution, Provider Instance lookup, framework Provider Contract catalog lookup, Env_Profile lookup, required binding key validation, operation input-key validation, output-ref validation, and safety validation. For `child_suites[]` aggregation manifests, dry-run validates every child suite, prints child suite status metadata, and keeps `provider_runtime_invoked: false`. It produces a resolved execution plan or child-suite aggregation plan and must not execute provider operations, mutate fixtures, publish messages, run SQL, call external endpoints, or create provider execution evidence.

Golden E2E framework sample mode without `--dry-run` may execute only framework-owned fake providers that are deterministic, local, self-contained, and marked as framework verification evidence. Provider capability suite-path mode may execute only checked-in framework provider capability samples explicitly listed in the provider capability plan and marked as framework provider capability evidence. Neither mode may execute Product/RP/RU topology interpretation, SIT/preprod release evidence, downstream deployment, K8s, VM, external runner, or non-selected provider types.

Kafka and IBM MQ P1 provider work is client-provider work. It may validate contracts, dry-run target resolution, run mocked client seams, and optionally run against a profile-gated native broker. It must not provision brokers, queue managers, Testcontainers, or RUs unless a later dependency-provisioning slice explicitly adds that capability.

### `regress report`

Syntax:

```bash
regress validate-evidence --result <generated_result_json>
regress report --result <generated_result_json> [--format text|yaml]
```

Evidence validation and report read standard result JSON and evidence indexes only. They fail with exit code `1` when required evidence is missing, raw secret masking failed, result schema validation failed, the result JSON path is missing, the result JSON is invalid, declared `test_count` and `test_results[]` are inconsistent, or coverage is not review-ready.

## Invocation Output Keys

Commands may print additional diagnostic lines, but these keys are stable for automation:

| Command | Stable Keys |
|---|---|
| `validate` | `status`, `valid`, `errors`, `warnings`, `selected_tests`, `provider_instances_used`, `provider_contracts_used`, `env_profiles_used`, `owner_action` |
| `run --dry-run` | `execution_started`, `environment_gaps`, `dsl_gaps`, `binding_gaps`, `provider_instances_used`, `provider_contracts_used`, `provider_contract_gaps`, `ap_gate_status`, `resolved_execution_plan`, `child_suites`, `run_status` |
| `run` | `batch_id`, `run_status`, `test_case_id`, `run_id`, `run_dir`, `status`, `exit_code`, `timeout`, `stdout`, `stderr`, `test_count`, `suite_summary_json`, `suite_summary_yaml`, `allure_results_dir`, `expected_failure_count` |
| `report` | `report_status`, `coverage_percent`, `covered`, `total_automatable`, `review_dir`, `gaps` |

## DSL and Test Definition Interface

The framework consumes approved test definitions as public inputs. v0.2 test definition compatibility is governed by:

- `docs/02-architecture/contracts/test_case_dsl.v0.2.schema.yaml`
- `docs/02-architecture/contracts/suite_manifest.v0.2.schema.yaml`

The stable DSL surface includes:

- `dsl_version`, test identity, lifecycle `status`, revision, tags, labels, source refs, and compatible profiles.
- optional `data.<name>.ref` or `data.<name>.value` for reusable reviewed data sources.
- operation-level `inputs` maps for provider binding; each key must be allowed by the resolved Provider Contract operation.
- `targets`, `setup.operations`, `cleanup.operations`, `execute.operations`, expected refs, `verify.checks`, `evidence`, and `runtime` sections.
- `targets.<target>.provider_id`; DSL test cases must not contain endpoint, topic, database credential, namespace, or secret values. Active profile selection belongs to CLI or suite manifest, with `compatible_profiles` used only as a guardrail.
- Execute operation names, verify type names, evidence requirement names, and input binding expression shape.
- Approved-test eligibility from `tests/approved/`; draft/generated tests are not runtime eligible.
- Single suite manifests may list multiple checked-in DSL test cases in `tests[]`; all selected test cases run under one suite-level profile selected by CLI `--profile` or suite manifest `profile`. A provider capability suite may include test cases for more than one executable provider type when they share the selected Env_Profile, for example Kafka and IBM MQ client-provider checks in one messaging suite.
- `child_suites[]` aggregation manifests remain a compatibility model using `profile` and `child_suites[].{id,ref,profile,expected_status}` for checked-in child suites. Child suite profiles are not the primary multi-test runner model. Child refs must stay under the aggregation manifest directory after normalization, and child ids must be unique. Standard suites must not contain legacy suite-type fields or `test_cases[]`; new multi-test execution belongs in `tests[]`.

DSL changes that rename fields, change required/conditional field rules, remove enum values, or change input binding semantics require a compatibility decision.

## Run, Environment, and Provider Configuration Interface

The canonical user-facing model below is Env_Profile, Provider Contract, and Provider Instance.

## Env_Profile, Provider Contract, and Provider Instance Interface

The framework consumes generated runtime configuration as public inputs. v0.2 configuration compatibility is governed by:

- `docs/02-architecture/contracts/env_profile.v0.2.schema.yaml`
- `docs/02-architecture/contracts/execution_profile.v0.2.schema.yaml` as a compatibility input until migration
- `docs/02-architecture/contracts/environment_binding.v0.2.schema.yaml` as a compatibility input until migration
- `docs/02-architecture/contracts/run_profile.v0.2.schema.yaml` as a compatibility alias for `execution_profile.v0.2.schema.yaml`
- `docs/02-architecture/contracts/provider_contract.v0.2.schema.yaml`
- `docs/02-architecture/contracts/provider_instance.v0.2.schema.yaml`
- `docs/02-architecture/contracts/provider_capability_registry.v0.2.yaml`
- `docs/02-architecture/contracts/provider_plugin_contract.md`
- `docs/02-architecture/contracts/verify_plugin_contract.md`

The stable provider runtime configuration surface includes:

- Env_Profile ID, execution mode, isolation scope, provider runtime modes, dependency model, dependency substitution policy, dependency provisioning policy, constraints, data policy, max duration, and evidence policy.
- DSL target references `provider_id`; active profile comes from CLI or suite manifest.
- Provider Instance declares one logical runtime target with `provider_id`, `provider_type`, allowed runtime modes, defaults, evidence capture choices, and failure mapping. It must not redefine Provider Contract binding key schema.
- Provider Contract declares the provider type, runtime-mode vocabulary, executable runtime modes when only a subset is runnable by this framework build, allowed operations, allowed input keys, required inputs, binding key schema, bindable outputs, required fields, defaults, output refs, evidence outputs, failure codes, and valid Provider Instance shape. Built-in Provider Contracts are framework-owned and resolved by `provider_type`.
- Env_Profile supplies environment-specific `runtime_mode` and actual values under `providers.<provider_id>.binding_keys`. The `providers` map keys are Provider Instance `provider_id` values. Each binding key must match the resolved Provider Contract `binding_keys`.
- Env_Profile binding key values may use `value`, `ref`, `secret_ref`, `generated_ref`, or approved `local_ref` only when allowed by the Provider Contract binding key schema. `generated_ref` values must target a producing Provider Contract `bindable_outputs` entry, such as `generated://wiremock-payment-api.base_url`, or a selected Env_Profile `dependency_provisioning_policy.generated_outputs` entry; `local_ref` is limited to framework-controlled local/CI fixtures and must not be used as SIT/preprod release evidence.
- WireMock-backed mock Provider Contracts may expose predefined generated endpoint refs, such as `generated://payment-soap-mock.endpoint_url` and `generated://customer-grpc-mock.target_uri`. These refs are suite/batch lifecycle outputs and must not be embedded in DSL test cases.
- Local and CI Env_Profiles may use mock, stub, ephemeral, fake-topic, embedded-broker, disposable-schema, or generated-data replacements for external dependencies only when those dependencies are materialized before framework execution and allowed by Env_Profile policy, the framework built-in Provider Contract executable runtime modes, and Provider Instance runtime modes. SIT and preprod default to native dependencies and must not produce release evidence from mock substitution.
- Provider capability registry entries list supported `provider_type` values and blocked unsupported or ambiguous provider selections.
- Product/RP/RU labels remain traceability metadata and must not select runtime behavior.

Provider interface changes that rename provider types, change required binding keys, change binding key value kinds, change runtime mode semantics, remove supported operations, remove allowed input keys, alter cleanup compatibility, alter bindable outputs, alter output refs, or alter evidence outputs require a compatibility decision.

## Stable Input Artifact Locations

The framework runtime consumes these canonical generated locations under `docs/08-release/release-packages/<rp_id>/`:

- `generated-framework/suite_manifest.yaml`
- `generated-framework/run_plan.yaml`
- `generated-framework/env_profiles/`
- `generated-framework/provider_instances/`
- `generated-framework/traceability_map.yaml`
- `tests/approved/`
- `parameter-sets/`
- `expected-results/approved/`

Compatibility readers may also accept these legacy locations until Env_Profile runtime migration completes:

- `generated-framework/execution_profiles/`
- `generated-framework/run_profiles/`
- `generated-framework/environment_bindings/`

RP-local authoring directories such as `provider-instances/`, `env-profiles/`, `environment-bindings/`, `execution-profiles/`, and optional `custom-provider-contracts/` may exist for owner or Agent Skill workflow. Runtime execution must consume the generated canonical paths above plus the framework built-in Provider Contract catalog so validation, dry-run, and execution resolve the same artifacts. Suite-local Provider Contracts are ignored unless `provider_contract_resolution` explicitly selects custom or snapshot mode.

The framework must not infer Product/RP/RU topology from labels, folder names, repo names, or implementation language.

## Stable Output Artifact Locations

The framework writes evidence under:

- `evidence/batches/<batch_id>/batch.yaml`
- `evidence/runs/<run_id>/run.yaml`
- `evidence/runs/<run_id>/actual/`
- `evidence/runs/<run_id>/logs/`
- `evidence/runs/<run_id>/assertions.yaml`
- `evidence/runs/<run_id>/cleanup.yaml`
- `evidence/review/`

Framework sample suite groups write aggregation artifacts under:

- `target/suite-groups/<suite_id>/<batch_id>/<run_id>/suite_summary.json`
- `target/suite-groups/<suite_id>/<batch_id>/<run_id>/suite_summary.yaml`
- `target/suite-groups/<suite_id>/<batch_id>/<run_id>/allure-results/`

These suite group artifacts summarize framework provider capability tests only. They are not downstream RP release evidence.

## Non-Runtime Support Boundary

Product Repo and Phase 2 Agent Skill workflows may initialize folders, perform owner-readiness checks, draft tests, or draft expected results outside the framework runtime. Those workflows are not current-stage framework runtime gates and are not v0.2.4 release-verification commands.

Phase 2 Product Repo translation must emit suite-mode artifacts and invoke `regress validate --suite <suite_manifest_path>`, `regress run --suite <suite_manifest_path> --profile <env_profile_id>`, `regress report --result <generated_result_json>`, and `regress validate-evidence --result <generated_result_json>`.
