# 08. Architecture Decision Records

## ADR-001 Use Product Repo and RP-level AC as Generation Source

### Status

Accepted

### Context

Agent-generated regression tests need a reliable source of truth. Product-level docs provide intent and E2E context, but formal generation, coverage, and evidence must trace to RP-level feature specs and RP acceptance criteria.

### Decision

Use Product Repo RP artifacts as the source of truth for regression generation: `rp_feature_spec.md`, `acceptance_criteria.md`, `rp_ru_mapping.yaml`, package inputs, expected-result artifacts, traceability, and evidence records.

### Consequences

RP artifact quality becomes critical. Readiness checks are required before generation or execution.

---

## ADR-002 Use Metadata-driven Test Artifacts Instead of Direct Test Code Generation

### Status

Accepted

### Context

If the agent directly writes project-specific test code, output style and maintainability will vary by project.

### Decision

Agent shall generate standardized artifacts, such as readiness reports, YAML test cases, package input references, expected-result drafts, assertion references, and traceability records.

### Consequences

A common test case DSL, package input contract, expected-result artifact contract, and traceability contract are required.

---

## ADR-003 Keep Framework Core Generic

### Status

Accepted

### Context

The platform should support multiple projects and domains.

### Decision

Domain-specific logic shall not be placed in the framework core. It must be implemented through reusable providers, assertion types, fixture handlers, or package input generators.

### Consequences

Plugin boundaries must be enforced.

---

## ADR-004 Require Human Approval for Expected Results and Release Review Decisions

### Status

Accepted

### Context

Agent may accidentally encode incorrect system behavior as expected behavior.

### Decision

Human approval is required before generated expected results become `approved_for_regression`. Waivers, manual-only exclusions, and final Go / No-Go decisions remain human-owned.

### Consequences

Approval records are required before generated expected results are used as regression truth or before exclusions affect coverage and release review.

---

## ADR-005 Separate Framework Verification from RP Regression Execution

### Status

Accepted

### Context

The phrase "test execution" can mean two different things in this repository: testing the regression framework itself, or using the framework to test a downstream Product Release Package. Mixing these meanings causes confusion about Maven lifecycle, CI responsibilities, SIT/UAT dependencies, and what evidence can be used for release review.

### Decision

Use two explicit execution lines:

- Framework Verification validates this framework product. `./mvnw test` runs unit and component tests through Maven Surefire. `./mvnw verify` runs framework integration tests through Maven Failsafe against sample generated artifacts and local/mock providers.
- RP Regression Execution validates a downstream Product Release Package. Product-aware tooling first generates framework-readable artifacts under the RP folder, then invokes the generic runner, for example `regress run --root <product-repo> --rp-id <rp-id> --env <mode>`.

Framework Verification must not depend on SIT/UAT deployment and must not claim downstream RP release evidence. RP Regression Execution may run in `local`, `ci`, `sit`, or `preprod` mode depending on the selected Execution Profile. SIT/preprod regression is triggered by a release package pipeline after required product targets are deployed; it is not part of Maven unit/component testing for this framework.

The Test Plan belongs under validation evidence and defines verification strategy. The Implementation Plan remains under planning and defines the work required to implement or extend that strategy.

### Consequences

CI can run fast framework checks with `./mvnw test`, deeper framework integration checks with `./mvnw verify`, and product-specific RP regression through release package pipelines. Sample Product Repo fixtures can prove framework behavior, but only real Product Repo RP evidence can support downstream release review.

---

## ADR-006 Use Provider Contracts for Heterogeneous RP Support

### Status

Accepted

### Context

Different Products, Release Packages, and Release Units may use different implementation languages, deployment models, and communication technologies, including Java, Go, C++, VB.NET, K8s, VM, REST, Kafka, NATS, gRPC, and Orbix. If the DSL or framework core directly models every technology, the framework will become brittle and every new RP will require core changes.

### Decision

Use framework-owned Provider Contracts, Provider Instances, and Environment Bindings as the heterogeneous execution boundary. The DSL remains package-neutral and references logical capabilities such as inputs, actions, fixtures, expected results, verify rules, observations, and evidence through `provider_id`, operation, operation `inputs`, and output refs. The selected profile is supplied by CLI or suite manifest, not by each DSL target. Product-owned `rp_ru_mapping.yaml` declares RU membership, execution mode, deployment requirement, dependencies, and provider intent for Agent Skill translation. The framework runtime consumes generated `suite_manifest.yaml`, `run_plan.yaml`, Execution Profiles, Provider Instances, Environment Bindings, and `traceability_map.yaml`, then resolves Provider Contracts from the framework catalog by `provider_type` unless an explicit custom or snapshot contract mode is declared. A provider capability registry validates `provider_type`, required binding keys, supported runtime status, execution modes, safety policy, and evidence outputs before execution. Reusable built-in provider types implement concrete behavior for request/response APIs, messaging, DB fixtures, deployment readiness, and file/batch execution.

The selected M1 pilot shall exercise one heterogeneous RP that includes REST and/or gRPC, Kafka and/or NATS, DB fixture setup/cleanup, and K8s/VM readiness as needed by the RP boundary. External runner is not a required pilot capability. It is an approved escape hatch only when a legacy or specialized boundary cannot yet be represented by a reusable built-in provider contract.

### Consequences

Framework Provider Contract catalog validation becomes part of the execution gate. New RP-specific needs should be handled by Agent Skill translation and provider configuration first, reusable provider code second, and DSL changes only when a recurring cross-RP concept cannot be represented by the existing DSL. External runner use must be explicit, bounded, owner-approved, and evidenced; it must not become the standard way for each RP to develop its own test tool or script.

---

## ADR-007 Replace Inline Parameter Cases with Parameter Set References

### Status

Accepted

### Context

The first execution-focused DSL v1 parameter model used inline `parameters.strategy: explicit_cases` with embedded `parameters.cases[].values`. That made simple framework verification easy, but it pushed reviewed test data directly into the test case body and made reusable parameter catalogs harder to govern, share, and update across RP tests.

The DSL needs a stable parameter contract that remains easy for agents to generate, lets product developers and QA review data separately from execution semantics, and avoids regenerating every test case when only parameter rows change.

### Decision

Replace the inline parameter-case syntax in new execution-focused DSL artifacts with operation `inputs` that point to reviewed data refs:

```yaml
data:
  orders_case:
    ref: parameter-sets/orders_regression_cases.yaml#happy_path
execute:
  operations:
    - id: submit_order
      target: order_api
      operation: http_request
      inputs:
        request.body:
          ref: ${data.orders_case}
```

The referenced parameter set artifact owns one or more reviewed named cases. The runtime expands those cases into separate runs, records `parameter_case_id`, and keeps coverage counted once for the traced AC per selected batch.

Inline `parameters.strategy`, inline `parameters.cases`, and `${parameters.<name>}` references are legacy-only compatibility inputs until migrated. They are not the new v1 core syntax.

### Consequences

Parameter data becomes reusable and separately reviewable, while DSL test cases stay focused on execution semantics. Binding, validation, evidence, report, and generator work must migrate from inline case expansion to parameter set resolution. Existing inline explicit-case tests require either migration or an explicit compatibility path during transition.

---

## ADR-008 Keep Framework Core Product-Topology Agnostic

### Status

Accepted

### Context

Earlier design drafts allowed the framework to consume `rp_ru_mapping.yaml` directly for RP/RU membership, RU dependency order, provider ownership, execution mode, and environment topology. That would make the runtime understand product-specific release topology and would force new framework logic whenever a Product, Release Package, Release Unit, language, deployment model, or SIT topology changes.

### Decision

Separate responsibilities into three layers:

- Product/RP/RU docs own product knowledge: product specs, RP feature specs, RP AC, RP/RU mapping, release manifest, deployment manifest, and SIT topology.
- Phase 2 Agent Skills interpret product knowledge and generate framework-readable artifacts: `suite_manifest.yaml`, `run_plan.yaml`, `environment_binding.yaml`, provider contracts, DSL tests, expected results, and `traceability_map.yaml`.
- Generic Test Framework core validates and executes only framework-readable artifacts. It may preserve RP/RU labels as opaque traceability metadata, but it must not decide RP membership, RU technology, runner eligibility, release manifest semantics, or SIT topology.

Framework validation covers generic contracts: DSL syntax, target IDs, runner/provider existence, fixture refs, expected-result refs, verify rules, evidence refs, timeout/retry, secret-safety rules, and generated artifact consistency. Agent Skill validation covers product mapping: which RUs are in an RP, whether a RU can use Maven Failsafe or another runner, which topology targets exist in SIT, and whether generated targets match release context.

### Consequences

F004 is a Product Mapping Translation feature, not a framework-core RP/RU mapping validator. AC-004 validates generated framework artifacts and product-side translation gaps separately. Runtime implementation must move from RP/RU-aware discovery to suite/run/environment artifact discovery. Existing RP-oriented CLI commands may remain as product-aware wrappers only if they generate or locate the framework-readable artifacts before invoking the generic runner.

---

## ADR-009 Use Labels and Source References for Generic DSL Metadata

### Status

Accepted

### Context

Execution-focused DSL v1 originally used `traceability.package_id`, `traceability.acceptance_criteria_id`, and `traceability.source` as required runtime fields. After separating product topology from framework runtime, those names are too product-specific for the generic execution core and invite runtime logic to depend on Product/RP/RU semantics.

### Decision

New generated DSL artifacts shall use:

```yaml
labels:
  product: ORDER
  package: RP-ORDER-PLATFORM
  runtime_unit: RU-normalization-pipeline

source_refs:
  acceptance_criteria: docs/acceptance_criteria.md#AC-001
  feature_spec: docs/feature_spec.md#F001
```

`source_refs.acceptance_criteria` is required for reviewed-source traceability. `labels` are optional opaque report metadata. The framework may copy labels into evidence and reports, but it must not branch on labels to select runners, providers, topology, or execution strategy.

`traceability.package_id`, `traceability.acceptance_criteria_id`, and `traceability.source` are compatibility inputs until migrated. They map to `labels.package`, `source_refs.acceptance_criteria`, and source refs respectively.

### Consequences

DSL test cases become more product-agnostic. Coverage and release evidence packaging must resolve product-specific reporting through `source_refs`, `labels`, and generated `traceability_map.yaml`. Generator, validator, run evidence, report, and compatibility tests must migrate away from required `traceability.*` fields for new artifacts.

## ADR-010 Define v0.2 as Full Pre-release Execution Framework

### Status

Accepted

### Context

Earlier planning used M1 and DSL v1 language to describe a minimum executable slice. That framing is too small for the first usable framework contract. The framework must be complete enough to support local, CI, and SIT execution, runner and verify plugin contracts, fixtures, polling, evidence, result schema, suite selection, and secret guardrails before Phase 2 Agent Skill integration can be useful.

At the same time, the framework must remain product-topology agnostic. Product/RP/RU mapping, topology translation, and strategy selection are product interpretation responsibilities and belong to Product Docs plus Phase 2 Agent Skill output artifacts.

### Decision

Auto Regression Test Framework v0.2 is the first feature-complete pre-release execution and operability contract. It is not a minimum MVP and not yet stable v1.0.

Framework v0.2 owns:

- DSL v0.2 schema.
- Run profile and environment binding schemas.
- Suite selection by test, suite, tag, and profile.
- Parameter expansion.
- Fixture setup and cleanup lifecycle.
- Runner interface and runner catalog: `cli`, `http`, `jdbc`, `nats`, `kafka`, `file`, `container`, `maven_failsafe`, and `k8s_job`.
- Execute operation catalog.
- Verify/assertion catalog, including DB/event polling.
- Expected-result artifact references.
- Evidence collection and masking.
- Standard result JSON and technical failure classification.
- Secret guardrails.
- Runner and verify plugin contracts.

Framework v0.2 does not own:

- RP/RU/product topology interpretation.
- Strategy selection from product docs.
- Test-case generation or expected-result generation.
- Approval, waiver, release gate, dashboard, or governance workflow.
- Business-level failure triage.

### Consequences

v0.2 implementation and verification must be judged against AC-001 through AC-018 in the acceptance criteria document. Any implementation slice that changes DSL/runtime behavior must update baseline, spec, artifact contracts, acceptance criteria, implementation plan, and framework verification test plan before code work proceeds.

The Phase 2 Agent Skill will translate Product/RP/RU context into v0.2 framework-readable artifacts and must record mapping explanation, selected runner/profile, source facts, unresolved assumptions, and validation warnings.

---

## ADR-011 Prioritize Framework Maturity Before Phase 2 Agent Skill Hardening

### Status

Accepted

### Context

Architecture and test-plan review found that the framework contracts, module ownership, plugin contract materialization, fixture cleanup semantics, and AC-to-test evidence gates must be stronger before implementation can safely expand. Phase 2 Agent Skills are still important, but their maturity depends on stable framework-readable contracts.

### Decision

Raise current-stage maturity for the product-agnostic framework first. Framework v0.2 maturity is judged by AC-001 through AC-018, contract artifacts, provider capability registry, DSL validation, execution, verification, result JSON, evidence, reporting, and Maven framework verification. Phase 2 Agent Skill support for F001 through F006 remains next-stage and is judged by SUP-AC-001 through SUP-AC-006.

### Consequences

Implementation should focus first on framework contract hardening and framework verification gates. Agent Skill translation, RU repo scanning, test drafting, and expected-result drafting can proceed after the framework contracts are stable, and they must not be used to compensate for missing framework runtime contracts.

---

## ADR-012 Document Framework Public Interface Before Runtime Tests

### Status

Accepted

### Context

Runtime implementation and test planning were previously driven by a mix of CLI examples, DSL examples, artifact contracts, provider settings, and support commands. That makes it easy for tests to validate accidental command names, DSL fields, provider fields, output keys, or evidence paths instead of the intended framework interface.

### Decision

Define `docs/02-architecture/contracts/framework_usage_interface.v0.2.md` as the current-stage public interface contract before expanding runtime/provider implementation or test plans. The documented v0.2 public interface has controlled breaking changes allowed before v1.0 and includes invocation commands, required options, optional options, exit-code semantics, stable stdout keys, DSL/test definition fields, Execution Profile fields, Provider Contract fields, Provider Instance fields, Environment Binding fields, result/evidence schemas, input artifact locations, output evidence paths, and the boundary between framework runtime commands and next-stage Phase 2 support commands.

### Consequences

Framework verification must add interface contract tests before provider/runtime tests can claim maturity. Future command, option, DSL field, Provider Contract field, Provider Instance field, Environment Binding field, output-key, or evidence-path changes must update the interface contract and test plan first. Phase 2 Agent Skills may wrap framework commands and generate framework-readable artifacts, but they must not redefine the framework interface.

---

## ADR-013 Use Provider Contract and Provider Instance as Runtime Public Interface

### Status

Accepted

### Context

Earlier v0.2 drafts mixed public runtime names such as provider, runner, family labels, generated environment target, and implementation-hook terminology. That made the framework boundary unclear and encouraged each RP to think in terms of implementation hooks instead of stable framework-readable contracts.

The user-facing runtime interface must stay product-agnostic and must not expose implementation naming. Product/RP/RU topology is owned by Product Docs and Phase 2 Agent Skills, while the framework needs a stable execution model that can validate heterogeneous REST, gRPC, Kafka, NATS, DB, K8s, VM, file/batch, and external runner boundaries.

### Decision

Use this public runtime resolution model:

```text
DSL target
  -> provider_id
  -> Provider Instance
  -> provider_type
  -> Framework built-in Provider Contract catalog
  -> selected profile
  -> Environment Binding
```

Provider Contract defines provider_type, allowed operations, allowed input keys, required inputs, binding keys, output refs, evidence outputs, failure codes, defaults, and valid Provider Instance shape. Built-in Provider Contracts are owned by the framework and resolved by `provider_type`; suite-local contracts are allowed only for explicit custom provider or snapshot pinning mode.

Provider Instance defines one RP logical runtime target with provider_id and provider_type using the same top-level shape as the Provider Contract.

Environment Binding supplies profile-specific actual values such as URLs, topics, DB strings, namespaces, host refs, and secret refs.

DSL Test Cases reference only `provider_id` for runtime targets. The active profile is selected by CLI or suite manifest. Test cases must not contain endpoint, topic, DB credential, namespace, or secret values.

Remove implementation-hook terminology from user-facing docs. Any legacy internal package names are implementation details only and are not public runtime contracts.

### Consequences

Feature/spec, architecture, AC, test plan, user guide, and contract artifacts must use Provider Contract, Provider Instance, Environment Binding, Execution Profile, DSL Test Case, CLI, and Evidence Contract consistently.

Provider validation must fail before execution when Provider Instance, framework Provider Contract catalog entry, explicit custom Provider Contract, Environment Binding, required binding key, allowed operation, required input, allowed input key, or output ref is missing or invalid.

## ADR-014 Use Data Catalog and Operation Inputs for DSL Provider Binding

### Status

Accepted for next v0.2 interface migration; runtime implementation pending.

### Context

The current DSL exposes provider binding through `data_binding`, lifecycle-specific data categories, inconsistent lifecycle operation shapes, and old parameter binding arrays. This works mechanically but creates inconsistent user-facing semantics:

- `data_binding` is named like a binding point, but it is only a reviewed data reference catalog.
- `input_data`, `setup_data`, `cleanup_data`, and `expect_data` force data to be categorized by lifecycle even when the same data is reused across setup, execute, verify, and cleanup.
- Provider-backed setup, execute, verify, and cleanup use different shapes even though all dispatch through Provider Contract operations.
- Old parameter name and binding fields duplicate naming; the real public contract is the provider input name.
- Old Provider Contract binding field names describe input names, not general parameters.

### Decision

Adopt a cleaner DSL model for the next v0.2 implementation slice:

```text
data     = optional reusable data source catalog
inputs   = operation input bindings
operation = Provider Contract action
```

The DSL shall use:

- `data.<data_id>.ref` or `data.<data_id>.value` for optional reusable data sources.
- `setup.operations[]`, `execute.operations[]`, and `cleanup.operations[]` for provider-backed lifecycle actions.
- `verify.checks[]` for verification. Provider-backed checks use `target`, `operation`, and `inputs`; framework assertions may use `type`.
- `inputs` maps where the key is the provider input name and the value supplies `ref` or safe literal `value`.
- Structured input maps for domain concepts such as JDBC `bind_variables`.

Provider Contracts shall migrate to:

```yaml
allowed_inputs: [...]
required_inputs: [...]
```

The legacy parameter binding array shape remains compatibility-only until the implementation migration is complete.

### Consequences

This reduces DSL authoring complexity and makes provider use consistent across setup, execute, verify, and cleanup. The implementation must add compatibility validation, sample migration, provider input resolution, and contract tests before the new shape can replace the current runtime syntax. Existing checked-in executable samples may remain in the old shape until the migration slice updates runtime code and samples together.

Dry-run must produce a resolved execution plan without executing real operations. Evidence must include provider_id, provider_type, profile, and resolved operation result.
