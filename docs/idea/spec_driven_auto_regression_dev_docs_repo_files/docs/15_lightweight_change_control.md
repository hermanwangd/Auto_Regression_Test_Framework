# 15. Lightweight Change Control

## 15.1 Change Categories

| Category | Example | Approval |
|---|---|---|
| Minor | Add P2/P3 test case | Project owner |
| Medium | Add P1 regression case | QA + Project owner |
| Major | Change release gate rule | Release manager + QA lead |
| Critical | Change expected baseline | QA lead + Business owner |
| Security | Change secret/data policy | Security owner |

## 15.2 Change Control Rule

- Every behavior change must update related specs and tests in the same PR.
- Every new P1 feature must include regression coverage or documented waiver.
- Every expected result change must include approval evidence.
- Every release gate rule change must be reviewed by QA lead and release manager.
