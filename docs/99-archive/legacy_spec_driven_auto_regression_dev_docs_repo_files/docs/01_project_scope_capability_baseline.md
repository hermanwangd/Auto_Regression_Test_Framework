# 01. Project Scope & Capability Baseline

## 1.1 Background

Regression testing across projects currently has duplicated infrastructure and inconsistent coverage. Each project may implement its own test runner, test data loader, assertion helper, report generator, and CI scripts. This makes regression execution hard to standardize and makes release decisions dependent on manual judgment.

The target is to build a spec-driven auto regression quality platform:

```text
Spec Template
→ Agent Skill
→ Generated Regression Artifacts
→ Auto Regression Framework
→ Evidence / Release Gate
```

## 1.2 Objective

- Build a generic auto regression framework reusable across projects.
- Keep the framework core domain-independent.
- Generate test artifacts from existing feature specs, architecture specs, acceptance criteria, API/data model, and test plans.
- Standardize test case DSL, test data catalog, data binding, assertion, oracle, evidence, and release gate.
- Enable agent-assisted test creation, execution, and failure triage.

## 1.3 Non-goals

- Not replacing project-owned testing responsibility.
- Not fully automating release approval.
- Not auto-approving expected results or golden baselines.
- Not allowing agent-generated tests to merge without review.
- Not supporting every provider in MVP.
- Not executing destructive operations without approval.
- Not using production data without masking and approval.

## 1.4 Milestone Definition

| Milestone | Meaning | Exit Criteria |
|---|---|---|
| M1 Must Vertical Slice | Framework can execute manually authored test artifacts for one pilot feature | YAML schema, data catalog, API provider, DB provider, basic assertions, report, CLI runner |
| M2 Agent Draft Generation | Agent can generate draft regression artifacts from existing specs | Spec readiness check, regression matrix, test case YAML, input/expected data |
| M3 Governance Integration | Regression results support release gate | P1/P2/P3 policy, approval, waiver, failure classification, Go/No-Go report |
| M4 Scale-out | Multiple projects can adopt the same framework | Plugin SDK, onboarding guide, dashboard, reusable templates |

## 1.5 Capability Baseline

| Capability ID | Capability | MVP | Owner | Status | Evidence |
|---|---|---:|---|---|---|
| CAP-001 | Test Case YAML DSL | M1 | Platform | Planned | Schema + validation report |
| CAP-002 | Test Data Catalog | M1 | Platform | Planned | Catalog schema + sample datasets |
| CAP-003 | Data Binding Resolver | M1 | Platform | Planned | Binding unit tests |
| CAP-004 | API Provider | M1 | Platform | Planned | API execution evidence |
| CAP-005 | DB Provider | M1 | Platform | Planned | DB state assertion evidence |
| CAP-006 | Basic Assertion Library | M1 | Platform | Planned | Assertion test report |
| CAP-007 | Fixture Setup / Cleanup | M1 | Platform | Planned | Cleanup evidence |
| CAP-008 | HTML / JSON Report | M1 | Platform | Planned | Sample report |
| CAP-009 | Spec Readiness Checker | M2 | Agent Skill | Planned | Readiness report |
| CAP-010 | Spec-to-Test Generator | M2 | Agent Skill | Planned | Generated artifact diff |
| CAP-011 | Failure Triage Agent | M3 | Agent Skill | Planned | Failure triage report |
| CAP-012 | Release Gate Engine | M3 | QA / Release | Planned | Go / No-Go evidence |
| CAP-013 | Waiver Process | M3 | Release | Planned | Waiver records |
| CAP-014 | Plugin SDK | M4 | Platform | Planned | Custom plugin example |
