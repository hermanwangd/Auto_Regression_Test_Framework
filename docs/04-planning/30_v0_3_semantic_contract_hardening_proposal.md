# DSL v0.3 Semantic Contract Hardening Proposal

**Status:** Proposed
**Scope:** v0.3 validation, dry-run, runtime planning, references, assertions, samples, and release verification.

## 1. Decision

Harden v0.3 around one public semantic model:

```text
Suite Manifest + selected Env_Profile + all DSL Test Cases + Provider Contracts
  -> CompiledSuite
  -> dry-run renderer or runtime executor
  -> result, evidence, report
```

`V03ExecutionPlanBuilder` becomes the framework's sole v0.3 compiler. Validation compiles and reports diagnostics; `run --dry-run` renders that compiled output; and `run` executes it. No path may re-parse raw YAML or apply independent ref/operation semantics after compilation.

This is a hardening change. It adds no provider, Product/RP/RU interpretation, Agent Skill, dashboard, or release-governance capability. v0.2 behavior remains isolated.

## 2. Compiled Multi-Test Model

A suite is not a test case. The compiler output must preserve shared suite resolution and separate test ownership:

```text
CompiledSuite
  suiteId, profileId, resolvedTargets, artifactRoots
  compiledTests: List<CompiledTestCase>

CompiledTestCase
  testCaseId, ordered setup/execute/verify/cleanup steps
```

Each compiled provider step carries its resolved target, Provider Contract, provider type, runtime mode, typed `with` values, declared outputs, and evidence requirements. Each compiled assertion carries a supported assertion kind and typed actual/expected references. Results remain attributable to `suite_id`, `test_case_id`, `batch_id`, and `run_id`.

## 3. Fixed Reference Contract

The following forms are the only v0.3 public references:

| Reference | Owner and meaning |
| --- | --- |
| `artifact://<root>/<path>[#<json-pointer>]` | Checked-in artifact under a Suite Manifest `artifact_roots` entry. |
| `step://<step_id>/<output_path>` | Declared output of an earlier step in the same test case. |
| `generated://<target>/<output>` | Bindable output declared by a Suite target's Provider Contract and selected Env_Profile. |
| `env://<NAME>` | Env_Profile binding only; resolves through the environment without exposing its value. |

`generated://<provider>.<output>` is a prohibited legacy form. It must fail validation with `invalid_generated_ref`, not be silently accepted. A shared typed resolver must parse, validate, materialize, and mask these references for every v0.3 path. JSON Pointer is valid only for `artifact://` values and is applied after artifact materialization.

## 4. Assertion and Provider-Check Boundaries

Framework-owned assertions are fail-closed. The versioned assertion catalog includes scalar comparisons (`equals`, `not_equals`, `gt`, `gte`, `lt`, `lte`, `matches`, `exists`, `not_exists`) and artifact verifiers (`json_match`, `schema_match`, `file_diff`). Unknown kinds, invalid operands, or incompatible value kinds block before provider dispatch.

`verify.type: assertion` compares declared values and never invokes a provider. `verify.type: provider_check` invokes the target Provider Contract's operation with `with`; it may only use contract-declared provider-specific expectation fields. Generic `provider_check.expect` is prohibited. A test that needs to compare provider output with a reviewed oracle performs a subsequent framework assertion against the provider step output.

For v0.3, these are the only verify variants. Legacy `verify.checks[]` and legacy top-level verifier names remain v0.2 compatibility syntax only; they are rejected in a v0.3 leaf test case and must never be silently normalized into v0.3 semantics.

## 5. Provider Contract and Binding Rules

Provider Contracts remain framework-owned. Their v0.3 operations must minimally declare allowed phases, supported `with` fields, required fields, accepted ref/value kinds, output paths, bindable outputs, evidence types, failure codes, and runtime modes. The compiler validates a Provider Contract before validating each step that uses it.

Env_Profile target bindings must match the Suite Manifest target namespace exactly. `generated://` dependencies form a target graph; missing producers, undeclared outputs, self-dependencies, and cycles block compilation. Binding resolution must not depend on the process working directory.

## 6. Version and Golden-Path Rules

All artifacts in a v0.3 **leaf suite** are v0.3 only when its Suite Manifest declares `manifest_version: v0.3` and every referenced test case declares `dsl_version: v0.3`. A leaf suite with mixed test-case versions blocks before schema lookup, compilation, or runtime dispatch. A suite group may aggregate child-suite summaries from different leaf-suite versions; it never compiles a cross-version child collection into one leaf execution plan.

The golden suite must use the normal registry, compiler, runtime adapter, result, evidence, and report path. Before removing special command dispatch, characterization tests freeze its result JSON, evidence index, cleanup behavior, report output, and exit code.

## 7. Documentation and Release Evidence

Canonical executable fixtures live under `samples/`; documents link to them or verify snippets against them. Documentation must not duplicate complete fixture trees.

A v0.3 release gate must extend the existing `verify-v0-3-runtime-samples.sh`, `verify-usage-kit.sh`, release-version, schema-drift, and contract checks. It must run from a clean checkout with the Maven Wrapper, build the jar and usage kit, validate all v0.3 samples, execute the supported local sample set, verify report/evidence output, and confirm contract registry resolution outside the repository working directory. Release archives must include the jar, checksums, usage kit, schemas, Provider Contract catalog, samples, and user guide.

## 8. Acceptance Conditions

- Validation, dry-run, and runtime consume one compiled representation.
- A multi-test suite compiles and reports each test independently while retaining suite totals.
- Each public reference form resolves identically in validation, dry-run, and runtime.
- Unknown assertion kinds and legacy generated refs fail before runtime.
- `provider_check` has no generic assertion model.
- Mixed-version artifacts within one leaf suite, unresolved target dependencies, and contract/output mismatches block before dispatch; suite-group aggregation remains version-neutral.
- Golden execution no longer uses command-layer special dispatch.
- Existing v0.2 compatibility tests and supported v0.3 samples remain green.
