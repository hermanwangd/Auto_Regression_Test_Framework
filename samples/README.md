# Sample Suites

The sample tree is the public Framework v0.3 usage-kit surface. A leaf suite is a directory whose `suite_manifest.yaml` contains `tests[]`. A suite group is a directory whose `suite_manifest.yaml` contains `child_suites[]`.

## Layout

- `00-getting-started/golden_e2e/`: smallest executable v0.3 DSL sample.
- `10-contract-baseline/mixed_wiremock_jdbc_nats/`: mixed HTTP mock/client, JDBC, and NATS v0.3 baseline.
- `20-provider-capability-p0/`: executable P0 provider capability suites grouped by capability family.
- `30-cross-provider-groups/mock_server_cross_verify/`: suite group for REST/SOAP/gRPC mock server verification.
- `40-evidence-reporting/evidence_hardening/`: result and evidence validation fixtures.
- `80-negative/`: expected-failure v0.3 validation and runtime samples.
- `90-compatibility/`: deprecation notes and source-tree-only v0.2 backup material.

## Provider Contract Lookup

Each v0.3 suite manifest declares targets with `provider_contract` ids, such as
`jdbc.v0.3` or `rest_client.v0.3`. Look up the matching YAML, supported
operations, required Env_Profile bindings, and reference samples in
`docs/02-architecture/contracts/provider-contracts/README.md`.

## Rules

- v0.3 leaf suites use `manifest_version: v0.3`, suite-level `targets`, `env_profiles/<profile>.yaml`, and test cases with `dsl_version: v0.3`.
- Test cases reference suite target names directly. They do not use Provider Instance files, `provider_id`, `parameters`, `bind_as`, or legacy `data_binding`.
- Env_Profile files provide runtime bindings for the selected profile. Provider values go under `targets.<target>.bindings`.
- Suite group child refs must stay inside the suite group directory.
- New public docs and scripts must point to the canonical paths above.
- Deprecated v0.2 executable artifacts are source-tree backups under `90-compatibility/legacy-v0.2/` and do not ship as current v0.3 usage-kit samples.

See `90-compatibility/DEPRECATED_PATHS.md` for migration hints from removed staging and v0.2 alias paths.
