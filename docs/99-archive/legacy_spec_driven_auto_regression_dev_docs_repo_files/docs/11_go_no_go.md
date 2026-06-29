# 11. Go / No-Go Criteria

## 11.1 Go Criteria

Release can proceed when:

- Smoke suite passed.
- All P1 release-gate tests passed.
- No unapproved expected result exists.
- No critical compatibility failure exists.
- No destructive cleanup failure exists.
- All blocking failures are resolved or approved by waiver.
- Regression report and evidence are available.

## 11.2 No-Go Criteria

Release should be blocked when:

- Any P1 release-gate test fails.
- Smoke suite fails.
- Critical state assertion fails.
- Critical compatibility test fails.
- Generated expected result is not approved.
- Destructive cleanup fails.
- Security or permission test fails.
- No valid regression report is available.

## 11.3 Waiver Process

A failed regression case can only be waived with structured approval.

Required waiver fields:

```text
waiver_id
failed_test_id
failure_summary
reason
risk_assessment
owner
approver
expiry_date
follow-up_action
```

Waiver should not be open-ended. Each waiver must have an expiry date and follow-up owner.
