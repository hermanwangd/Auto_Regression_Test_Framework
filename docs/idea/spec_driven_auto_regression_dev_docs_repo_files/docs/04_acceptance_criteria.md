# 04. Acceptance Criteria

## AC-001 Framework Executes Standard Test Case

Given a valid test case YAML and valid dataset binding
When the regression framework executes the test case
Then the framework shall resolve test data, execute steps, run assertions, cleanup data, and generate a report.

## AC-002 Agent Generates Test Case from Spec

Given a feature spec with acceptance criteria, input/output, and architecture touchpoints
When the agent skill processes the spec
Then it shall generate regression matrix, test case YAML, input data, expected result, and assertion configuration.

## AC-003 Spec Readiness Gate Blocks Low-quality Generation

Given an incomplete feature spec
When the agent skill performs spec readiness check
Then it shall report missing information instead of generating unreliable tests.

## AC-004 Human Approval Required for Expected Result

Given an agent-generated expected result
When the test case is marked as P1 or release-gate
Then human approval shall be required before the test becomes active in release gate.

## AC-005 Release Gate Blocks Critical Failure

Given a P1 release-gate regression test fails
When release gate evaluates the regression report
Then the release shall be blocked unless an approved waiver exists.

## AC-006 Failure Triage Classifies Failure

Given a regression test failure
When the failure triage agent analyzes logs, actual result, expected result, environment, and code diff
Then it shall classify the failure as Product defect, Test data issue, Expected result outdated, Environment issue, Flaky test, Framework issue, Agent generation issue, or Spec ambiguity.
