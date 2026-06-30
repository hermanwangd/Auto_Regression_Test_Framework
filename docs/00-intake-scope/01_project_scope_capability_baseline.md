# 01. Project Scope & Capability Baseline

## 1.1 Background

Product developers need reliable auto regression coverage for release packages, but current regression assets are often fragmented across project-specific scripts, ad hoc fixtures, manual expected-result checks, and release notes. This creates three recurring problems:

- Regression coverage is hard to measure and usually incomplete.
- Release package behavior can change without a clear trace from RP acceptance criteria to test evidence.
- Developers spend too much effort rebuilding test runners, package input loaders, assertions, and reports per project.

This project defines a product spec-driven Release Package Regression Framework. Product is a virtual product boundary, while release is performed by package release. The Product Repo must therefore provide the initialized source-of-truth structure for product specs, release package membership, RP acceptance criteria, regression artifacts, evidence, monitoring, and change workflow.

```text
Product Spec and RP Acceptance Criteria
-> Agent-generated Test Artifacts and Expected Results
-> Release Package Regression Framework
-> Coverage and Evidence Package
-> Release Review Input
```

## 1.2 Target User

The first real user is the product developer responsible for implementing and maintaining release package changes.

The platform should help this user:

- Understand which RP acceptance criteria are covered by automated regression tests.
- Generate draft test cases and expected results from approved RP acceptance criteria.
- Run repeatable regression checks against the release package's declared inputs, behavior, outputs, and evidence requirements.
- Produce evidence that can be reviewed by QA, release owners, or project stakeholders.

## 1.3 Objective

- Improve auto regression test coverage for the first release package pilot to greater than 80%.
- Build Auto Regression Test Framework v0.2 as a complete product-agnostic pre-release execution and operability foundation, not a minimum MVP.
- Support local, CI, SIT, and preprod execution contexts through Execution Profiles, Environment Bindings, Provider Contracts, Provider Instances, provider/verify plugin contracts, provider capability registry, fixture lifecycle, verification catalog, result schema, and evidence collection.
- Treat local/CI mock, stub, Testcontainers-backed ephemeral DB/broker/service, fake-topic, embedded-broker, disposable-schema, and generated-data replacements as explicit runtime modes and dependency provisioning policy, not hidden fallback behavior.
- Allow the agent to generate expected results from RP acceptance criteria when the AC is explicit, reviewable, and traceable.
- Standardize test case DSL, parameter sets, fixture bindings, provider contracts, expected-result references, assertions, evidence, and coverage reporting.
- Make every generated test artifact traceable back to RP acceptance criteria and source specs.
- Keep release approval human-owned even when test creation is agent-assisted.

## 1.3.1 v0.2 Full Pre-release Positioning

Auto Regression Test Framework v0.2 is the first feature-complete pre-release framework contract. It is not yet stable v1.0, and breaking changes remain allowed before v1.0, but v0.2 must be complete enough for pilot project execution and Phase 2 Agent Skill integration.

```text
Auto Regression Test Framework v0.2
= Product-agnostic Execution + Operability Framework
```

Framework v0.2 owns:

- Test DSL schema validation.
- Execution Profile and Environment Binding resolution.
- Parameter expansion and variable resolution.
- Fixture setup, cleanup, and state safety.
- Provider execution through built-in Provider Contracts and provider plugin contracts.
- Verify/assertion execution, including DB, event, file, API, and batch checks.
- Eventual verification through polling.
- Evidence collection, masking, indexing, and attachment to result records.
- Standard result JSON generation.
- CLI, CI, suite, tag, profile, and test-id execution entrypoints.
- Provider and verify plugin contract validation.

Framework v0.2 does not own:

- RP/RU/product topology interpretation.
- Product mapping or topology translation.
- Deciding which RU should use `maven_failsafe`, `k8s_job`, REST, Kafka, NATS, JDBC, or any other runner.
- Agent-generated test-case drafting or expected-result drafting.
- Approval, waiver, release gate, Go/No-Go, or governance workflow decisions.
- Business-level failure triage.

The control boundary is:

| Layer | Responsibility |
|---|---|
| Product Docs / Release Docs | RP/RU mapping, specs, AC, architecture, release, deployment, and topology truth. |
| Phase 2 Agent Skill | Translate product context into framework-readable DSL, suite, Execution Profile, Environment Binding, Provider Contract, Provider Instance, traceability map, and mapping explanation artifacts. |
| Framework v0.2 | Execute generated artifacts, verify results, collect evidence, and produce standard results without interpreting product topology. |

## 1.4 Product, RP, and RU Role Model

| Level | Role | Owns | Does Not Own |
|---|---|---|---|
| Product | Virtual product boundary and development workflow grouping | Product intent, product specs, E2E scenarios, RP inventory, monitoring intent, change workflow | Release artifact or primary release AC |
| Release Package (RP) | Release artifact and development execution unit | RP scope, RP feature specs, RP acceptance criteria, RP/RU mapping, regression artifacts, evidence package, release readiness | Runtime implementation internals |
| Release Unit (RU) repo | Implementation workspace | Code, local tests, build artifacts, unit/component evidence | Primary product/RP specs or primary acceptance criteria |

Formal acceptance criteria are owned at the RP level. Product-level docs may define product intent and E2E scenarios, but they should not define the primary release acceptance denominator. RU repos provide implementation and evidence mapped back to RP-level AC.

## 1.5 Release Package Definition

A release package is a software package that includes one or more release units required to deliver product features.

For this framework, a release package must declare:

- Product feature scope and linked product spec context.
- RP-level acceptance criteria.
- Release units included in the package, such as services, jobs, data pipelines, schemas, configuration, scripts, or UI modules.
- Execution or validation boundary for each release unit.
- Inputs, expected behavior, outputs, and allowed side effects.
- Regression evidence required for release review.
- Environment assumptions, including test data, dependencies, and runtime constraints.

The v0.2 adoption proof uses one heterogeneous release package as the pilot. The selected RP should include request/response interaction, asynchronous messaging, DB fixture setup/cleanup, K8s and VM readiness, and provider capability registry validation. External runner is optional and allowed only as an approved escape hatch when a reusable built-in provider cannot safely represent a legacy or specialized boundary. Package-specific behavior remains outside the framework core.

v0.2 is delivered through two gates:

| Gate | Proves | Does Not Prove |
|---|---|---|
| Framework Verification | The framework can validate artifacts, block unsafe execution, run local/mock provider paths, write evidence, and calculate sample coverage through `./mvnw test` and `./mvnw verify`. | Real downstream RP release readiness or native technology certification. |
| Heterogeneous Pilot Validation | An owner-provided Product Repo and RP can achieve greater than 80% automatable RP AC coverage using the selected provider contracts. | Broad support for every package type or every native provider. |

Framework verification may use local mocks, stub endpoints, fake brokers, local JDBC fixtures, and command/API stubs to prove reusable provider behavior. Native Kafka, NATS, gRPC, K8s, VM, container, Maven Failsafe, and file provider runtimes are framework-verified where listed in the capability matrix, but downstream pilot acceptance still requires owner-provided RP artifacts, selected provider contracts, and real environment evidence.

## 1.6 Product Repo Definition

The Product Repo owns development workflow specs for the virtual product boundary and its package releases. It is not required to contain runtime implementation code. Runtime implementation remains in RU repos.

The Product Repo should contain the product spec lifecycle structure:

- `00-intake-scope/`: product scope, baseline, and release package inventory.
- `01-specs/`: product specs and RP feature specs.
- `02-architecture/`: product and RP architecture, boundaries, and sequence flows.
- `03-acceptance/`: RP-level acceptance criteria and product E2E scenarios.
- `04-planning/`: implementation and release planning.
- `05-decisions-adr/`: architecture and product decision records.
- `06-reviews/`: spec, PR, release, and evidence review checklists.
- `07-validation-evidence/`: traceability, regression evidence, coverage, and validation reports.
- `08-release/`: release package readiness, go/no-go, and release records.
- `09-operations/`: monitoring, runtime checks, runbooks, and operational feedback.
- `10-change-control/`: change requests, impact analysis, waivers, and workflow records.
- `99-archive/`: retired specs, superseded release packages, and historical evidence.

The Product Repo maps the virtual product boundary to package-release execution:

```text
Virtual Product
-> Product Spec and E2E Scenarios
-> Release Packages as release artifacts
-> RP/RU repo mapping
-> Regression Test Cases, Data, Expected Results
-> Evidence Package
```

Product specs should go down to the Release Package level. Release Packages are the release and regression evidence units. RU repos are referenced through each release package's RP/RU mapping; they are not a separate product spec level.

## 1.7 Release Package Artifact Contract

Each RP should define these minimum artifacts in the Product Repo:

- `package.yaml`: RP identity, scope, owner, lifecycle status, target release, and package type.
- `rp_feature_spec.md`: RP feature behavior, source context, and package-level behavior boundaries.
- `rp_ru_mapping.yaml`: product-owned release unit mapping used by Agent Skills, not by the framework core directly.
- `acceptance_criteria.md`: RP-level AC used for generation, regression coverage, and release readiness.
- `tests/`: versioned RP regression test cases, including draft, approved, and retired test artifacts.
- `expected-results/`: reviewable expected-result artifacts used by approved tests.
- `generated-framework/`: Agent-generated framework-readable artifacts such as `suite_manifest.yaml`, `run_plan.yaml`, `environment_bindings/*.yaml`, provider contracts, and `traceability_map.yaml`.
- `traceability.md`: mapping from product spec context to RP AC, generated tests, evidence, and review state.
- `evidence_index.md`: evidence package manifest for coverage reports, execution reports, logs, diffs, waivers, and approvals.

## 1.8 Product Mapping and Framework Artifact Boundary

`rp_ru_mapping.yaml` is product knowledge. Product owners, SA, tech leads, or product developers use it to declare which RUs belong to an RP, which versions matter, and which validation boundary is intended. The generic framework core must not interpret product topology or decide which RU should use which runner.

Each RP/RU mapping entry should include enough information for the Agent Skill to generate framework-readable artifacts:

| Field | Purpose |
|---|---|
| `rp_id` | Release package identifier |
| `ru_id` | Release unit identifier inside the RP |
| `repo` | Source repository or implementation workspace |
| `unit_type` | Service, job, data pipeline, schema, config, script, UI module, or other package unit type |
| `owner` | Developer or team accountable for implementation evidence |
| `version_ref` | Branch, tag, commit, build artifact, or version used by the RP |
| `validation_boundary` | What the Agent Skill should translate into logical framework targets and tests |
| `execution_mode` | Local fixture, CI ephemeral, SIT deployed, or evidence-only validation mode |
| `environment_ref` | Target environment, CI job, SIT namespace, endpoint set, or local fixture reference |
| `deployment_required` | Whether this RU must be deployed before RP regression execution |
| `provider_intent` | Candidate provider type or provider_id hint for Agent Skill translation |
| `evidence_responsibility` | Evidence this RU must produce or contribute |
| `dependency_order` | Product topology hint; generated `run_plan.yaml` must express executable target dependencies |

The Agent Skill translates product mapping into:

- `suite_manifest.yaml`: selected tests, trace labels, and coverage source refs.
- `run_plan.yaml`: logical target dependency graph, Execution Profile, and selected execution mode.
- `provider-instances/*.yaml`: RP logical runtime targets with provider_id and provider_type.
- `environment-bindings/*.yaml`: profile-specific values for Provider Instance binding keys.
- `provider-contracts/*.yaml`: reusable Provider Contracts that define provider_type, operations, allowed `bind_as` values, output refs, evidence outputs, and failure codes.
- `traceability_map.yaml`: opaque product/RP/RU/source labels used for reporting, not runtime branching.
- `mapping_explanation.md` or `.yaml`: strategy selection reason, selected provider_id/profile, source facts, unresolved assumptions, and validation warnings for human review.

## 1.8.1 Execution Environment Policy

RP regression execution may run in different modes. Product topology determines which mode is appropriate, but the Agent Skill records the selected mode in framework-readable Execution Profiles and Environment Bindings. The framework shall not infer deployment need from RP/RU names or product topology.

| Execution Mode | Use When | Deployment Ownership |
|---|---|---|
| `local` | RP behavior can be validated with local fixtures, files, mocks, or deterministic package inputs | No SIT deployment required |
| `ci` | Multiple RUs can be built or composed in CI using temporary services, containers, schemas, or jobs | CI pipeline provisions and tears down the environment |
| `sit` | RP behavior depends on deployed RU services, jobs, schemas, configuration, or endpoints in SIT | CD or environment owner deploys RUs before regression |
| `preprod` | RP behavior must run in a production-like environment with stricter approval and masking | Environment owner supplies readiness evidence and approved safety controls |

For RPs that include multiple RUs, the RP/RU mapping must declare enough topology for the Agent Skill to generate explicit target dependencies, execution mode, deployment readiness refs, Provider Instances, Environment Bindings, and evidence responsibilities.

CI/CD boundary:

- CI may run readiness checks, artifact validation, agent generation, local fixture tests, and CI integration tests.
- CD may deploy RU versions to SIT or another target environment.
- SIT regression may run only after the generated Environment Binding references readiness evidence for the selected deployed targets.
- The framework orchestrates regression execution and evidence collection, but it does not own CD deployment in v0.2.
- Evidence may retain RP/RU/version labels as opaque traceability metadata, but framework decisions must use generated target IDs, Provider Contracts, Provider Instances, Execution Profiles, and Environment Bindings.

## 1.9 AC Level and Coverage Policy

Formal AC are defined at RP level. Product-level E2E scenarios provide context and cross-RP confidence checks, but they are not the primary RP coverage denominator.

Pilot auto regression coverage is calculated as:

```text
covered automatable RP-level AC / total automatable RP-level AC in release package scope
```

Rules:

- Manual-only AC require explicit approval before exclusion from the denominator.
- Waived AC require waiver records and approval before exclusion from the denominator.
- Partially covered AC remain in the denominator with status `partial`, unless split into smaller AC and reclassified.
- Product E2E scenarios may map to one or more RP AC, but they do not replace RP AC.
- Release unit tests count as evidence only when mapped back to RP AC.

## 1.9.1 DSL and 7 AP Baseline Model

The regression DSL is the checked-in RP-level test contract. In one sentence: it is the durable description of what RP behavior is validated and how the framework shall execute and judge that validation repeatably. It states which RP behavior is validated, which AC it traces to, what input or state is needed, what logical action is performed, what oracle/assertion decides pass/fail, and what evidence must be retained.

The DSL is not a BDD-only story, package-specific script, provider configuration file, secret store, generated run log, or replacement for owner-authored RP AC. Provider-specific details such as endpoints, commands, DB loaders, queue clients, credentials, and implementation settings belong in Provider Contracts, Provider Instances, or Environment Bindings.

The 7 AP are framework processing areas. In one sentence: they are the lifecycle responsibility boundaries that convert reviewed RP artifacts into executable and reviewable regression evidence. An AP is not necessarily a separate Java package, deployable service, or RP-specific plugin.

The baseline ownership model is:

| Item | Owner | Purpose |
|---|---|---|
| RP feature spec and RP AC | Product owner, PM, SA, or RP owner | Define business behavior and acceptance truth. |
| RP/RU mapping | RP/RU owner or platform owner | Declare product topology and release boundary for Agent Skill translation. |
| Generated framework artifacts | Agent Skill with reviewer approval | Translate product topology into suite manifest, run plan, Execution Profiles, Provider Instances, Provider Contracts, Environment Bindings, and traceability map. |
| DSL test case | Product developer or agent-assisted reviewer | Preserve reviewed regression validation intent as a repeatable artifact. |
| Expected result or oracle truth | RP owner or delegated reviewer | Approve the truth source used by assertions. |
| 7 AP execution flow | Framework | Validate, plan, bind, execute, assert, and report without inventing business truth. |

| AP | Consumes | Produces | Clear Boundary |
|---|---|---|---|
| Definition and Validation | DSL files, suite manifest, run plan, Execution Profiles, Provider Instances, Environment Bindings, expected-result artifacts | Schema, lifecycle, approval, and compatibility findings | Validates framework-readable artifacts; does not infer business truth. |
| Discovery and Context | Suite manifest, run plan, Execution Profile, Provider Instances, Provider Contracts, Environment Binding, requested profile | Resolved execution context, target list, artifact paths, traceability labels | Finds declared context; does not understand product topology. |
| Planning and Binding | DSL tests, parameters, package inputs, Provider Contracts, Provider Instances, Environment Bindings | Execution plan with resolved bindings and step placeholders | Resolves logical references; does not execute package behavior. |
| Fixture and State Manager | Preconditions, fixture setup/cleanup, lifecycle policy | Prepared state, cleanup plan, cleanup evidence | Owns state safety; does not hide destructive actions. |
| Execution Engine | Execution plan, Provider Contracts, Provider Instances, Environment Binding policy | Step results, provider outputs, logs, runtime metadata | Executes configured actions; does not embed RP-specific logic. |
| Oracle and Assertion Engine | Actual outputs, approved truth sources, assertion rules | Pass/fail decisions with expected, actual, and reason | Evaluates evidence; does not approve truth. |
| Evidence and Reporting | Outputs from all APs, traceability, waiver records | Durable evidence, coverage, failure, and release-review reports | Reports readiness; does not decide release approval. |

Ownership split:

- RP owners define feature behavior, RP AC, RP/RU membership, and release decisions.
- Agent skills translate product topology into framework-readable artifacts and may draft DSL tests or expected results only from ready owner-authored context.
- The DSL owns validation intent and logical references.
- Generated run plans, Execution Profiles, Provider Instances, Environment Bindings, and Provider Contracts own executable configuration.
- The 7 AP own deterministic validation, planning, execution orchestration, assertion evaluation, and evidence production over generated framework-readable artifacts only.

## 1.10 Success Metrics

| Metric | M1 Target | Evidence |
|---|---:|---|
| Auto regression coverage | >80% of automatable RP-level AC in pilot RP scope | Coverage report mapped to RP AC IDs |
| Traceability | 100% of generated tests link to RP AC/source spec | Traceability matrix |
| Repeatability | Same input produces same result across repeated runs | Regression execution logs |
| Reviewability | Generated expected results can be inspected before merge | Artifact diff and review checklist |
| Failure visibility | Failed checks include reason, input, expected result, and actual result | Failure evidence report |

## 1.11 v0.2 Non-Functional Constraints

- Test execution should be deterministic for the same package inputs and expected results.
- v0.2 should support local, CI, SIT, and preprod execution contexts through Execution Profiles and Environment Bindings.
- Local and CI should normally replace external service, DB, messaging, K8s, and VM dependencies with explicit mock/stub/ephemeral bindings, including Testcontainers or equivalent provisioned dependencies when real protocol behavior matters; SIT/preprod release evidence should use native dependencies unless explicitly classified as framework verification only.
- SIT execution is required only when the RP validation boundary depends on deployed RU behavior that cannot be reproduced with local fixtures or CI-ephemeral composition.
- Fixture and sample data should be small enough to review and commit safely, or generated by a documented command.
- Production data must not be used unless masked and approved.
- Evidence artifacts should be retained with the RP release record.
- Test runs should avoid destructive operations unless explicitly approved.
- Local commands should remain bounded and avoid memory-heavy execution.
- Result and evidence artifacts must mask secrets and must not print raw credentials.

## 1.12 Non-goals

- Not replacing developer ownership of release package correctness.
- Not fully automating release approval.
- Not allowing agent-generated tests or expected results to merge without review.
- Not using production data without masking and approval.
- Not interpreting every release package type or product topology natively in the framework; Phase 2 Agent Skills translate product context into generic v0.2 artifacts.
- Not building a full dashboard before the pilot proves value.
- Not owning CD deployment orchestration in v0.2; the framework consumes deployment and environment readiness evidence when SIT is required.
- Not executing destructive operations without explicit approval.

## 1.13 Milestone Definition

Framework maturity is evaluated before Phase 2 Agent Skill maturity. The current stage must make the generic framework executable, verifiable, and contract-complete without requiring Product/RP/RU interpretation. Phase 2 Agent Skills may be improved in the next stage as long as the framework-owned contracts they will consume are stable enough to validate.

| Milestone | Meaning | Exit Criteria |
|---|---|---|
| v0.2 Full Pre-release Framework | Product-agnostic execution foundation is feature-complete enough for local/CI framework verification and later pilot project execution | DSL v0.2, Execution Profiles, Environment Bindings, Provider Contracts, Provider Instances, suite selection, provider capability registry, verify catalog, fixture lifecycle, polling, parameter expansion, result schema, evidence schema, CLI, secret guardrail, and plugin contracts are materialized, aligned, and verifiable |
| Phase 2 Agent Skill Integration | Agent translates Product/RP/RU context into framework-readable artifacts | Next-stage hardening; mapping explanation, strategy selection reason, generated suite/run/environment/provider artifacts, and test/expected-result drafts are reviewable after framework contracts are stable |
| v1.0 Stabilization | Framework contract becomes stable enough for wider adoption | Breaking changes are controlled; compatibility and migration policy are accepted |
| Governance / Dashboard | Regression output supports release review and waiver decisions | P1/P2/P3 policy, approval flow, waiver record, Go/No-Go report, and dashboard views |

## 1.13.1 Current Acceptance Boundary

Current framework verification can be accepted only for the framework capabilities covered by Maven tests and sample Product Repo fixtures. It is not enough to declare v0.2 delivery, M1 heterogeneous pilot validation, or downstream RP release evidence accepted.

| Area | Current Framework Status | Pilot Acceptance Requirement |
|---|---|---|
| File/batch execution | Supported through bounded shell/file provider contracts. | Use when selected RP has file, CLI, batch, or pipeline-style validation. |
| REST/gRPC request/response | Supported through configurable REST contracts and native descriptor-driven gRPC unary contracts. | Pilot must prove selected endpoint behavior against owner-provided RP artifacts and environment refs. |
| Messaging | Supported through local/mock messaging plus native Kafka/NATS publish, consume/observe, and bounded cleanup drain contracts. | Pilot must prove selected broker behavior against owner-provided Kafka/NATS environment refs. |
| DB fixture | Supported through JDBC setup, verification query, cleanup SQL refs, cleanup strategy, isolation key evidence, and DB row count assertions. | Pilot must prove state isolation and cleanup on real selected DB fixture boundary. |
| Deployment readiness | Supported through local/mock readiness plus native K8s and VM bounded readiness probes, K8s direct API availability, pod log capture, and VM SSH/WinRM command probes. | Pilot must prove selected K8s/VM readiness against owner-provided environments. |
| External runner | Supported as an approved escape hatch with approval metadata, timeout, inputs, outputs, evidence map, and mapped-artifact checks. | Use only when built-in providers cannot safely represent the selected legacy or specialized boundary. |

## 1.14 v0.2 Capability Baseline

| Capability ID | Capability | v0.2 Scope | Owner | Acceptance Evidence |
|---|---|---|---|---|
| CAP-001 | Test Case DSL v0.2 | Metadata, tags, labels, source refs, compatible profiles, parameters, targets, setup, execute, expected results, verify, evidence, runtime | Platform | Schema validation and DSL contract tests |
| CAP-002 | Execution Profile | `local`, `ci`, `sit`, and `preprod` profiles with isolation, dependency, duration, data, mock, readiness, and destructive-operation constraints | Platform | Execution Profile validation and selected-profile blocking tests |
| CAP-003 | Environment Binding | Logical targets resolved to local process, testcontainer, deployed runtime, shared infrastructure, file store, or secret/runtime-generated references | Platform | Environment binding validation and resolution evidence |
| CAP-004 | Suite Selection | Execute by test ID, suite, tag, and profile | Platform | CLI selection tests and suite manifest validation |
| CAP-005 | Parameter Expansion | Operation-level `parameters[].ref` and `parameters[].bind_as` with per-parameter result and evidence folders | Platform | Parameterized run evidence and coverage de-duplication |
| CAP-006 | Fixture Manager | Database, file, mock, message, container dependency, environment variable, and test-data namespace fixtures with scope and cleanup policy | Platform | Setup/cleanup evidence and unsafe cleanup blocking |
| CAP-007 | Provider Interface and Catalog | Provider types such as `shell_command`, `rest_client`, `grpc_client`, `jdbc_database`, `nats`, `kafka_messaging`, `kubernetes_runtime`, `vm_runtime`, and approved `external_runner` Provider Contracts | Platform | Provider catalog and plugin metadata validation |
| CAP-008 | Execute Operation Catalog | `run_batch`, `execute_command`, `call_api`, `execute_sql`, `publish_message`, `consume_message`, `run_application`, `run_container`, `run_k8s_job`, `run_maven_failsafe`, `read_file`, `write_file` | Platform | Operation dispatch and unsupported-operation blocking |
| CAP-009 | Verify / Assertion Catalog | Basic, structure, collection, numeric/time, file, state, event, and custom verify types | Platform | Verify engine tests and verify plugin metadata validation |
| CAP-010 | State / Event Polling | DB and event verification polling with bounded timeout, poll interval, and retained final observation evidence | Platform | Polling pass/fail/timeout evidence |
| CAP-011 | Expected Result Handling | `literal`, `file`, `json`, `yaml`, `csv`, `schema`, `db_snapshot`, and `event_payload` truth artifacts | Platform / Reviewer | Expected-result resolution and approval-gate tests |
| CAP-012 | Evidence Collector | Execution logs, actual/expected artifacts, assertion diffs, DB query results, event payloads, HTTP request/response, screenshots, provider reports, fixture logs, cleanup logs | Platform | Evidence index, masking, and attachment to result |
| CAP-013 | Result Schema | Standard result JSON with framework/dsl version, profile, environment, labels, steps, verify results, evidence, failure classification, and timing | Platform | Result schema validation |
| CAP-014 | Secret Guardrail | Block raw secrets in DSL, Execution Profiles, Environment Bindings, result, and evidence; allow secret refs and runtime-generated values | Platform / Security | Secret validation tests and evidence masking checks |
| CAP-015 | Plugin Contracts | Provider and verify plugin metadata validated at startup or preflight | Platform | Catalog/plugin compatibility checks |
| CAP-016 | Technical Failure Classification | `schema_error`, `target_resolution_error`, `fixture_setup_error`, `execution_error`, `verification_failed`, `timeout`, `environment_error`, `secret_resolution_error`, `cleanup_error`, `framework_error` | Platform | Failed/blocked result classification tests |
| CAP-017 | Product Mapping Translation | Product/RP/RU interpretation and strategy selection into framework-readable artifacts | Phase 2 Agent Skill | Mapping explanation and generated artifact review |
| CAP-018 | Test and Expected-Result Drafting | Agent-assisted draft test cases and expected results from owner-authored AC | Phase 2 Agent Skill | Readiness report and draft artifact review |
| CAP-019 | Governance / Release Gate | Approval, waiver, Go/No-Go workflow, dashboard, business triage | Later Governance Layer | Release decision artifacts |

CAP-001 through CAP-016 are framework v0.2 responsibilities. CAP-017 and CAP-018 are Phase 2 Agent Skill responsibilities that consume product truth and generate framework-readable artifacts. CAP-019 is explicitly outside v0.2 framework runtime scope.

Current-stage framework maturity is judged only by CAP-001 through CAP-016. CAP-017 and CAP-018 remain required for end-to-end Product/RP adoption, but they are not blockers for raising framework implementation maturity in the current stage.

## 1.15 AC Readiness and Fallback Policy

The agent may generate expected results only when the related RP acceptance criteria define observable inputs, behavior, and outputs.

When an AC is ambiguous or incomplete:

- The framework should mark it as `not_ready_for_generation`.
- The agent may generate a test skeleton, but not an expected result.
- A product developer must either clarify the AC, provide a manual expected result, or classify the AC as manual-only or waived.
- Manual-only and waived ACs require explicit approval before exclusion from coverage calculation.

## 1.16 Test Case Lifecycle and Storage Policy

Generated test cases are durable RP artifacts. The framework shall not regenerate test cases on every execution run.

Rules:

- Test generation is an explicit action triggered by new or changed RP AC, changed execution context, missing coverage, or owner request.
- Execution runs read checked-in test cases from the RP `tests/` folder.
- Draft test cases may be stored under `tests/draft/` until reviewed.
- Approved executable test cases should be stored under `tests/approved/` and reused across repeated runs.
- Superseded test cases should move to `tests/retired/` or be marked `retired` with replacement links.
- Each checked-in test case must reference `source_refs.acceptance_criteria`, test case ID, source artifact version, expected-result reference when needed, optional report labels, and artifact status.
- Regeneration must preserve history by creating a new revision or replacement test case instead of silently overwriting approved tests.
- If an RP has a dedicated RP repository, the same `tests/` lifecycle applies there. In the current Product Repo model, the RP folder under `docs/08-release/release-packages/<rp_id>/` is the RP artifact location.

## 1.17 Key Assumptions to Validate

- RP acceptance criteria are explicit enough for the agent to derive expected results without inventing business rules.
- Product developers will trust generated artifacts if diffs, traceability, and review controls are clear.
- The first heterogeneous release package can reach greater than 80% meaningful regression coverage with a reusable provider contract model and a bounded provider set.
- The first pilot has stable sample data or masked data that can be committed or regenerated safely.
- Coverage measured by RP AC mapping is a useful proxy for regression confidence.

## 1.18 Not Doing Yet

- UI regression support: excluded from v0.2 unless a runner/verify plugin is explicitly added later.
- Multi-project dashboard: deferred until the first pilot proves repeatable evidence value.
- Fully automated release decisions: excluded because release truth remains human-owned.
- Broad provider marketplace: deferred until the Provider Contract / Provider Instance pattern stabilizes.
- Agent-only golden baseline approval: excluded because expected results must remain reviewable and auditable.
