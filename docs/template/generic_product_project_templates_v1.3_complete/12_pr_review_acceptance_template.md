# PR Review / Acceptance Template

PR:
Related Issue:
Related Spec:
Related Acceptance Criteria:
Owner:
Reviewer:

---

## 1. Review Mode

- [ ] Evidence-based review
- [ ] Architecture boundary review
- [ ] Spec alignment review
- [ ] Cross-context AI review
- [ ] Cross-model review, if high risk
- [ ] Manual line review required

---

## 2. Spec Alignment

- [ ] PR matches approved spec
- [ ] No unapproved behavior change
- [ ] Acceptance criteria addressed
- [ ] Failure cases addressed

---

## 3. Architecture Boundary

- [ ] Does not bypass approved runtime contract / boundary
- [ ] Does not directly use physical storage outside approved repository
- [ ] Does not use legacy / deprecated models
- [ ] Does not introduce hidden coupling
- [ ] Preserves idempotency / validation / trace

---

## 4. Manual Line Review Required If

- [ ] Concurrency / locking
- [ ] DB migration
- [ ] Security / permission
- [ ] Formula evaluator / dynamic execution
- [ ] Idempotency
- [ ] Hold / high-impact decision
- [ ] High-volume query
- [ ] Data consistency

If checked, specify reviewed files:

```text
<files>
```

---

## 5. Evidence

- [ ] Unit test result
- [ ] Integration test result
- [ ] Regression result
- [ ] Metrics screenshot / report
- [ ] Trace example
- [ ] Feature flag tested
- [ ] Rollback path verified
- [ ] Evidence matrix updated

Links:

```text
<links>
```

---

## 6. Cross Review

- [ ] Cross-context AI review completed
- [ ] Cross-model review completed, if high risk
- [ ] Engineer reviewed AI findings

---

## 7. Decision

- [ ] APPROVE
- [ ] APPROVE_WITH_RISK
- [ ] REQUEST_CHANGES
- [ ] REJECT

Reason:

```text
<reason>
```

---

## 8. Sign-off

| Reviewer | Role | Decision | Date |
|---|---|---|---|
