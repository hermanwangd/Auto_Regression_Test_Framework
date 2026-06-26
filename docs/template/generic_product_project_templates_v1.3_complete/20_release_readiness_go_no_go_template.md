# Release Readiness / Go-No-Go Checklist

Version: <version>
Release ID: <release>
Owner: <Release Owner>
Date: <YYYY-MM-DD>

---

## 1. Release Scope

```text
<release scope>
```

---

## 2. Go / No-Go Decision Table

| Gate | Required | Status | Evidence | Owner |
|---|---|---|---|---|
| P0 blockers = 0 | Yes |  |  |  |
| M1 Must complete | Yes |  |  |  |
| Golden regression pass | Yes |  |  |  |
| Failure path pass | Yes |  |  |  |
| Metrics ready | Yes |  |  |  |
| Trace ready | Yes |  |  |  |
| Feature flag ready | Yes |  |  |  |
| Rollback ready | Yes |  |  |  |
| Engineer sign-off | Yes |  |  |  |

---

## 3. Open Risks

| Risk | Severity | Mitigation | Owner | Accepted? |
|---|---|---|---|---|

---

## 4. Rollback Readiness

- [ ] Feature flag off path verified
- [ ] Config rollback ready
- [ ] Deployment rollback ready
- [ ] DB recovery plan ready
- [ ] Smoke test after rollback defined

---

## 5. Canary Scope

```text
Allowed users / routes / segments / operations:
Excluded scope:
Expansion rule:
Stop rule:
```

---

## 6. Final Decision

- [ ] GO
- [ ] GO_WITH_RISK
- [ ] NO_GO

Reason:

```text
<reason>
```

---

## 7. Sign-off

| Role | Name | Decision | Date |
|---|---|---|---|
| Tech Lead |  |  |  |
| Architect |  |  |  |
| QA / Release |  |  |  |
| Operations |  |  |  |
