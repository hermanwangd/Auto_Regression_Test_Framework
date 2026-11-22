# v0.3 Contract Closure Plan

## Objective

Close the v0.3 runtime model before release. A release is blocked until each invariant below has a runtime implementation and an executable regression gate.

## 1. Typed Binding Model

Every Provider Contract binding defines `required`, `value_type`, `reference_kinds`, and `sensitivity`. Env_Profile validation resolves and validates each binding before runtime. There is no implicit public/default binding rule. A `secret_ref` is accepted only when the declared binding sensitivity permits it.

## 2. Evidence Model

Every provider-created artifact is indexed before result creation. Structured JSON/YAML evidence is parsed and recursively masked using Provider Contract `evidence.redact`; text logs use text masking. Symlinks, hardlinks, unindexed files, and paths outside the run directory block execution.

## 3. Output Provenance Model

Every output records its producer test case, step, Provider Contract operation, type, sensitivity, and bindability. `step://` resolves one producer step. `generated://` resolves an explicit producer target and operation; target-wide first-match lookup is prohibited.

## 4. Compatibility Boundary

Typed v0.3 `inputs`, `outputs`, and bindings are the only source of truth. v0.2 lists are generated compatibility views and cannot influence v0.3 compilation. A drift test compares every generated compatibility view with typed metadata.

## Implementation Order

1. Add typed binding schema/model and migrate all Provider Contracts.
2. Replace evidence text-only sanitizer with indexed structured evidence processing.
3. Add output provenance to the canonical execution plan and reference resolver.
4. Generate and verify v0.2 compatibility views.
5. Add negative tests for every invariant and run Maven/release gates.

## Release Gate

`v0.3.0` may be re-tagged only after all four invariant tests, full Maven verification, usage-kit verification, runtime samples, contract drift, secret scan, and GitHub Release workflow pass.
