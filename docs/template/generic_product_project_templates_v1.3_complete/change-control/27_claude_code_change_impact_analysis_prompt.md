# Claude Code Prompt — Change Impact Analysis

```text
You are acting as a senior tech lead and architect.

Task:
Review this change request and perform impact analysis.

Inputs:
1. Change Note / FCR / SCR / DCR
2. Project Scope / Capability Baseline
3. Related Spec
4. Related Architecture
5. Current Codebase

Return:
1. Impacted capabilities
2. Impacted specs
3. Impacted architecture documents
4. Impacted APIs / schemas
5. Impacted tests / regression
6. Impacted release gates
7. Files likely affected
8. Risk level: Small / Medium / Major
9. Whether this should remain Change Note or be escalated to FCR / SCR / DCR
10. Recommended follow-up issues

Rules:
- Do not modify code.
- Provide evidence with file paths where possible.
- Mark uncertain items explicitly.
```
