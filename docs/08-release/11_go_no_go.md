# 11. Go / No-Go Criteria

This document belongs to F010 / M3 governance. M1 produces release review inputs only; final Go / No-Go remains human-owned.

## 11.1 Go Review Inputs

A Release Package may be recommended for release review when:

- RP-level automatable AC coverage meets the configured threshold.
- M1 pilot target is greater than 80% coverage for automatable RP-level AC.
- Generated tests and evidence trace to RP ID, AC ID, test case ID, and run ID.
- Required expected results are `approved_for_regression`.
- No blocking execution failure remains unresolved.
- Manual-only and waived AC exclusions have approval records.
- Evidence package includes coverage report, traceability report, failure summary, and evidence index.

## 11.2 No-Go Review Inputs

A Release Package should be flagged for release review when:

- Required RP artifacts are missing or incomplete.
- Required RP/RU mapping fields are missing.
- Automatable RP-level AC coverage is below the configured threshold.
- A blocking regression failure has no approved waiver.
- Expected results required for regression truth remain `draft` or `blocked`.
- Evidence is missing RP ID, AC ID, test case ID, or run ID traceability.
- Cleanup failure, destructive-operation risk, or data-sensitivity risk is unresolved.

## 11.3 Waiver Record

A failed or excluded regression case can only be waived with structured approval.

Required waiver fields:

```yaml
waiver_id: WAV-001
rp_id: RP-AR-M1-data-pipeline
ac_id: RP-AR-M1-data-pipeline-AC-001
test_case_id: RP-AR-M1-data-pipeline-TC-001
failure_summary: actual output differed from approved expected output
reason: accepted known limitation for this release
risk_assessment: low
owner: product_developer
approver: release_owner
expiry_date: 2026-07-31
follow_up_action: update AC or implementation before next release
```

Waivers shall not be open-ended. Each waiver must have an expiry date and follow-up owner.
