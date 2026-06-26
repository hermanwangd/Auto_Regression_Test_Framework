# 12. PR Review / Evidence Checklist

## 12.1 RP Spec Review

- [ ] RP ID and owner are clear.
- [ ] RP feature spec is owner-authored and traceable.
- [ ] RP-level AC have stable AC IDs.
- [ ] Inputs, behavior, expected outputs, and allowed side effects are defined for ready AC.
- [ ] Product E2E context is linked when cross-RP confidence matters.
- [ ] RP/RU mapping is human-authored and complete.
- [ ] Each RU declares execution mode, deployment requirement, environment reference, validation boundary, and dependency order.

## 12.2 Generated Artifact Review

- [ ] Readiness report lists status, gaps, owner action, and next required step.
- [ ] Test case YAML includes RP ID, AC ID, test case ID, execution target, and assertion references.
- [ ] Approved test cases are checked in under the RP `tests/approved/` folder.
- [ ] Test case revisions or replacement links are present when generated tests update existing approved tests.
- [ ] Package input references and fixture lifecycle are safe and reviewable.
- [ ] Expected-result artifacts include source references, assumptions, unresolved gaps, and approval status.
- [ ] `approved_for_regression` is present before expected results are used as regression truth.
- [ ] No secret, sensitive, or unapproved production data is committed.

## 12.3 Evidence Review

- [ ] Execution evidence includes run ID, RP ID, AC ID, test case ID, actual results, assertion results, logs, and cleanup evidence.
- [ ] Execution evidence identifies the checked-in test case revision used for the run.
- [ ] Execution evidence records execution mode, environment reference, deployment refs when required, and RU dependency order.
- [ ] SIT execution evidence includes environment readiness proof before test execution.
- [ ] Coverage is calculated against automatable RP-level AC.
- [ ] Manual-only or waived AC exclusions have approval records.
- [ ] Failure summary includes expected result, actual result, source artifact links, and owner action.
- [ ] Release review summary is evidence-backed and does not bypass human Go / No-Go ownership.
