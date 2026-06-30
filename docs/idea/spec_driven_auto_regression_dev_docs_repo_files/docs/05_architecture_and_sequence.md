# 05. Architecture and Sequence

## 5.1 High-level Architecture

```text
Existing Spec Template
- Feature Spec
- Architecture
- Acceptance Criteria
- API / Data Model
- Test Plan
- Traceability Matrix
        ↓
Agent Skill Layer
- Spec Extractor
- Impact Mapper
- Test Case Designer
- Test Data Generator
- Oracle Generator
- Assertion Selector
- Artifact Writer
- Failure Triage Agent
        ↓
Generated Regression Artifacts
- Regression Matrix
- Test Case YAML
- Test Data
- Expected Result
- Data Catalog
- Fixture / Cleanup
        ↓
Auto Regression Framework
- Data Binding Resolver
- Provider-based Executor
- Assertion Engine
- Oracle Engine
- Evidence Collector
- Report Generator
- Release Gate Engine
        ↓
Quality Output
- Pass / Fail
- Failed Feature
- Actual vs Expected Diff
- Evidence
- Failure Classification
- Go / No-Go Recommendation
```

## 5.2 Responsibility Split

| Layer | Responsibility |
|---|---|
| Spec Template | Define feature, behavior, architecture, AC, test plan |
| Agent Skill | Generate test artifacts from spec |
| Regression Framework | Execute and validate artifacts |
| Project Team | Own business correctness and expected result |
| QA / Release Team | Review evidence and release decision |
| Platform Team | Maintain framework core and common plugins |

## 5.3 Spec-to-Test Generation Flow

```text
Developer updates spec
        ↓
Agent reads feature spec / architecture / AC / test plan
        ↓
Agent checks spec readiness
        ↓
Agent identifies changed or new scenarios
        ↓
Agent generates regression matrix
        ↓
Agent generates test case YAML
        ↓
Agent generates test data and expected result
        ↓
Framework validates artifact syntax
        ↓
Human reviews P1 / expected result / baseline
```

## 5.4 Test Execution Flow

```text
Run selected suite
        ↓
Resolve data binding
        ↓
Setup fixture
        ↓
Execute steps through providers
        ↓
Collect actual result
        ↓
Run assertions / oracle
        ↓
Cleanup data
        ↓
Generate report and evidence
        ↓
Apply release gate rule
```

## 5.5 Failure Triage Flow

```text
Test failed
        ↓
Agent reviews test step log, input data, expected result, actual result, diff, environment, recent code diff
        ↓
Classify failure
        ↓
Generate recommendation
```
