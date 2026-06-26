# Project Scope / Capability Baseline

Version: <version>
Status: DRAFT / BASELINE_CANDIDATE / APPROVED_FOR_SPEC_GENERATION
Owner: <Tech Lead / Product Owner>
Last Updated: <YYYY-MM-DD>

---

## 1. Purpose

This document defines the project scope and capability baseline.

It answers:

```text
1. What capabilities are in scope?
2. Which capabilities are required for the first launch / canary?
3. Which capabilities can be deferred?
4. What is the minimum acceptance bar?
5. What specs, tests, and evidence must be created later?
```

This document is **not** a detailed spec, architecture design, or issue list.

---

## 2. Project Goal

Describe the project in one paragraph.

```text
<What outcome should this project deliver?>
```

---

## 3. One-Sentence System / Product Description

```text
<One sentence description of the product/project capability.>
```

---

## 4. Scope Principle

Example:

```text
Month-1 target is controlled canary vertical slice, not full platform coverage.
```

Define the scope principle:

```text
<scope principle>
```

---

## 5. M1 Must Interpretation

M1 Must means this capability must support the approved first-release / controlled canary vertical slice.

It does **not** necessarily mean full platform coverage.

Examples:

```text
- Formula support may only need to cover approved golden formulas.
- Data integration may only need to cover approved source-target scenarios.
- Feature flags may only need to support approved canary dimensions.
- Admin UI / full RBAC / approval workflow may be deferred.
```

---

## 6. Priority Definition

| Priority | Meaning | Decision Rule |
|---|---|---|
| M1 Must | Required for first launch / controlled canary | Missing it blocks launch |
| M1 Should | Strongly recommended for first launch | Missing it requires SOP / accepted risk |
| M2 | Needed after canary / before broad rollout | Does not block initial canary |
| M3 | Platform / governance / long-term capability | Deferred |

---

## 7. Capability List

| ID | Capability | Priority | Purpose | User / System Outcome | Launch Need | Owner | Spec Status | Acceptance Status | Related Spec |
|---|---|---|---|---|---|---|---|---|---|
| F1 | <Capability name> | M1 Must | <Why it exists> | <What user/system can do> | <Why needed for launch> | <owner> | NOT_STARTED | NOT_STARTED | <path> |

Suggested statuses:

```text
Spec Status:
NOT_STARTED / DRAFT / IN_REVIEW / APPROVED / IMPLEMENTING / VERIFIED / RELEASED

Acceptance Status:
NOT_STARTED / GENERATED / REVIEWED / ACCEPTED / CONDITIONAL / REJECTED
```

---

## 8. Acceptance Source

For each M1 capability, define where acceptance will come from.

```text
Capability:
- Spec:
- Acceptance Criteria:
- Regression:
- Evidence:
```

Example:

```text
Capability: F1 Example Capability
- Spec: spec/m1/spec-m1-example.md
- Acceptance Criteria: acceptance/ac-example.md
- Regression: regression/golden/example.yaml
- Evidence: evidence/example-evidence-matrix.md
```

---

## 9. Milestone Scope

### M1 Must

```text
<List M1 Must capabilities>
```

### M1 Should

```text
<List M1 Should capabilities>
```

### M2

```text
<List M2 capabilities>
```

### M3

```text
<List M3 capabilities>
```

---

## 10. Minimum Acceptance Criteria

The first launch / controlled canary can proceed only if:

```text
1. <criterion>
2. <criterion>
3. <criterion>
```

---

## 11. Golden Happy Path

```text
<Step 1>
  → <Step 2>
  → <Step 3>
  → <Expected successful result>
```

---

## 12. Golden Failure Paths

```text
1. <Failure scenario> → <Expected result>
2. <Failure scenario> → <Expected result>
```

---

## 13. Required Follow-up Documents

### Required Before Implementation

| Document | Related Capability | Owner | Status |
|---|---|---|---|
| <spec path> | <F#> | <owner> | NOT_STARTED |

### Required Before Engineer Acceptance

| Document | Related Capability | Owner | Status |
|---|---|---|---|

### Required Before Release / Controlled Canary

| Document | Related Capability | Owner | Status |
|---|---|---|---|

### Deferred Documents

| Document | Related Capability | Target Timing |
|---|---|---|

---

## 14. Risks

| Risk | Impact | Trigger | Mitigation | Decision / Action | Owner |
|---|---|---|---|---|---|
| <risk> | <impact> | <trigger> | <mitigation> | <action> | <owner> |

---

## 15. Change Control Rule

Any change to the following requires Feature Change Request:

```text
1. M1 Must / M1 Should / M2 / M3 priority
2. Minimum acceptance criteria
3. Golden happy path / failure path
4. Capability addition / removal
5. Capability deprecation
6. Launch scope or Go / No-Go criteria
```

Small wording changes can be handled with a PR note or Change Note.
