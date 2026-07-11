# v0.3.0 Contract Compiler Hardening Plan

## Goal

Complete the four remaining v0.3 semantic-contract foundations before contract freeze: typed Provider Contracts, output redaction, one canonical execution plan, and a complete binding dependency compiler. Do not add providers, DSL features, product/RP/RU behavior, dashboards, or external reporting integrations.

## Public Invariants

- `V03ExecutionPlan` is the sole runtime input after validation. It contains suite metadata, profile/evidence metadata, artifact roots, targets, test cases, ordered steps, dependency graph, and digest.
- Every test document becomes one compiled test case, including an empty-phase test case.
- A step has an explicit `kind`: `provider_operation` or `assertion`; blank target is never a discriminator.
- Provider Contract inputs and outputs declare type, allowed reference kinds, sensitivity, bindability, evidence behavior, and failure codes.
- No sensitive output reaches execution context, result JSON, report, or evidence unmasked.
- Compiler routing is determined only by the validated leaf manifest version. It compiles Env_Profile, generated refs, `env://`, and step inputs before runtime dispatch.

## Delivery Slices

### 1. Typed Provider Contract Model

Replace string-only operation metadata with typed input/output definitions. Migrate bundled v0.3 YAML and schemas. Define value types, reference kinds, sensitivity (`public`, `masked`, `secret`), `bindable`, evidence inclusion, and runtime-mode applicability. Add positive and negative catalog tests.

### 2. Canonical Plan and Explicit Steps

Move all fields required by dry-run and runtime into immutable `V03ExecutionPlan`; remove runtime reliance on a second compiled object. Add `V03ExecutionStepKind`, preserve empty tests, and make validate, dry-run, and run emit/consume the same digest. Test immutable plan shape, empty tests, and digest equivalence.

### 3. Binding Dependency Compiler

Compile target bindings and step inputs into typed dependency nodes. Validate producer target/step/output existence, output bindability, input reference-kind/type compatibility, sensitivity flow, missing/blank `env://`, self references, and cycles. Topologically order framework-managed producers before dependent consumers. Route leaf DSL version exactly before graph construction.

### 4. Redaction Boundary

Materialize public values only after dependency validation. Apply contract-driven redaction before storing step outputs, provider results, resolved operation results, evidence payloads, diagnostics, and reports. Fail closed when a `secret` value is requested by an unsafe sink. Add leak regression tests for tokens, passwords, JDBC URLs, Authorization headers, and nested payloads.

## Acceptance Tests

- Typed contract rejects invalid input type, ref kind, runtime mode, undeclared output, and invalid sensitivity declaration.
- Empty test appears in plan/result/summary with deterministic status.
- Assertion and provider operation steps have explicit kinds.
- Dry-run and runtime use the same plan digest and preserve step order.
- Missing producer, undeclared output, incompatible types, unsafe sensitivity flow, missing env value, and cycles block before provider runtime.
- Sensitive output is masked in result, evidence, report, and error text; raw-secret scans remain zero.
- v0.2 compatibility tests retain their existing routing behavior.

## Verification Gate

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q test
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q verify
scripts/ci/check-schema-drift.sh
scripts/ci/verify-contracts.sh
scripts/release/verify-v0-3-release-gate.sh
```

The branch is ready to merge only when all four slices pass these gates and no raw output map crosses the typed contract/redaction boundary.
