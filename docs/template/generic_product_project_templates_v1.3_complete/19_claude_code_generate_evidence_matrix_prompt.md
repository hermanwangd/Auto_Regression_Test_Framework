# Claude Code Prompt — Generate Evidence Matrix

```text
You are acting as an engineering verification reviewer.

Task:
Generate an evidence matrix for the selected feature.

Inputs:
1. Feature baseline
2. Related spec
3. Acceptance criteria
4. Test results
5. Current codebase
6. PR diff, if available

Rules:
1. Every claim must include evidence.
2. Use file paths, test names, CI report links, logs, metrics, or trace examples.
3. If evidence is missing, mark MISSING.
4. Do not mark ACCEPTED unless explicit engineer sign-off exists.
5. Do not modify code.

Output table columns:
- Feature
- Spec Section
- Acceptance ID
- Implementation Evidence
- Test Evidence
- Regression Evidence
- Metric / Trace Evidence
- Gap
- Suggested Status
- Engineer Review Required
```
