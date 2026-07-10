# DSL v0.3 No Provider Instance Implementation Plan

**Status:** In progress for release/0.3.0; validation and dry-run baseline is the current implementation slice
**Spec:** `docs/01-specs/05_dsl_v0_3_no_provider_instance_spec.md`
**Architecture:** `docs/02-architecture/08_dsl_v0_3_no_provider_instance_architecture.md`
**AC:** `docs/03-acceptance/05_dsl_v0_3_acceptance_criteria.md`
**Test Plan:** `docs/07-validation-evidence/11_dsl_v0_3_test_plan.md`

## 1. Goal

Implement DSL v0.3 as a versioned public interface that removes user-authored Provider Instance artifacts. The framework must validate, dry-run, execute, produce evidence, and report using:

```text
DSL target -> Suite Manifest target -> Provider Contract -> Env_Profile target -> Execution Plan
```

v0.2 runtime behavior must remain compatible and isolated.

## 2. Non-Goals

- Do not remove v0.2 Provider Instance support.
- Do not introduce new provider families.
- Do not infer Product/RP/RU topology.
- Do not add release governance, waiver workflow, dashboard, Allure, or ReportPortal.
- Do not silently migrate v0.2 files during v0.3 validation.

## 3. Implementation Phases

### Phase 1: Contract Schemas and Fixture Skeletons

**Files likely touched:**

- `schemas/test_case_dsl.v0.3.schema.yaml`
- `schemas/suite_manifest.v0.3.schema.yaml`
- `schemas/env_profile.v0.3.schema.yaml`
- `schemas/provider_contract.v0.3.schema.yaml`
- `docs/02-architecture/contracts/*.v0.3.schema.yaml`
- `samples/v0_3_dsl/golden/`
- `samples/v0_3_dsl/negative/`

**Tasks:**

- [ ] Add v0.3 schemas matching the formal spec.
- [ ] Add minimal valid golden sample.
- [ ] Add minimal negative fixtures for legacy fields, missing target, missing binding, and unsupported operation.
- [ ] Add schema parse tests.

**Acceptance:**

- [ ] v0.3 schemas parse and reject prohibited fields.
- [ ] v0.2 schemas remain unchanged except references to v0.3 docs if needed.

**Verify:**

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw test -Dtest=*V03*Schema*Test
```

### Phase 2: Versioned Loader and Validation Boundary

**Files likely touched:**

- DSL loader/parser classes.
- Suite manifest loader.
- Env_Profile loader.
- Validation service and error taxonomy mapping.

**Tasks:**

- [ ] Route `dsl_version: v0.3` to the v0.3 validator.
- [ ] Keep `dsl_version: v0.2` on the existing path.
- [ ] Reject v0.3 legacy fields.
- [ ] Validate suite targets, Env_Profile target entries, selected profile, and contract IDs.
- [ ] Produce owner-actionable field paths.

**Acceptance:**

- [ ] Unknown target blocks before runtime.
- [ ] Provider Instance files are not read for v0.3.
- [ ] Existing v0.2 smoke tests still pass.

**Verify:**

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw test -Dtest=*V03*Validation*Test,GoldenE2eCommandTest
```

### Phase 3: Provider Contract and Env_Profile Binding Validation

**Files likely touched:**

- Provider contract registry.
- Provider contract validator.
- Env_Profile binding resolver.
- Secret guardrail scanner.

**Tasks:**

- [ ] Validate runtime mode against Provider Contract.
- [ ] Validate required and optional binding keys.
- [ ] Validate `env://` and `generated://` refs.
- [ ] Validate generated outputs against contract-declared bindable outputs.
- [ ] Block raw secrets in suite, Env_Profile, and DSL.

**Acceptance:**

- [ ] Missing binding, invalid binding kind, blank env var, and raw secret fail before dispatch.
- [ ] Dry-run masks binding values.

**Verify:**

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw test -Dtest=*V03*Binding*Test,*Secret*Test
```

### Phase 4: Typed Ref Resolver and Artifact Root Guard

**Files likely touched:**

- Ref parser/resolver.
- Artifact materializer.
- Path security guard.

**Tasks:**

- [ ] Implement `artifact://`, `step://`, `generated://`, and `env://` parsing for v0.3.
- [ ] Enforce artifact root containment.
- [ ] Reject traversal, absolute paths, symlink escape, and invalid JSON pointer.
- [ ] Enforce prior-step-only `step://` refs.

**Acceptance:**

- [ ] Valid artifact refs materialize.
- [ ] Invalid path and step refs fail with owner-actionable errors.

**Verify:**

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw test -Dtest=*V03*Ref*Test,*Path*Test
```

### Phase 5: Execution Plan Builder and Dry-Run

**Files likely touched:**

- Execution plan model.
- Planning/binding service.
- CLI `run --dry-run` output path.

**Tasks:**

- [ ] Build normalized execution plan from suite, DSL, Provider Contract, and Env_Profile.
- [ ] Validate operations, `with` inputs, expectation paths, operators, and output refs.
- [ ] Emit deterministic dry-run output.
- [ ] Guarantee provider runtime is not invoked during dry-run.

**Acceptance:**

- [ ] Golden dry-run returns `provider_runtime_invoked: false`.
- [ ] Invalid operation/input/expectation cases block dry-run.

**Verify:**

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw test -Dtest=*V03*DryRun*Test
```

### Phase 6: Runtime Execution and Cleanup Semantics

**Files likely touched:**

- Execution engine.
- Provider runtime dispatch adapter from execution plan.
- Cleanup manager.

**Tasks:**

- [ ] Execute provider operations from the normalized plan.
- [ ] Ensure provider runtimes do not need Provider Instance files for v0.3.
- [ ] Preserve setup, execute, verify, cleanup ordering.
- [ ] Always attempt cleanup after setup starts.
- [ ] Preserve primary failure plus secondary cleanup failures.

**Acceptance:**

- [ ] Golden suite runs and writes result/evidence.
- [ ] Cleanup failure tests preserve original failure.

**Verify:**

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw verify -Dit.test=*V03*IT
```

### Phase 7: Result, Evidence, and Report

**Files likely touched:**

- Result writer/schema.
- Evidence index validator.
- Report command.

**Tasks:**

- [ ] Add v0.3 result fields for target and Provider Contract.
- [ ] Remove v0.3 dependency on Provider Instance evidence refs.
- [ ] Validate result evidence refs and evidence index paths.
- [ ] Mask secrets in result, evidence, report, and logs.
- [ ] Update report summary for v0.3.

**Acceptance:**

- [ ] `regress report --result <result_json>` validates v0.3 result/evidence.
- [ ] Missing evidence and secret leakage fail report.

**Verify:**

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw test -Dtest=*V03*Report*Test,*Evidence*Test
```

### Phase 8: Compatibility and Release Gate

**Files likely touched:**

- Compatibility tests.
- User guide section for v0.3 preview if release scope includes it.
- Sample README.

**Tasks:**

- [ ] Run selected v0.2 smoke tests.
- [ ] Run all v0.3 tests.
- [ ] Add user-facing migration notes only after implementation is accepted.
- [ ] Confirm no v0.3 docs claim v0.2.7 release support.

**Acceptance:**

- [ ] v0.2 selected smoke tests pass.
- [ ] v0.3 full test matrix passes.
- [ ] Documentation clearly separates v0.2 and v0.3.

**Verify:**

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw test
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw verify -Dit.test=*V03*IT
```

## 4. Dependency Order

```text
schemas
  -> versioned loader
  -> contract/env validation
  -> ref resolver
  -> execution plan
  -> runtime execution
  -> result/evidence/report
  -> compatibility gate
```

Do not start runtime execution work until dry-run resolution is green.

## 5. Risks and Mitigations

| Risk | Impact | Mitigation |
| --- | --- | --- |
| v0.3 changes accidentally weaken v0.2 behavior | High | Version-based loader split and v0.2 smoke tests in every checkpoint. |
| Provider runtimes still expect Provider Instance models | High | Introduce execution-plan runtime input and adapt one provider path first before broad migration. |
| Env_Profile becomes too powerful | Medium | Keep runtime values only under target `bindings`; suite and DSL remain value-free. |
| Path refs become unsafe | High | Central path guard with traversal, symlink, and root overlap tests. |
| Dry-run leaks secrets | High | Dry-run prints binding status only; secret scan covers dry-run output fixtures. |

## 6. Implementation Readiness Checklist

- [x] Formal spec exists.
- [x] Architecture/design update exists.
- [x] Acceptance criteria exist and cover happy, failure, and boundary paths.
- [x] Test plan maps AC to positive and negative tests.
- [x] Implementation tasks are ordered by dependency and have verification commands.
- [x] v0.2 compatibility boundary is explicit.
- [x] No code implementation should start before this checklist is reviewed.
