# 09. Traceability Matrix

| Requirement | Feature | Acceptance Criteria | Test Case | Evidence |
|---|---|---|---|---|
| REQ-000 Framework verification and RP regression execution boundary | Cross-cutting | AC-017 | FWK-001 / FWK-002 / FWK-003 / FWK-004 / FWK-005 / FWK-006 / FWK-007 / FWK-008 / FWK-009 / FWK-010 / FWK-011 / FWK-012 | EVD-000 / EVD-009 |
| REQ-000A Sample generated-artifact framework integration AC trace | Cross-cutting | AC-001 through AC-018 | FWK-001 / FWK-002 / FWK-004 / FWK-006 / FWK-007 / FWK-008 / FWK-010 / FWK-011 / FWK-012 | EVD-000 / EVD-009 |
| REQ-001 Product Repo bootstrap CLI and readiness agent skill | F001 | AC-001 | REG-RP-001 | EVD-001 |
| REQ-002 RP creation completeness | F002 | AC-002 | REG-RP-002 | EVD-002 |
| REQ-003 RP feature spec and AC intake | F003 | AC-003 | REG-RP-003 | EVD-003 |
| REQ-004 Product mapping translation and generated artifact completeness | F004 | AC-004 | REG-RP-004 | EVD-004 |
| REQ-005 AC readiness and test drafting | F005 | AC-005 | REG-RP-005 / REG-RP-006 | EVD-005 |
| REQ-006 Expected-result drafting and approval | F006 | AC-006 | REG-RP-007 | EVD-006 |
| REQ-007 Release Package execution | F007 | AC-007 | REG-RP-008 / REG-RP-011 / REG-RP-012 / REG-RP-013 | EVD-007 |
| REQ-007A Heterogeneous provider-family execution support | F007 | AC-004 / AC-007 / AC-008 / AC-009 | FWK-006 / FWK-007 / FWK-IT-010 / FWK-IT-011 / REG-RP-015 / REG-RP-016 | EVD-007 / EVD-009 |
| REQ-007B Execution-focused DSL v0.2 run/report consumption | F007 / F008 | AC-001 / AC-002 / AC-003 / AC-012 / AC-013 / AC-014 / AC-015 / AC-017 / AC-018 | FWK-008 | EVD-000 / EVD-007 / EVD-008 |
| REQ-007C v0.2 runner catalog | F007 / F011 | AC-005 / AC-006 / AC-007 / AC-008 / AC-016 | FWK-010 | EVD-009 |
| REQ-007D v0.2 verify and polling catalog | F007 / F011 | AC-009 / AC-010 / AC-011 / AC-016 | FWK-011 | EVD-009 |
| REQ-007E v0.2 evidence, result, and secret guardrail | F007 / F008 | AC-012 / AC-013 / AC-014 | FWK-012 | EVD-007 / EVD-008 / EVD-009 |
| REQ-008 Unsafe or incomplete regression execution block | F007 | AC-008 | REG-RP-012 / REG-RP-013 / REG-RP-014 | EVD-007 |
| REQ-009 Coverage and evidence package | F008 | AC-009 | REG-RP-009 / REG-RP-010 | EVD-008 |

Every generated test case and evidence item shall also trace to `source_refs.acceptance_criteria`, optional Product/RP labels, batch ID, run ID, and test case ID inside the pilot RP evidence package.
