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
  -> selected profile
  -> Environment Binding
```

New v0.2 documentation and generated artifacts must use Provider Contract, Provider Instance, Environment Binding, Execution Profile, DSL Test Case, CLI, and Evidence Contract terminology. Internal implementation packages may keep legacy names temporarily, but those names are not public runtime interfaces.

## Controlled Interface Change Rules

- Public commands, required options, exit-code meaning, stable stdout keys, DSL fields, generated artifact fields, framework built-in provider contract fields, provider instance fields, environment binding fields, evidence paths, and input artifact locations are part of the v0.2 contract.
- Interface changes require updates to this file, artifact contracts, AC, implementation plan, and test plan before code changes.
- Additive optional fields are allowed when old callers still work.
- Renaming commands, changing required options, changing exit-code meaning, renaming/removing DSL fields, renaming/removing provider contract or provider instance fields, or removing output keys requires a compatibility decision.
- Phase 2 Agent Skills may wrap invocation commands and generate artifacts, but framework maturity is measured against this public interface directly.

## Stable Interface Families

| Interface Family | Stable Contract Files | Stable Surface |
|---|---|---|
| Invocation | This file | Runtime commands, required/optional options, exit codes, stable stdout keys, and support-command boundary. |
| Test Definition | `test_case_dsl.v0.2.schema.yaml`, `suite_manifest.v0.2.schema.yaml` | DSL identity/status/source refs, labels, compatible profiles, parameters, targets, setup, execute, expected results, verify, evidence, runtime policy, suite/test/tag selection. |
| Execution Profile and Environment Binding | `execution_profile.v0.2.schema.yaml`, `environment_binding.v0.2.schema.yaml` | Profile selection, execution mode, target environment values, secret refs, readiness refs, dependency model, constraints, and binding keys. |
| Provider Runtime Configuration | `provider_contract.v0.2.schema.yaml`, `provider_instance.v0.2.schema.yaml`, `provider_capability_registry.v0.2.yaml`, `provider_plugin_contract.md`, `verify_plugin_contract.md` | Provider type, provider ID, valid instance shape, allowed operations, allowed `bind_as` values, required binding keys, defaults, output refs, evidence outputs, failure codes, and runtime status. |
| Result and Evidence Output | `result.v0.2.schema.yaml`, `evidence.v0.2.schema.yaml`, `evidence_folder_structure.v0.2.md`, `validation_error_taxonomy.v0.2.yaml`, `secret_guardrails.v0.2.yaml` | Standard result shape, deterministic validation errors, failure classification, batch/run evidence locations, assertion evidence, cleanup evidence, report evidence, and masking rules. |
| P0 Contract Catalog | `p0_provider_verify_catalog.v0.2.md` | Contract baseline for HTTP mock, JDBC, NATS/event, polling, JSON/schema/file, fixture injection, reporting, and dry-run readiness. |

## Invocation Interface: Runtime Commands

| Command | Required Options | Optional Options | Success | Blocking / Failure |
|---|---|---|---|---|
| `regress validate` | Product Repo mode: `--rp-id <rp-id>`, `--env <profile>`; framework sample mode: `--suite <suite_manifest_path>` | `--root <product-repo>` default `.`, `--test-case <test-case-id>`, `--tag <tag>`, `--format yaml|json` default `yaml`, `--strict` | Validates DSL, suite manifest, Execution Profile, Provider Instance, framework Provider Contract catalog, Environment Binding, secret guardrails, and evidence/result contract readiness without provider execution. | Returns non-zero and prints deterministic owner-actionable validation errors. |
| `regress run` | Product Repo mode: `--rp-id <rp-id>`, `--env <profile>`; framework sample mode: `--suite <suite_manifest_path>`, `--profile <profile>` | `--root <product-repo>` default `.`, `--dry-run`, `--test-case <test-case-id>`, `--tag <tag>` | Creates one batch ID, one run ID per selected approved test or parameter case, and writes run/batch evidence. | Blocks before unsafe provider dispatch when validation, binding, environment, provider contract, provider instance, expected-result, or secret checks fail. |
| `regress report` | Product Repo mode: `--rp-id <rp-id>`, `--batch-id <batch-id>`; framework sample mode: `--result <generated_result_json>` | `--root <product-repo>` default `.`, `--format text|yaml|json` default `text` | Produces review-ready coverage/evidence summary from batch evidence or a framework verification result JSON. | Returns non-zero when evidence is incomplete, coverage is not review-ready, result JSON is missing, or result schema is invalid. |

Debug-only compatibility:

- `regress report --run-id <run-id>` may exist for local debugging, but it is not a release coverage interface and must not satisfy RP release evidence.
- Compatibility option markers retained for older contract tests: `--strict-schema`, `--suite <suite-id>`, `--format text`.

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
regress validate --root <product-repo> --rp-id <rp-id> --env <profile> [--test-case <id> | --suite <id> | --tag <tag>] [--format yaml|json] [--strict]
regress validate --suite samples/golden_e2e/suite_manifest.yaml [--format yaml|json] [--strict]
```

Product Repo mode requires `--rp-id` and `--env`; `--root` defaults to `.`. Framework sample mode requires `--suite <suite_manifest_path>` and resolves artifacts relative to the suite manifest. The command validates selected checked-in approved DSL tests and generated framework artifacts only. It must not start providers, provision dependencies, mutate fixtures, write run evidence, or infer Product/RP/RU topology.

Machine-readable output must include `status`, `valid`, `errors`, `warnings`, `selected_tests`, `provider_instances_used`, `provider_contracts_used`, `environment_bindings_used`, and `owner_action`. Errors follow `validation_error_taxonomy.v0.2.yaml`.

### `regress run --dry-run`

Syntax:

```bash
regress run --root <product-repo> --rp-id <rp-id> --env <profile> --dry-run [--test-case <id> | --suite <id> | --tag <tag>]
regress run --suite samples/golden_e2e/suite_manifest.yaml --profile local_golden
```

Dry-run performs all validation and planning through DSL target resolution, Provider Instance lookup, framework Provider Contract catalog lookup, Environment Binding lookup, required binding key validation, `bind_as` validation, output-ref validation, and safety validation. It produces a resolved execution plan and must not execute provider operations, mutate fixtures, publish messages, run SQL, call external endpoints, or create provider execution evidence.

Framework sample mode without `--dry-run` may execute only framework-owned fake providers that are deterministic, local, self-contained, and marked as framework verification evidence. It must not execute production provider types such as WireMock, JDBC, NATS, Kafka, REST/gRPC, K8s, VM, or external runner.

### `regress report`

Syntax:

```bash
regress report --root <product-repo> --rp-id <rp-id> --batch-id <batch-id> [--format text|yaml|json]
regress report --result <generated_result_json> [--format text|yaml|json]
```

Report reads standard result JSON and evidence indexes only. It fails with exit code `1` when required evidence is missing, raw secret masking failed, result schema validation failed, the result JSON path is missing, the result JSON is invalid, or coverage is not review-ready.

## Invocation Output Keys

Commands may print additional diagnostic lines, but these keys are stable for automation:

| Command | Stable Keys |
|---|---|
| `validate` | `status`, `valid`, `errors`, `warnings`, `selected_tests`, `provider_instances_used`, `provider_contracts_used`, `environment_bindings_used`, `owner_action` |
| `run --dry-run` | `execution_started`, `environment_gaps`, `dsl_gaps`, `binding_gaps`, `provider_instances_used`, `provider_contracts_used`, `provider_contract_gaps`, `ap_gate_status`, `resolved_execution_plan`, `run_status` |
| `run` | `batch_id`, `run_status`, `test_case_id`, `run_id`, `run_dir`, `status`, `exit_code`, `timeout`, `stdout`, `stderr` |
| `report` | `report_status`, `coverage_percent`, `covered`, `total_automatable`, `review_dir`, `gaps` |

## DSL and Test Definition Interface

The framework consumes approved test definitions as public inputs. v0.2 test definition compatibility is governed by:

- `docs/02-architecture/contracts/test_case_dsl.v0.2.schema.yaml`
- `docs/02-architecture/contracts/suite_manifest.v0.2.schema.yaml`

The stable DSL surface includes:

- `dsl_version`, test identity, lifecycle `status`, revision, tags, labels, source refs, and compatible profiles.
- operation-level `parameters.ref` / `parameters.bind_as` (`parameters[].ref` / `parameters[].bind_as` in array form) for reviewed parameter selection and provider binding.
- `targets`, `setup`, `cleanup`, `execute`, `expected_results`, `verify`, `evidence`, and `runtime` sections.
- `targets.<target>.provider_id`; DSL test cases must not contain endpoint, topic, database credential, namespace, or secret values. Active profile selection belongs to CLI or suite manifest, with `compatible_profiles` used only as a guardrail.
- Execute operation names, verify type names, evidence requirement names, and parameter binding expression shape.
- Approved-test eligibility from `tests/approved/`; draft/generated tests are not runtime eligible.

DSL changes that rename fields, change required/conditional field rules, remove enum values, or change parameter binding semantics require a compatibility decision.

## Run, Environment, and Provider Configuration Interface

The canonical user-facing model below is Execution Profile, Environment Binding, Provider Contract, and Provider Instance.

## Execution Profile, Environment, Provider Contract, and Provider Instance Interface

The framework consumes generated runtime configuration as public inputs. v0.2 configuration compatibility is governed by:

- `docs/02-architecture/contracts/execution_profile.v0.2.schema.yaml`
- `docs/02-architecture/contracts/environment_binding.v0.2.schema.yaml`
- `docs/02-architecture/contracts/run_profile.v0.2.schema.yaml` as a compatibility alias for `execution_profile.v0.2.schema.yaml`
- `docs/02-architecture/contracts/provider_contract.v0.2.schema.yaml`
- `docs/02-architecture/contracts/provider_instance.v0.2.schema.yaml`
- `docs/02-architecture/contracts/provider_capability_registry.v0.2.yaml`
- `docs/02-architecture/contracts/provider_plugin_contract.md`
- `docs/02-architecture/contracts/verify_plugin_contract.md`

The stable provider runtime configuration surface includes:

- Execution Profile ID, execution mode, trigger, isolation scope, dependency model, dependency substitution policy, dependency provisioning policy, constraints, data policy, and max duration.
- DSL target references `provider_id`; active profile comes from CLI or suite manifest.
- Provider Instance declares one RP logical runtime target with `provider_id`, `provider_type`, allowed runtime modes, allowed operation selections, binding key names, defaults, evidence capture choices, and failure mapping using the same top-level shape as the Provider Contract.
- Provider Contract declares the provider type, allowed runtime modes, allowed operations, allowed `bind_as` values, required fields, defaults, output refs, evidence outputs, failure codes, and valid Provider Instance shape. Built-in Provider Contracts are framework-owned and resolved by `provider_type`.
- Environment Binding supplies profile-specific `runtime_mode` and actual values for the Provider Instance binding keys, such as URLs, topics, DB connection refs, namespaces, host refs, secret refs, mock service refs, stub server refs, fake topic refs, embedded broker refs, ephemeral DB refs, or disposable schema refs.
- Local and CI profiles may use mock, stub, ephemeral, Testcontainers-backed, fake-topic, embedded-broker, disposable-schema, or generated-data replacements for external dependencies only when allowed by the Execution Profile, framework built-in Provider Contract, and Provider Instance. The Execution Profile defines allowed provisioners, dependency types, startup/readiness policy, cleanup scope, and output binding keys. SIT and preprod default to native dependencies and must not produce release evidence from mock substitution.
- Provider capability registry entries list supported `provider_type` values and blocked unsupported or ambiguous provider selections.
- Product/RP/RU labels remain traceability metadata and must not select runtime behavior.

Provider interface changes that rename provider types, change required binding keys, change runtime mode semantics, remove supported operations, remove allowed `bind_as` values, alter cleanup compatibility, alter output refs, or alter evidence outputs require a compatibility decision.

## Stable Input Artifact Locations

The framework runtime consumes these canonical generated locations under `docs/08-release/release-packages/<rp_id>/`:

- `generated-framework/suite_manifest.yaml`
- `generated-framework/run_plan.yaml`
- `generated-framework/execution_profiles/`
- `generated-framework/run_profiles/` as a compatibility alias for generated Execution Profiles
- `generated-framework/provider_instances/`
- `generated-framework/environment_bindings/`
- `generated-framework/traceability_map.yaml`
- `tests/approved/`
- `parameter-sets/`
- `expected-results/approved/`

RP-local authoring directories such as `provider-instances/`, `environment-bindings/`, `execution-profiles/`, and optional `custom-provider-contracts/` may exist for owner or Agent Skill workflow. Runtime execution must consume the generated canonical paths above plus the framework built-in Provider Contract catalog so validation, dry-run, and execution resolve the same artifacts. Suite-local Provider Contracts are ignored unless `provider_contract_resolution` explicitly selects custom or snapshot mode.

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

## Next-Stage Support Commands

These commands support Product Repo and Phase 2 Agent Skill workflows. They are not current-stage framework runtime gates:

- `regress init-product-repo`
- `regress check-readiness`
- `regress init-rp`
- `regress check-rp`
- `regress check-rp --strict-schema`
- `regress generate-tests`
- `regress draft-expected-results`

They may be tested as support behavior, but they do not prove framework runtime maturity.
