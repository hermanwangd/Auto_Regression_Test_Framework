# Claude Code Prompt — Architecture Drift Review

```text
You are acting as a senior architect.

Task:
Review the current implementation for architecture drift.

Inputs:
1. Approved architecture design
2. Approved feature spec
3. Current codebase
4. PR diff, if available

Focus on:
1. Bypass of approved runtime contract or boundary
2. Direct access to physical storage outside approved repository
3. Usage of legacy or deprecated models
4. Hidden coupling between modules
5. Incorrect runtime sequence
6. Missing validation / idempotency / trace
7. Unsafe dynamic execution
8. Missing feature flag / rollback path
9. Missing metrics / evidence
10. Divergence from approved C4 / sequence / algorithm design

Output:
1. Architecture Drift List
2. Evidence with file paths
3. Severity: P0 / P1 / P2
4. Impact
5. Recommended fix
6. Whether implementation should pause
```
