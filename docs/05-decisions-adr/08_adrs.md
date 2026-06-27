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

Domain-specific logic shall not be placed in the framework core. It must be implemented through package-type adapters, assertion types, fixture handlers, or package input generators.

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

- Framework Verification validates this framework product. `./mvnw test` runs unit and component tests through Maven Surefire. `./mvnw verify` runs framework integration tests through Maven Failsafe against a sample Product Repo fixture and local/mock adapters.
- RP Regression Execution validates a downstream Product Release Package. It is invoked through the framework CLI, for example `regress run --root <product-repo> --rp-id <rp-id> --env <mode>`.

Framework Verification must not depend on SIT/UAT deployment and must not claim downstream RP release evidence. RP Regression Execution may run in `local_fixture`, `ci_ephemeral`, `sit_deployed`, or `evidence_only` mode depending on the RP validation boundary. SIT/UAT regression is triggered by a release package pipeline after required RU versions are deployed; it is not part of Maven unit/component testing for this framework.

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

Use config-driven provider contracts as the heterogeneous execution boundary. The DSL remains package-neutral and references logical capabilities such as inputs, actions, fixtures, oracles, assertions, observations, and evidence. `rp_ru_mapping.yaml` declares RU membership, execution mode, deployment requirement, dependencies, and provider contracts. A provider capability registry validates `provider_family`, `provider_type`, required fields, supported runtime status, execution modes, safety policy, and evidence outputs before execution. Reusable built-in provider families implement concrete behavior for request/response APIs, messaging, DB fixtures, deployment readiness, and file/batch execution.

The selected M1 pilot shall exercise one heterogeneous RP that includes REST and/or gRPC, Kafka and/or NATS, DB fixture setup/cleanup, and K8s/VM readiness as needed by the RP boundary. External runner is not a required pilot capability. It is an approved escape hatch only when a legacy or specialized boundary cannot yet be represented by a reusable built-in provider contract.

### Consequences

Provider contract validation becomes part of the execution gate. New RP-specific needs should be handled by provider configuration first, reusable provider code second, and DSL changes only when a recurring cross-RP concept cannot be represented by the existing DSL. External runner use must be explicit, bounded, owner-approved, and evidenced; it must not become the standard way for each RP to develop its own test tool or script.
