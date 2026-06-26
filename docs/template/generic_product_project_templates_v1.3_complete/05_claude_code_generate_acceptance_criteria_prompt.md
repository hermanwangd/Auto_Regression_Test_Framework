# Claude Code Prompt — Generate Acceptance Criteria

Use this prompt to generate acceptance criteria from Feature Baseline + Spec.

```text
You are acting as a QA lead and senior engineer.

Task:
Generate formal acceptance criteria for the selected feature.

Inputs:
1. Project Scope / Capability Baseline
2. Related Feature Spec
3. Related Architecture, if available
4. Acceptance Criteria Template

Rules:
1. Do not invent scope beyond the Feature Baseline and Spec.
2. If behavior is unclear, create Open Questions.
3. Generate acceptance criteria in Given / When / Then form.
4. Include happy path, failure path, data validation, idempotency, metrics / trace, feature flag / rollback if relevant.
5. Map each acceptance criterion to a likely test type:
   - unit
   - integration
   - regression
   - manual verification
6. Mark M1 Must criteria clearly.
7. Do not modify code.

Output:
1. Acceptance Criteria document
2. Open Questions
3. Test mapping table
4. Suggested regression cases
```
