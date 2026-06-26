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
