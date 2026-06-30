# 07. Regression Test Plan

## 7.1 Suite Levels

| Suite | Purpose | Timing |
|---|---|---|
| Smoke | Basic system health | Every commit / deployment |
| Core | Critical P1 features | Every merge / release candidate |
| Full | All released features | Nightly / before release |
| SIT | End-to-end business flow | Before release |
| Release Gate | Go / No-Go decision | Before production deployment |

## 7.2 MVP Test Scope

MVP shall cover:

- YAML test case validation
- Data catalog validation
- Data binding resolution
- API provider execution
- DB provider execution
- Basic assertions
- Fixture setup
- Cleanup
- HTML / JSON report
- Spec readiness check
- Agent-generated regression matrix
- Agent-generated test case YAML
- Agent-generated input / expected data

## 7.3 Out of MVP Scope

- Full UI automation
- Complex event replay
- All custom oracle types
- Production-scale backtest
- Self-healing tests
- Fully autonomous merge
- Fully autonomous release approval

## 7.4 Standard Regression Cases

| Test ID | Feature | Scenario | Suite | Priority | Automation |
|---|---|---|---|---|---|
| REG-FW-001 | F001 | Framework executes valid YAML test | Core | P1 | Auto |
| REG-DSL-001 | F002 | DSL schema validation passes | Core | P1 | Auto |
| REG-DATA-001 | F003 | Dataset binding resolves input/expected | Core | P1 | Auto |
| REG-PROVIDER-001 | F004 | API provider executes request | Core | P1 | Auto |
| REG-PROVIDER-002 | F004 | DB provider verifies state | Core | P1 | Auto |
| REG-ASSERT-001 | F005 | Assertion engine compares actual/expected | Core | P1 | Auto |
| REG-AGENT-001 | F006 | Agent generates test artifacts from complete spec | SIT | P1 | Auto |
| REG-AGENT-002 | F007 | Agent blocks generation for incomplete spec | SIT | P1 | Auto |
| REG-GATE-001 | F008 | P1 failure blocks release | Release Gate | P1 | Auto |
