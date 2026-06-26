# Acceptance Criteria

Version: <version>
Status: DRAFT / IN_REVIEW / APPROVED / VERIFIED
Owner: <QA / Feature Owner>
Related Feature: <F#>
Related Spec: <path>
Last Updated: <YYYY-MM-DD>

---

## 1. Purpose

This document defines how the feature will be accepted.

Feature List decides **what must be accepted**.
Spec decides **what correct behavior means**.
This document defines **the concrete pass/fail criteria**.

---

## 2. Acceptance Type

- [ ] Functional
- [ ] Data
- [ ] API
- [ ] Failure Path
- [ ] Idempotency
- [ ] Performance
- [ ] Metrics / Trace
- [ ] Feature Flag / Rollback
- [ ] Release Gate

---

## 3. Acceptance Criteria

| ID | Given | When | Then | Priority |
|---|---|---|---|---|
| AC-001 | <context> | <action> | <expected result> | Must |

---

## 4. Failure Acceptance

| ID | Failure Scenario | Expected Result | Reason / Error Code |
|---|---|---|---|
| AC-F-001 | <failure> | <expected handling> | <code> |

---

## 5. Data Acceptance

| ID | Data Condition | Expected Storage / Output |
|---|---|---|

---

## 6. Metrics / Trace Acceptance

| ID | Signal | Expected Evidence |
|---|---|---|

---

## 7. Acceptance to Test Mapping

| Acceptance ID | Acceptance Criteria | Unit Test | Integration Test | Regression Test | Evidence |
|---|---|---|---|---|---|
| AC-001 | <criteria> | <test> | <test> | <case> | <link> |

---

## 8. Engineer Verification

| Reviewer | Role | Decision | Comment |
|---|---|---|---|
| <name> | Feature Owner | ACCEPT / CONDITIONAL / REJECT |  |
| <name> | Architect | ACCEPT / CONDITIONAL / REJECT |  |
| <name> | QA / Release | ACCEPT / CONDITIONAL / REJECT |  |

---

## 9. Acceptance Decision

- [ ] ACCEPTED
- [ ] CONDITIONAL_ACCEPT
- [ ] NEEDS_FIX
- [ ] REJECTED
- [ ] DEFERRED

Reason:

```text
<reason>
```
