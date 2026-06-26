# 07. Regression Test Plan

This plan validates the framework M1 behavior for one pilot Release Package. It does not define downstream product-feature release AC.

## 7.1 Validation Levels

| Level | Purpose | Timing |
|---|---|---|
| Readiness | Validate Product Repo, RP artifacts, and RP/RU mapping completeness | Before generation |
| Generation | Validate agent readiness classification, test drafting, and expected-result drafting | Before execution |
| Execution | Validate package input binding, execution mode, environment readiness, fixture lifecycle, adapter execution, assertions, and raw evidence | Local, CI, or SIT pilot run |
| Evidence | Validate coverage, traceability, exclusions, and review package | Before M1 pilot review |

## 7.2 M1 Test Scope

M1 shall cover:

- Product Repo folder readiness.
- RP creation and artifact completeness.
- RP feature spec and AC intake without invented behavior.
- Human-authored RP/RU mapping completeness.
- AC and execution context readiness classification.
- Draft test skeleton and executable test case generation.
- Expected-result drafting with source references and approval status.
- Release Package execution using package inputs, fixtures, adapter mode, assertions, and cleanup.
- Execution environment resolution for local, CI-ephemeral, SIT-deployed, and evidence-only modes.
- Multi-RU dependency graph and environment readiness validation.
- Coverage and evidence package generation against automatable RP-level AC.

## 7.3 Out of M1 Scope

- Product-level formal AC as release denominator.
- RU repo-owned primary specs or primary AC.
- Cross-package orchestration.
- Full dashboard-driven governance.
- Fully automated release approval.
- Broad package-type plugin support.

## 7.4 Standard Regression Cases

| Test ID | Feature | Scenario | Level | Priority | Automation |
|---|---|---|---|---|---|
| REG-RP-001 | F001 | Product Repo bootstrap/readiness reports missing folders and RP artifacts | Readiness | P1 | Auto |
| REG-RP-002 | F002 | RP completeness check requires minimum RP artifact set | Readiness | P1 | Auto |
| REG-RP-003 | F003 | RP feature spec and AC intake preserves owner-authored AC and stable IDs | Readiness | P1 | Auto |
| REG-RP-004 | F004 | Missing RP/RU mapping fields block execution | Readiness | P1 | Auto |
| REG-RP-005 | F005 | Ambiguous AC produces `not_ready_for_generation` instead of executable test | Generation | P1 | Auto |
| REG-RP-006 | F005 | Ready AC with incomplete execution context produces only `draft_test_skeleton` | Generation | P1 | Auto |
| REG-RP-007 | F006 | Expected-result draft includes source references, assumptions, gaps, and approval status | Generation | P1 | Auto |
| REG-RP-008 | F007 | Pilot RP test executes through adapter and emits raw execution evidence | Execution | P1 | Auto |
| REG-RP-011 | F007 | Execution reuses checked-in approved RP test cases without regenerating them | Execution | P1 | Auto |
| REG-RP-012 | F007 | SIT-deployed execution is blocked until deployment and environment readiness evidence exists | Execution | P1 | Auto |
| REG-RP-013 | F007 | Multi-RU execution follows the declared dependency graph and stops downstream execution on required upstream validation failure | Execution | P1 | Auto |
| REG-RP-009 | F008 | Coverage and evidence package trace to RP ID, AC ID, test case ID, and run ID | Evidence | P1 | Auto |
| REG-RP-010 | F008 | Manual-only or waived AC require approval record before exclusion | Evidence | P1 | Auto |
