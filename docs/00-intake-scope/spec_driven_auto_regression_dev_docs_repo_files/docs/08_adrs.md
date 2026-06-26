# 08. Architecture Decision Records

## ADR-001 Use Spec-driven Generation

### Status

Proposed

### Context

Agent-generated regression tests need a reliable source of truth. Existing specs already describe feature behavior, architecture, acceptance criteria, API/data model, and test plan.

### Decision

Use project specs as the source of truth for regression test generation.

### Consequences

Spec quality becomes critical. A spec readiness gate is required.

---

## ADR-002 Use Metadata-driven Test Artifacts Instead of Direct Test Code Generation

### Status

Proposed

### Context

If the agent directly writes project-specific test code, output style and maintainability will vary by project.

### Decision

Agent shall generate standardized test artifacts, such as YAML test cases, data catalogs, input data, expected data, and assertion configuration.

### Consequences

A common test case DSL and data catalog schema are required.

---

## ADR-003 Keep Framework Core Generic

### Status

Proposed

### Context

The platform should support multiple projects and domains.

### Decision

Domain-specific logic shall not be placed in the framework core. It must be implemented through custom adapter, assertion, oracle, fixture, or data generator.

### Consequences

Plugin boundaries must be enforced.

---

## ADR-004 Require Human Approval for Critical Expected Results

### Status

Proposed

### Context

Agent may accidentally encode incorrect system behavior as expected behavior.

### Decision

Human approval is required for new expected results, golden baselines, and P1 release-gate tests.

### Consequences

Approval workflow is required before generated tests become release-gate tests.
