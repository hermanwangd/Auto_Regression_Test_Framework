# Generic Product / Project Development Templates


## v1.3 Complete Update

This package is the complete generic product/project development template set. It includes all v1.2 workflow templates plus the upgraded v1.3 implementation-ready Feature / Functional Spec template and Claude Code Generate Spec prompt.

Key v1.3 spec upgrades:

- Controlled Canary / Delivery Slice
- Blocking Open Questions classification
- Key Decisions
- Runtime Sequence / Algorithm
- State Machine
- API / Interface Contract
- Data Model / Schema Contract
- Logical Uniqueness / Idempotency
- Error / Reason Codes
- Acceptance Metrics
- Test / Regression Mapping
- Architecture Impact
- Implementation Readiness Gate



Version: v1.3 Complete
Status: Generic template package
Purpose: Support AI-assisted product/project development from idea to release.

---

## 1. What This Template Package Is

This package provides a reusable documentation and workflow system for product / software project development.

It is not tied to any specific project.

It supports the following flow:

```text
Idea
  → Project Scope / Capability Baseline
  → Feature / Functional Spec
  → Architecture Design
  → Acceptance Criteria
  → Test / Regression Plan
  → Issue / Implementation Plan
  → PR Review / Evidence
  → Release Go / No-Go
  → Change Control
```

---

## 2. Template Usage Flow

Use the templates in this order:

| Step | Purpose | Template |
|---|---|---|
| 1 | Define project scope and capability priorities | `01_project_scope_capability_baseline_template.md` |
| 2 | Create spec index | `02_spec_index_template.md` |
| 3 | Write feature / functional specs | `03_feature_spec_template.md` |
| 4 | Generate acceptance criteria | `04_acceptance_criteria_template.md` |
| 5 | Create architecture design for architecture-sensitive features | `06_architecture_design_template_c4_sequence_algorithm.md` |
| 6 | Define APIs, if needed | `07_api_spec_template.md` |
| 7 | Define data model / schema, if needed | `08_data_model_schema_template.md` |
| 8 | Define test / regression plan | `09_test_plan_regression_template.md` |
| 9 | Record major architecture decisions | `10_adr_template.md` |
| 10 | Create 4hr implementation issues | `11_issue_template.md` |
| 11 | Review PR using evidence-based review | `12_pr_review_acceptance_template.md` |
| 12 | Produce traceability / evidence matrix | `18_test_traceability_matrix_template.md`, `19_claude_code_generate_evidence_matrix_prompt.md` |
| 13 | Make release decision | `20_release_readiness_go_no_go_template.md` |
| 14 | Handle scope/spec/design changes | `change-control/` templates |

---

## 3. Minimum Required Documents by Stage

| Stage | Required Documents |
|---|---|
| Before implementation | Feature Spec, Acceptance Criteria, Issue |
| Before architecture-sensitive implementation | Architecture Design, ADR if major |
| Before engineer acceptance | Acceptance Criteria, Test Traceability Matrix, Evidence Matrix |
| Before release / controlled canary | Release Go / No-Go, Regression Evidence, Rollback Plan |
| Before changing scope/spec/design | Change Note or FCR/SCR/DCR |

---

## 4. Lightweight vs Full Process

Default mode should be lightweight.

```text
Small implementation detail → PR Note
Medium spec/design/test change → Change Note
Major scope/spec/architecture/release change → FCR / SCR / DCR
```

Do not require heavy templates for every small change.

---

## 5. Version and Filename Strategy

When applying these templates to a project:

```text
Use stable active filenames.
Put version in document metadata.
Archive old versions separately.
```

Recommended:

```text
project-scope-capability-baseline.md
spec-index.md
feature-name-spec.md
release-go-no-go.md
```

Avoid active filenames like:

```text
feature-name-spec-v1.3 Complete.md
```

Use archive folder for historical versions:

```text
archive/v1.3 Complete/
```

---

## 6. Recommended Repo Layout

```text
doc/
  project_doc/
    templates/
      <generic templates>

    <project-name>/
      product/
      spec/
      architecture/
      assessment/
      process/
      release/
      tests/
      evidence/
```

---

## 7. AI / Claude Code Usage

Claude Code can help generate:

```text
1. Spec drafts from Feature Baseline + Architecture
2. Acceptance criteria from Spec
3. Regression cases from Acceptance Criteria
4. 4hr issues from approved Spec
5. Architecture drift review
6. Evidence matrix from code/test results
```

Claude Code should not directly decide acceptance.

Engineer sign-off is required for `ACCEPTED`.