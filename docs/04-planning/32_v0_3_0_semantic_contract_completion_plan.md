# v0.3.0 Semantic Contract Completion Plan

## Goal

Complete the six remaining v0.3 semantic-contract capabilities before v0.3.0 release completion. Do not add providers, change the v0.2 path, or add product/RP/RU interpretation.

## Order

1. Freeze current v0.3 results, evidence, and CLI output with characterization tests.
2. Replace raw-map fields in `V03CompiledSuite` with immutable typed plan records. Keep `V03ExecutionPlan` only as a derived compatibility view.
3. Make `V03ExecutionPlanBuilder` the one compiler. Discovery/version routing happens once, compilation happens once, and validate/dry-run/run consume the same plan digest.
4. Add typed Provider Contract records for bindings, inputs, outputs, sensitivity, phase, runtime mode, evidence, and failure codes; migrate the bundled YAML catalog and schemas.
5. Build a generated-binding DAG from selected Env_Profile bindings. Reject missing producer, undeclared output, self-edge, and cycles before plan emission; use topological order for framework-managed producers.
6. Enforce strict leaf `manifest_version` routing before schema/contract lookup. Suite groups validate child versions independently and aggregate summaries only.
7. Convert user-guide snippets to canonical executable samples and add a Maven release gate that builds, validates, runs supported local samples, reports, validates evidence, and verifies bundled registry resolution outside the repository cwd.

## Implementation Slices and Tests

| Slice | Primary code | Required tests |
| --- | --- | --- |
| Compiler model | `V03CompiledSuite`, `V03CompiledTestCase`, `V03ExecutionPlanBuilder`, `V03RuntimeExecutionService` | multi-test plan, immutable plan, validate/dry-run/run digest equivalence, no YAML reread |
| Typed contract model | Provider Contract schema/catalog, compiler validators | type/sensitivity/phase/runtime-mode positive and negative cases |
| Generated DAG | compiler dependency service and Env_Profile validation | ordering, missing producer, undeclared output, self-cycle, two-node/long cycle |
| Version router | `ContractBaselineService`, CLI routing | missing/unsupported/mixed leaf versions; mixed-version suite group aggregation |
| Docs and gate | user guide, samples, release scripts, release tests | clean checkout, outside-cwd registry resolution, jar/usage-kit sample matrix, report/evidence validation |

## Completion Gate

Run bounded Maven tests after each slice. The final gate is:

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q test
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q verify
scripts/ci/check-schema-drift.sh
scripts/ci/verify-contracts.sh
scripts/release/verify-v0-3-runtime-samples.sh
scripts/release/verify-usage-kit.sh
```

Completion requires all commands to pass, no runtime YAML reparse, one canonical plan per leaf invocation, typed Provider Contract enforcement, DAG cycle detection, strict routing, and executable release documentation.
