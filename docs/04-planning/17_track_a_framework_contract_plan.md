# Track A — Framework Contract Plan

## 1 Summary

Track A delivers a contract-complete framework baseline so Track B Golden E2E and Track C Provider Scenarios can start without public-interface rework. It documents and sample-validates the framework-readable artifacts, CLI contract, validation taxonomy, secret guardrails, result/evidence contract, and P0 provider/verify catalog.

Track A is not runtime-complete.

## 2 Scope Lock

In scope: DSL Test Case, Provider Contract, Provider Instance, Execution Profile, Environment Binding, Suite Manifest, Result, Evidence, CLI validation/dry-run/report contract, sample artifacts, validation errors, and secret guardrails.

Out of scope: full WireMock/JDBC/NATS/K8s/VM/external-runner runtime, Phase 2 Agent Skill, RP/RU topology interpretation, release governance, waiver workflow, release gate, Go/No-Go automation, and product-specific strategy selection.

## 3 Track A Deliverables

- Documented v0.2 public interface with controlled breaking changes allowed before v1.0.
- Contract schemas and contract reference docs under `docs/02-architecture/contracts/`.
- `samples/` artifact set for dry-run validation.
- AC and test-plan mapping for contract validation.
- ADR-backed Provider Contract / Provider Instance terminology.

## 4 Schema Inventory

| Artifact | Path |
|---|---|
| DSL Test Case | `docs/02-architecture/contracts/test_case_dsl.v0.2.schema.yaml` |
| Provider Contract | `docs/02-architecture/contracts/provider_contract.v0.2.schema.yaml` |
| Provider Instance | `docs/02-architecture/contracts/provider_instance.v0.2.schema.yaml` |
| Execution Profile | `docs/02-architecture/contracts/execution_profile.v0.2.schema.yaml` |
| Environment Binding | `docs/02-architecture/contracts/environment_binding.v0.2.schema.yaml` |
| Suite Manifest | `docs/02-architecture/contracts/suite_manifest.v0.2.schema.yaml` |
| Result | `docs/02-architecture/contracts/result.v0.2.schema.yaml` |
| Evidence | `docs/02-architecture/contracts/evidence.v0.2.schema.yaml` |
| Error taxonomy | `docs/02-architecture/contracts/validation_error_taxonomy.v0.2.yaml` |
| Secret guardrails | `docs/02-architecture/contracts/secret_guardrails.v0.2.yaml` |

## 5 CLI Contract

`regress validate --root <product-repo> --rp-id <rp-id> --env <profile> [--test-case <id> | --suite <id> | --tag <tag>] [--format yaml|json] [--strict]`

Validates DSL, suite manifest, Execution Profile, Provider Contract, Provider Instance, Environment Binding, result/evidence contract, and secret guardrails. It must not execute providers or mutate fixtures.

`regress run --root <product-repo> --rp-id <rp-id> --env <profile> --dry-run [--test-case <id> | --suite <id> | --tag <tag>]`

Builds a resolved execution plan without real operations. Output includes provider_id, Provider Instance, provider_type, Provider Contract, profile, Environment Binding, required binding key status, output refs, AP gate, and owner action.

`regress report --root <product-repo> --rp-id <rp-id> --batch-id <batch-id> [--format text|yaml|json]`

Reads result/evidence only. Exit codes are `0` ready, `1` blocked/failed/not-review-ready, and `2` usage error.

## 6 P0 Provider / Verify Catalog

Track A documents contract placeholders for:

- HTTP/Mock: `wiremock_http_mock`, `http_stub`, `http_mock_called`, `http_mock_request_body_match`.
- DB: `jdbc`, `secret_ref` connection, SQL params binding, Oracle/DB2 dialect metadata, `db_record_exists`, query evidence.
- NATS/Event: `nats`, `nats_publish`, `nats_observe`, `event_published`, `event_payload_match`, `consume_from: test_start_time`, subject handling, event evidence.
- Polling: timeout, `poll_interval`, last observed evidence.
- JSON/Schema/File: `json_match`, `schema_match`, `ignore_paths`, `file_diff`, `normalize`, `ignore_order`.
- Test Data Injection: DSL `data` catalog, operation `inputs`, `db_seed`, `db_cleanup`, `http_stub`.
- Reporting: standard result JSON and evidence folder structure.

## 7 Validation Error Taxonomy

All blocking output uses `validation_error_taxonomy.v0.2.yaml`: `schema_error`, `target_resolution_error`, `environment_error`, `secret_resolution_error`, `fixture_setup_error`, `execution_error`, `timeout`, `verification_failed`, `cleanup_error`, `evidence_not_complete`, and `framework_error`.

## 8 Secret Guardrail Rules

Raw secrets are prohibited in DSL, data binding, Execution Profile, Environment Binding, Provider Instance, Provider Contract, result JSON, evidence, CLI stdout, and report output. Accepted forms are `secret_ref`, `env_ref`, `vault_ref`, `ci_secret_ref`, and `generated_secret_ref`.

## 9 Sample Artifact Set

Required sample paths:

- `samples/contract_baseline/suite_manifest.yaml`
- `samples/contract_baseline/test_case.yaml`
- `samples/contract_baseline/provider_contracts/{wiremock_http_mock,jdbc,nats}.yaml`
- `samples/contract_baseline/provider_instances/{wiremock_payment_api,oracle_database,nats_event_bus}.yaml`
- `samples/contract_baseline/execution_profiles/{ci_pr,sit_regression}.yaml`
- `samples/contract_baseline/environment_bindings/{ci,sit}.yaml`
- `samples/contract_baseline/expected_results/sample_expected.json`
- `samples/contract_baseline/fixtures/{sample_db_seed,sample_db_cleanup}.yaml`
- `samples/contract_baseline/result/sample_result.json`
- `samples/contract_baseline/evidence/evidence_index.yaml`

## 10 Result and Evidence Contract

Result JSON must include framework version, DSL version, test case ID, status, profile, environment, timestamps, provider results, steps, verify results, evidence refs, and failure. Provider results must include provider_id, provider_type, profile, runtime_mode, resolved operation result, and release-evidence eligibility.

Evidence folders must include batch/run summaries, resolved execution plan, provider evidence, query evidence, event evidence, assertion results, cleanup results, masking report, and review report refs.

## 11 Definition of Ready

- User guide and framework public interface agree.
- Provider terminology is used in public docs.
- Contract file paths are agreed.
- Track A scope excludes runtime completion and Phase 2 Agent Skill.
- Sample artifact list is explicit.

## 12 Definition of Done

- Feature/spec, architecture, AC, test plan, and user guide are aligned.
- Contract files and sample YAML/JSON parse successfully.
- `regress validate`, `regress run --dry-run`, and `regress report` contract is documented.
- P0 provider/verify catalog exists.
- Error taxonomy and secret guardrails exist.
- Consistency scan finds no user-facing implementation-hook terminology or legacy provider family field in active docs.

## 13 First PR Plan

First PR is documentation/spec alignment only:

1. Add Track A spec patch and plan.
2. Update public interface and user guide for `regress validate`.
3. Correct architecture maturity wording from runtime-complete to contract baseline.
4. Add sample artifact set.
5. Run syntax and terminology checks.

## 14 Work Breakdown

| Task | Scope | Acceptance |
|---|---|---|
| A1 | Public interface docs | `validate`, `run --dry-run`, and `report` are specified with output and exit codes. |
| A2 | Contract docs | Required schema inventory, P0 catalog, error taxonomy, guardrails, and evidence structure exist. |
| A3 | Spec/architecture/AC/test plan | Track A is contract-complete and runtime-not-complete. |
| A4 | Sample artifacts | All required sample paths exist and parse. |
| A5 | Consistency review | Active docs use Provider terminology and avoid topology interpretation. |

## 15 Risks and Mitigation

Risk: Track A may be mistaken for runtime completion. Mitigation: every Track A doc states runtime is deferred.

Risk: Provider Instance shape drifts from Provider Contract. Mitigation: AC and test plan require unknown-field, invalid input-key, required-input, and missing output-ref validation.

Risk: local/CI mock substitution leaks into release evidence. Mitigation: Execution Profile and Environment Binding contracts require release-evidence eligibility markers.

## 16 Open Questions

- Should `regress validate` write a validation report artifact by default, or only print machine-readable output?
- Should YAML or JSON be the default output format for automation?
- After Track B Golden E2E, which Track C provider scenario should be implemented first: JDBC, NATS, WireMock, REST/gRPC, K8s/VM, or external runner?

## File Path Proposal

Use docs as the contract source of truth and `samples/` as the executable-shape fixture source. Runtime code should later read Product Repo generated artifacts from `docs/08-release/release-packages/<rp_id>/generated-framework/`, not from `samples/`.

## Track A Acceptance Criteria

- Valid sample DSL target resolves `provider_id` + `profile`.
- Provider Instance exists and conforms to Provider Contract.
- Provider Contract exists for `provider_type`.
- Environment Binding exists for the selected profile.
- Required binding keys are supplied.
- Invalid input key, missing required input, missing output ref, missing Provider Contract, missing Provider Instance, missing Environment Binding, and missing binding key are specified as blocking failures.
- Dry-run produces a resolved execution plan without execution.
- Evidence includes provider_id, provider_type, profile, runtime_mode, and resolved operation result.

## Immediate First PR Checklist

- [ ] Add/patch Track A docs.
- [ ] Add required sample artifacts.
- [ ] Parse YAML/JSON samples.
- [ ] Scan active docs for user-facing legacy implementation-hook terms and legacy provider family field.
- [ ] Run `git diff --check`.
