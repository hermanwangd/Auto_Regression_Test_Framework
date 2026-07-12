# DSL v0.3 Seven-Gap Closure Fix Plan

## Goal

Close only these known gaps: concrete generated producers, test scope, provider-check publication, assertion preflight, deterministic digests, target-binding compatibility, and longest-prefix dotted output resolution. Do not add providers, DSL syntax, Provider Adapter APIs, result/evidence schemas, or Product/RP/RU behavior. Update the v0.3 spec, architecture, acceptance criteria, user guide, and affected samples only to describe the fixed existing `generated://` lifecycle.

## Fixed Decisions

- `generated://target/output` remains the public Env_Profile target-binding reference syntax; it is not valid in DSL `with` values or assertion operands.
- This release supports only a `STEP` generated producer. There is no implicit target-startup or suite-startup producer. A framework-managed mock is started by an explicit preceding `setup` `PROVIDER_OPERATION` in the same test case.
- Every generated output is scoped to its `test_case_id`. Cross-test generated references are invalid.
- Only `PROVIDER_OPERATION` publishes to the generated-output registry. `PROVIDER_CHECK` may publish step-local output for a following `step://` assertion, but never for `generated://`; a provider operation must not consume a provider-check output.
- Runtime resolves a compiled producer identity; it must not scan targets or outputs from a raw generated ref.
- `generated://target/output` remains an Env_Profile target-binding value. Its binding is materialized immediately before each consumer step, after the compiled producer has run; it is not materialized during suite or target initialization.
- Existing mock-to-client samples must contain an explicit mock setup producer before the client consumer step. A shared target does not make generated output shared across test cases.

## Internal Model

Use typed keys instead of concatenated strings:

```java
record V03StepOutputKey(String testCaseId, String stepId, String outputPath) {}
record V03GeneratedOutputKey(String testCaseId, String target, String stepId, String outputPath) {}
record V03ResolvedOutputPath(
        String declaredPath,
        V03OutputDefinition definition,
        String remainingPath) {}
```

`V03ResolvedGeneratedProducer` contains the consumer test case, producer step/target/operation/output, resolved output definition, and generated registry key. Store it on the compiled target-binding use. The dependency list remains audit data, not the runtime lookup mechanism.

`V03ResolvedOutputPath` is the only output lookup result used by compiler, runtime materialization, and redaction. It first resolves an exact output. Otherwise it selects the longest declared dotted prefix and returns the unconsumed suffix. A suffix is valid only below an `OBJECT` or `ANY` output. If the suffix has no separately declared typed output, its effective value type is `ANY`; a typed consumer may not consume that unknown leaf, except where its contract explicitly accepts `ANY`.

## Patch Sequence

### 1. Unified Output Definition Resolver

Extend `V03OutputDefinitionResolver` to return `V03ResolvedOutputPath`: exact match first, then the longest declared dotted prefix. A nested suffix is valid only when that declared output is `OBJECT` or `ANY`. Reuse this resolver in compiler, runtime reference resolution, and redaction; no component may reimplement prefix splitting.

Tests: exact output, `response.body.id`, invalid scalar suffix, JSON Pointer after a resolved dotted output.

### 2. Scoped Step and Generated Registries

Replace raw output maps with separate framework-private registries. All provider operations and checks publish `V03StepOutputKey`; only provider operations publish `V03GeneratedOutputKey`. Do not expose either registry through the adapter-facing execution context.

Tests: a following assertion can consume provider-check `step://`; a provider operation consuming that output is rejected; provider-check `generated://` is rejected; TC2 cannot read TC1 output.

### 3. Compile Concrete Producer Identities

For each generated consumer, select exactly one same-test-case, preceding `PROVIDER_OPERATION` whose contract declares a bindable output. Zero producers returns `missing_generated_producer`; multiple producers returns `ambiguous_generated_producer`. Compile the resolved identity into the consumer.

Target bindings are compiled and materialized per consumer step, not once per target. This prevents a target shared by multiple tests from inheriting another test's producer and fixes materialization timing for Env_Profile values such as `base_url: generated://payment_mock/base_url`.

Tests: no producer, forward producer, duplicate producer, cross-test producer, and dry-run identity equals runtime identity.

### 4. Assertion Preflight

Compile assertion operands that use `step://` into `V03CompiledAssertionOperand`. Validate prior producer, contract output declaration, longest-prefix path, scope, sensitivity, and operator-compatible type. `generated://` in a DSL assertion is rejected as an invalid reference scope. `exists`/`not_exists` accept any resolved type; `equals`/`not_equals` require compatible operand types; `gt`/`gte`/`lt`/`lte` require `NUMBER`; `matches` requires `STRING`. Artifact and literal operands keep their existing validation path.

Tests: missing assertion output, invalid nested path, provider-check step output allowed only for an assertion, provider-check generated output rejected, and DSL generated reference scope rejected.

### 5. Target Binding Compatibility

Compile each target-binding use with its consumer step. Reject a literal, `env://`, `generated://`, or `secret_ref` whose reference kind is not allowed by the target binding definition. Validate literal values, generated producer type/sensitivity, and environment values. `env://` accepts `STRING` directly; `NUMBER` and `BOOLEAN` use strict parsing; `OBJECT` and `ARRAY` are rejected until an explicit contract mechanism exists. `secret_ref` validates reference form and a `SECRET` consumer sensitivity but never resolves a secret value at compile time.

Compatibility is explicit: equal types and consumer `ANY` are allowed. A longest-prefix object suffix without its own declaration is `ANY` and is accepted only by an `ANY` consumer. Sensitivity follows `PUBLIC < MASKED < SECRET`: a consumer may accept data at its own sensitivity or lower only. Thus `MASKED -> PUBLIC`, `SECRET -> MASKED`, and `SECRET -> PUBLIC` are rejected.

### 6. Canonical Plan Digest

Add `V03PlanCanonicalizer` to build a plain-data canonical tree. Maps use sorted keys, sets use sorted lists, authored test/step lists preserve order, and artifact paths are suite-relative. Include execution profile, targets/bindings, artifact roots, typed contract metadata, output publication/scope, compiled producer identities, steps, and dependencies. Serialize deterministic JSON and hash SHA-256 bytes. Prohibit `Map.toString()`, `Set.toString()`, absolute paths, and object identity. Only unordered map/set permutations preserve a digest; a test or step order change changes it.

Tests: map/set permutations share one digest; a target, contract, binding, test-order, or step-order semantic change changes it; 20 independent JVM runs return one digest.

### 7. Focused Closure Gate

Add `scripts/release/verify-v03-seven-gap.sh` with bounded JVM memory. It runs the seven negative/positive cases, 20-JVM digest verification, Java 17 `verify`, and existing v0.3 sample gates. The gate must create an isolated temporary output directory, assert the exact closure strings below, and delete that directory on success and failure.

## Definition of Done

```text
generated_without_producer_step=REJECTED
cross_test_generated_reference=REJECTED
provider_check_generated=REJECTED
assertion_missing_output=REJECTED
digest_variants_across_20_jvms=1
binding_type_mismatch=REJECTED
secret_to_public_binding=REJECTED
response.body.id=RESOLVED_BY_response.body
provider_check_step_output=ALLOWED
provider_operation_consumes_provider_check_output=REJECTED
generated_reference_in_dsl_assertion=REJECTED
dry_run_producer_identity=runtime_producer_identity
generated_env_profile_binding_materialized_per_consumer_step=ALLOWED
unsupported_binding_reference_kind=REJECTED
secret_to_masked_binding=REJECTED
step_order_digest_change=DETECTED
```

All existing positive and negative v0.3 samples, Java 17 `verify`, release gate, usage-kit verification, and secret scan must remain green.
