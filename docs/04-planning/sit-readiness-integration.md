# SIT readiness vertical slice

Baseline: framework v0.3.2, release/0.3.0 commit bf9e9870c9576b139394404b85b0ad6461a5e0c4.

The shared, persistent SIT environment requires owner-actionable observations of test data, external systems and cross-repository versions before a suite starts. Existing Product Repo readiness checks explain repository artifacts; they do not probe SIT. The v0.3 runtime already compiles Env_Profile, resolves provider support and writes standard Result/Evidence. Its pre-provider boundary is the smallest suitable integration seam.

## Decision

Add an optional Env_Profile preflight with a read-only Java SPI. Leave provider execution and provider contracts unchanged. Provider operations describe test phases; this gate describes whether any phase may begin. A new runner or a general external_runner would duplicate existing execution and incorrectly expand the support matrix. `external_runner`, `kubernetes_runtime` and `vm_runtime` retain their existing contract-only status.

The gate produces READY, MISSING, INVALID, UNKNOWN or BLOCKED observations. Only all READY permits the existing provider loop. Other outcomes mark every test blocked and skip setup, execution, verification and cleanup. Evidence uses the existing run ID, batch ID, profile, plan digest, sanitizer and evidence index. Synthetic adapters are allowed only for local framework verification without downstream release evidence.

## Implementation sequence and evidence

1. Run existing CLI, runtime status and evidence tests before editing (47 passed).
2. Specify service behavior in failing tests, then implement SPI discovery, strict observation validation and one bounded deadline.
3. Specify runtime behavior in failing tests, then connect Env_Profile and evidence to the existing v0.3 runner.
4. Exercise independently compiled replacement JARs, missing data, unhealthy dependencies, version drift, missing/ambiguous adapters, malformed config/results, shared timeout, secret masking and missing evidence.
5. Add paired schema documentation, local-only runnable samples and plugin build/load instructions; perform independent review and repository verification.

## Boundaries

An agent explains deterministic reports and coordinates owner actions. Adapters only observe through approved read-only access. The pipeline selects the approved profile and dependency versions, enforces namespace ownership and suite permissions, and handles locks, seeding, cleanup and deployment separately. READY is a point-in-time observation, never a reservation or permission to mutate shared SIT.

No live SIT, ADO, MES, device or deployment integrations are implemented. No real SIT readiness is established by this slice. Java plugins are trusted in-process code with cooperative cancellation; the framework bounds waiting and worker count but cannot forcibly terminate an uncooperative plugin. Legacy v0.2 profiles explicitly reject this gate rather than ignoring it.
