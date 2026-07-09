# Sample Suites

The sample tree is the public usage-kit surface for framework v0.2. A leaf suite is a directory whose `suite_manifest.yaml` contains `tests[]`. A suite group is a directory whose `suite_manifest.yaml` contains `child_suites[]`.

## Layout

- `00-getting-started/golden_e2e/`: smallest executable framework lifecycle sample.
- `10-contract-baseline/mixed_wiremock_jdbc_nats/`: mixed contract baseline sample.
- `20-provider-capability-p0/`: executable P0 provider capability suites grouped by capability family.
- `30-cross-provider-groups/mock_server_cross_verify/`: suite group for cross-provider mock server verification.
- `40-evidence-reporting/evidence_hardening/`: result and evidence validation fixtures.
- `90-compatibility/dummy_rest/`: compatibility-only fixture, not a supported provider capability gate.

## Rules

- Leaf suites use one `env_profiles/<profile>.yaml` file per runtime profile. Provider values go under `providers.<provider_id>.bindings`.
- Env_Profile policy sections are optional and defaults-backed; samples include them only for external, SIT, or intentionally stricter behavior.
- New samples must not include `execution_profiles/`, `environment_bindings/`, or suite `artifact_roots.execution_profiles` / `artifact_roots.environment_bindings`.
- Suite group child refs must stay inside the suite group directory.
- Runtime-mode provider instance samples labeled `sample_scope: usage_kit_runtime_mode_sample` are coverage artifacts and are not executable targets.
- New documentation should point to the canonical paths above. Legacy release-asset paths are generated only inside the usage-kit zip for one compatibility release.
