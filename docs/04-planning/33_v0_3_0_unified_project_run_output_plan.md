# v0.3.0 Unified Project Run Output Plan

## Goal

Make `target/regression/` the only user-facing runtime output root. Provider/runtime dispatch details must not appear in project artifact paths.

## Contract

```text
target/regression/<suite_id>/<batch_id>/<run_id>/
  result.json
  suite_summary.json
  suite_summary.yaml
  evidence_index.yaml
  evidence/
  allure-results/                 # when enabled
  children/<child_suite_id>/<child_run_id>/  # suite-group only
```

Every entered v0.3 leaf writes `result.json` and `suite_summary.json`. A suite group writes the same parent artifacts and its child runs only below `children/`. Blocked preflight writes no run directory.

## Implementation

1. Change CLI dispatch roots from `target/provider-capability/` and `target/suite-groups/` to `target/regression/`.
2. Remove runtime/provider family path segments from leaf output roots.
3. Preserve batch/run identity, containment checks, evidence refs, and group child isolation.
4. Update CLI tests, release scripts, acceptance criteria, test plan, and user guide.
5. Keep old paths unsupported as generated outputs; samples and compatibility input paths are unchanged.

## Verification

Run a direct v0.3 leaf and a suite group. Assert all canonical artifacts are under `target/regression/`, group children are under the parent `children/` directory, and no new artifacts appear beneath the former output roots.
