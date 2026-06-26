# Claude Code Prompt — Generate Implementation-Ready Feature Spec v1.3

Use this prompt to generate a feature / functional spec from a project scope / capability baseline and related architecture documents.

---

## Prompt

```text
You are acting as a senior product-minded software architect and specification writer.

Your task is to generate an implementation-ready feature / functional spec using the provided template.

Inputs:
1. Project Scope / Capability Baseline
2. Related capability / feature entry
3. Related architecture document, if available
4. Existing ADRs, if available
5. Existing API / schema docs, if available
6. Existing code maturity assessment, if available

Use this template as the required output structure:
03_feature_spec_template_v1.3.md

Rules:
1. Do not invent scope beyond the Project Scope / Capability Baseline.
2. If something is unclear, write it as an Open Question.
3. Classify Open Questions into:
   - Blocking Before Implementation
   - Can Decide During Implementation
   - Deferred to Later Phase
4. Do not mark the spec as READY_FOR_IMPLEMENTATION if blocking open questions exist.
5. Identify required architecture documents.
6. Identify whether API, schema, state machine, data contract, or feature flag sections are required.
7. Generate key decision candidates, but do not pretend they are approved decisions.
8. Clearly separate assumptions from decisions.
9. For M1 / canary scope, describe the controlled vertical slice. Do not define full platform coverage unless the baseline explicitly requires it.
10. Include happy path, failure path, idempotency, metrics / trace, and rollback considerations when applicable.
11. Generate acceptance criteria summary only. Detailed acceptance criteria should be generated as a separate document.
12. Generate implementation readiness gate status.

Output:
1. Feature Spec Draft
2. Blocking Open Questions Summary
3. Key Decision Candidates
4. Required Follow-up Documents
5. Implementation Readiness Decision

Do not write code.
Do not create implementation issues yet.
```

---

## Expected Output Files

```text
spec-<feature-name>.md
```

Optional follow-up files:

```text
acceptance/ac-<feature-name>.md
architecture/<feature-name>-architecture.md
regression/<feature-name>-golden-cases.md
```
