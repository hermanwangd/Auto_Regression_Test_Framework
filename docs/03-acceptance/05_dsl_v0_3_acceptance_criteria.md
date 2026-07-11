# DSL v0.3 Acceptance Criteria

**Status:** v0.3.0 golden baseline and P0 assertion/reference hardening implemented; AC-V03-015 through AC-V03-017 are planned for v0.3.1.
**Scope:** Versioned v0.3 DSL and runtime interface. These AC do not replace v0.2 acceptance criteria.

## AC-V03-001 Validate Suite Manifest Target Model

Happy path: Given a v0.3 suite manifest with `targets.<target>.provider_contract`, declared artifact roots, Env_Profile refs, and test refs, `regress validate --suite <suite>` passes and reports the selected target names and Provider Contracts.

Failure path: Missing `manifest_version`, duplicate target, missing Provider Contract ID, missing Env_Profile ref, missing test ref, or environment-specific values in the suite manifest fail validation with artifact path and field path.

Boundary path: Built-in Provider Contracts resolve from the bundled registry by default. `--contract-root` may add or override contracts only when the contract ID is explicit.

## AC-V03-002 Resolve DSL Target Without Provider Instance

Happy path: Given a DSL step target declared in the suite manifest and present in the selected Env_Profile, dry-run resolves target, Provider Contract, provider type, runtime mode, and masked binding status without reading a Provider Instance file.

Failure path: Unknown target, missing Env_Profile target entry, missing Provider Contract, ambiguous contract ID, or legacy `provider_id` / `provider_instance` field blocks before provider runtime.

Boundary path: v0.2 tests continue to use the v0.2 path. v0.3 tests must not infer targets from v0.2 Provider Instance files.

## AC-V03-003 Validate Env_Profile Bindings

Happy path: Env_Profile `targets.<target>.bindings` supplies all required binding keys for the selected target runtime mode and uses allowed ref/value kinds.

Failure path: Missing required binding, unknown binding key, unsupported runtime mode, invalid `env://` name, blank env var, invalid generated ref, raw secret, or binding value kind not allowed by Provider Contract fails validation.

Boundary path: Env_Profile may omit optional policy sections and receive documented framework defaults.

## AC-V03-004 Validate Provider Contract Operations

Happy path: Each `op` in setup, execute, verify provider checks, and cleanup exists in the target Provider Contract and all required `with` inputs are present.

Failure path: Unknown operation, missing required input, unsupported input field, invalid input type, unsupported phase restriction, or operation not available for the selected runtime mode blocks before dispatch.

Boundary path: If a Provider Contract does not restrict operation phases, the operation may be used in any DSL phase subject to input, safety, and runtime-mode validation.

## AC-V03-005 Validate Provider Check Boundary

Happy path: A `verify` item with `type: provider_check` validates its target, operation, and contract-declared `with` inputs, then exposes only contract-declared outputs for later assertions.

Failure path: A generic `provider_check.expect`, unsupported input, or undeclared output ref fails validation before provider dispatch with owner-actionable detail.

Boundary path: Provider-specific semantic business comparison remains out of scope unless the Provider Contract explicitly defines the comparator.

## AC-V03-006 Validate Structured Assertions

Happy path: A `verify` item with `type: assertion` compares a prior `step://` output using a supported operator and expected literal or artifact ref.

Failure path: Missing `assert`, unknown operator, unsupported actual ref, unknown step ID, forward step ref, cross-test step ref, or output marked `secret` / `internal` fails validation.

Boundary path: Outputs marked `masked` may be compared by deterministic masked representation only when the Provider Contract allows the output path for assertion.

## AC-V03-007 Validate Artifact References and Path Security

Happy path: `artifact://fixtures/...`, `artifact://queries/...`, and `artifact://expected_results/...` resolve under declared artifact roots and optional JSON pointers extract bounded values.

Failure path: Unknown root, missing file, absolute path, `../`, `~`, drive-letter path, encoded traversal, symlink escape, root overlap, invalid JSON pointer, excessive pointer depth, or extracted value over size limit fails validation.

Boundary path: Provider Contracts validate ref type and materialization mode; they do not define suite filesystem roots.

## AC-V03-008 Dry-Run Produces Resolved Execution Plan

Happy path: `regress run --suite <suite> --profile <profile> --dry-run` returns `dry_run_status: ready`, `provider_runtime_invoked: false`, resolved targets, operation plan, evidence plan, and validation findings.

Failure path: Any validation failure returns `dry_run_status: blocked` with no provider runtime invocation.

Boundary path: Dry-run may show binding names and status, but must not print resolved env var values or secrets.

## AC-V03-009 Execute Valid v0.3 Suite

Happy path: `regress run --suite <suite> --profile <profile>` executes the normalized plan, records ordered step results, produces result JSON, writes evidence index, and supports `regress report --result <result_json>`.

Failure path: Provider runtime error, timeout, verification failure, or evidence write failure produces a failed or blocked result with failure code, category, target, Provider Contract, operation, and safe original cause.

Boundary path: Repeated runs must write isolated output directories and must not corrupt prior evidence.

## AC-V03-010 Preserve Cleanup Semantics

Happy path: If setup starts, declared test cleanup runs after setup, execute, or verify failure. Original failure remains primary and cleanup failure is secondary.

Failure path: Cleanup failure after a passing test makes the test failed with `CLEANUP_ERROR`. Cleanup failure after an original failure is recorded without hiding the original failure.

Boundary path: If setup fails before acquiring a resource, dependent cleanup may be marked `skipped` with owner-actionable reason.

## AC-V03-011 Validate Result and Evidence Contract

Happy path: Result JSON references a valid evidence index, each evidence ref exists, required provider/assertion/polling/cleanup evidence exists, and report summarizes target, Provider Contract, profile, pass/fail counts, evidence folder, and masking status.

Failure path: Missing evidence index, missing evidence file, unknown evidence ref, unsupported evidence type, duplicate evidence ID, failed assertion without diff evidence, polling timeout without last observation, or raw secret leakage fails evidence/report validation.

Boundary path: Report must validate before printing review-ready status.

## AC-V03-012 Reject Legacy v0.2 DSL Fields in v0.3

Happy path: A clean v0.3 DSL test case uses direct suite targets, `with`, structured `verify`, and typed refs.

Failure path: `uses`, `targets`, `provider_id`, `provider_instance`, `data_binding`, `datasets`, `fixtures`, `expected_results`, `db_seed`, `db_cleanup`, `mock_stubs`, `parameters`, or `bind_as` in a v0.3 test case fails validation.

Boundary path: v0.2 artifacts may still be accepted by the v0.2 runtime path; they are not auto-upgraded inside v0.3 validation.

## AC-V03-013 Maintain Secret Guardrails

Happy path: `env://`, generated refs, secret refs, and masked evidence are accepted.

Failure path: Raw passwords, bearer tokens, API keys, JDBC URLs with credentials, broker credentials, authorization headers, private keys, or resolved secret values in suite, Env_Profile, DSL, result JSON, evidence, report, or logs fail validation or evidence scan.

Boundary path: Secret-like test data may appear only when explicitly marked as non-secret fixture data and not matching configured raw secret patterns.

## AC-V03-014 Preserve v0.2 Compatibility Boundary

Happy path: Existing v0.2 samples continue to validate and run through the v0.2 path after v0.3 implementation.

Failure path: v0.3 changes must not remove or reinterpret v0.2 Provider Instance fields, Env_Profile compatibility, suite group behavior, or existing result/report compatibility.

Boundary path: New v0.3 samples must not be used to claim v0.2 release support unless they are explicitly added to a versioned v0.3 release gate.

## AC-V03-015 Canonical Plan and Compile-Once

Happy path: validate, dry-run, and run for one v0.3 leaf suite share one typed `V03CompiledSuite` digest and produce the same resolved target/step ordering.

Failure path: an invalid artifact blocks before a canonical plan is emitted; runtime must not reload YAML or revalidate semantic contracts.

## AC-V03-016 Typed Contracts and Generated DAG

Happy path: a typed Provider Contract validates phase, input kind, output sensitivity, runtime mode, and bindable generated output before dispatch.

Failure path: invalid type, phase, runtime mode, missing producer, undeclared generated output, self-reference, or dependency cycle blocks with an owner-actionable field path.

## AC-V03-017 Strict Version Routing and Executable Release Docs

Happy path: each leaf suite routes by one declared manifest/DSL version; executable documentation samples validate, run where locally supported, and pass report/evidence checks from the release artifact.

Failure path: absent, unsupported, or mixed leaf versions block before schema/contract lookup. A release gate fails when a documented sample, bundled contract registry, or Maven verification command is unavailable outside the repository working directory.
