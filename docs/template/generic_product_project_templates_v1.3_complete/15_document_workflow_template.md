# Documentation Workflow

Version: <version>
Owner: <Tech Lead>
Last Updated: <YYYY-MM-DD>

---

## 1. Purpose

Define when each document is created, who owns it, and which gate it supports.

---

## 2. Document Production Flow

```text
Idea
  → Project Scope / Capability Baseline
  → Spec Index
  → Feature Spec
  → Architecture Design, if needed
  → Acceptance Criteria
  → Test / Regression Plan
  → Issue
  → PR Review / Evidence
  → Release Go / No-Go
```

---

## 3. Document Ownership

| Document | Owner | Reviewers | Required Before |
|---|---|---|---|
| Project Scope / Capability Baseline | Tech Lead / Product | Architect, QA | Spec generation |
| Feature Spec | Feature Owner | Tech Lead, Architect, QA | Implementation |
| Architecture Design | Architect | Tech Lead, Feature Owner | Architecture-sensitive implementation |
| Acceptance Criteria | QA / Feature Owner | Tech Lead, Engineer | Engineer acceptance |
| Test Plan | QA / Engineer | Tech Lead | Release |
| Issue | Feature Owner | Tech Lead | Implementation |
| PR Review / Evidence | Engineer | Reviewer | Merge |
| Release Go / No-Go | Tech Lead / Release Owner | Architect, QA | Release |

---

## 4. Claude Code Involvement

| Step | Claude Code Role |
|---|---|
| Feature → Spec | Generate draft spec |
| Spec → Acceptance | Generate acceptance criteria |
| Spec → Issue | Split into 4hr issues |
| Code → Evidence | Generate evidence matrix |
| Code → Architecture | Detect architecture drift |

---

## 5. Gates

### Before Implementation

- [ ] Feature in scope
- [ ] Spec approved
- [ ] Acceptance criteria generated
- [ ] Architecture reviewed if needed

### Before Engineer Acceptance

- [ ] Implementation complete
- [ ] Test evidence attached
- [ ] Evidence matrix updated
- [ ] Cross-context review completed if needed

### Before Release

- [ ] P0 blockers = 0
- [ ] M1 Must complete or accepted risk
- [ ] Regression pass
- [ ] Feature flag / rollback ready
- [ ] Go / No-Go signed off

---

## 6. Lightweight Rule

Do not require all templates for every small change.

```text
Small change → PR note
Medium change → Change Note
Major change → FCR / SCR / DCR
```
