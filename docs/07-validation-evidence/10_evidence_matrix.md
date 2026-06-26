# 10. Evidence Matrix

| Evidence ID | Evidence | Owner | Used For | Storage |
|---|---|---|---|---|
| EVD-001 | Product Repo readiness report and readiness agent explanation | Platform / Agent Skill | F001 readiness proof and owner-actionable next-step guidance | `docs/08-release/release-packages/<rp_id>/evidence/readiness/` |
| EVD-002 | RP completeness report | Platform | F002 artifact completeness proof | `docs/08-release/release-packages/<rp_id>/evidence/readiness/` |
| EVD-003 | RP AC intake and classification report | Platform / Agent Skill | F003 AC inventory and readiness proof | `docs/08-release/release-packages/<rp_id>/evidence/generation/` |
| EVD-004 | RP/RU mapping validation report | Platform | F004 mapping completeness and execution-blocking proof | `docs/08-release/release-packages/<rp_id>/evidence/readiness/` |
| EVD-005 | Test drafting readiness report | Agent Skill | F005 skeleton/executable generation decision proof | `docs/08-release/release-packages/<rp_id>/evidence/generation/` |
| EVD-006 | Expected-result draft and approval record | Product Developer / Agent Skill | F006 expected-result review and approval proof | `docs/08-release/release-packages/<rp_id>/expected-results/` |
| EVD-007 | Raw execution report, checked-in test case revision list, execution mode, environment readiness, deployment refs, RU dependency graph, logs, actual results, assertions, and cleanup evidence | Platform | F007 execution proof, no-regeneration proof, and environment readiness proof | `docs/08-release/release-packages/<rp_id>/evidence/runs/<run_id>/` |
| EVD-008 | Coverage report, traceability report, evidence index, failure summary, and release review summary | Platform / QA / Release | F008 review-ready evidence package | `docs/08-release/release-packages/<rp_id>/evidence/review/` |

Evidence records shall preserve RP ID, AC ID, test case ID, run ID where applicable, source artifact links, approval records, and waiver/manual-only exclusion records.
