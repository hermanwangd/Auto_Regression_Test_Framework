# Change Control Workflow

Version: <version>
Owner: <Tech Lead>
Last Updated: <YYYY-MM-DD>

---

## 1. Purpose

Define how to add, modify, or deprecate project capabilities, specs, and architecture designs.

---

## 2. Lightweight Rule

Do not over-process small changes.

```text
Level 0: PR note
Level 1: Change Note
Level 2: FCR / SCR / DCR
```

---

## 3. Decision Guide

| Change | Required Process |
|---|---|
| Implementation detail only | PR note |
| Small spec clarification | Change Note |
| Small design adjustment | Change Note |
| Add / remove / reprioritize feature | FCR |
| Change functional behavior / acceptance | SCR |
| Change architecture / schema / data flow | DCR |
| Deprecate feature | FCR + Deprecation Plan |

---

## 4. Three Questions

Before escalating, ask:

```text
1. Does this change user/runtime behavior?
2. Does this change architecture/schema/API?
3. Does this affect launch / controlled canary?
```

If all no → PR note.
If one yes but low risk → Change Note.
If high risk → FCR / SCR / DCR.

---

## 5. Approval

| Process | Approver |
|---|---|
| PR note | PR reviewer |
| Change Note | Tech Lead / Architect depending on impact |
| FCR | Tech Lead + Product / Architect if needed |
| SCR | Tech Lead + Spec Owner |
| DCR | Architect + Tech Lead |
| Deprecation Plan | Tech Lead + Architect + QA / Release |

---

## 6. Closure Criteria

A change is closed only when:

- [ ] Baseline / spec / architecture updated if needed
- [ ] Issue queue updated
- [ ] Tests / regression updated
- [ ] Evidence attached
- [ ] Engineer verification completed
- [ ] Release note / changelog updated
