# Provider Support Matrix

This matrix defines the Framework `0.3.2` public provider support claim. It does not decide downstream Product/RP release readiness; owners still provide AC, Env_Profile, deployment readiness, expected results, and release evidence.

For operation names, binding keys, output refs, and evidence rules, use the
[Provider Contract Catalog](../02-architecture/contracts/provider-contracts/README.md)
and the referenced contract YAML. This matrix is keyed by provider type; v0.3
suite manifests reference contract ids such as `jdbc.v0.3` from
`targets.<target>.provider_contract`.

Status meanings:

- `supported`: public contract exists, runtime exists, executable CI-verifiable usage-kit sample exists, and release verification passes for the available release environment.
- `contract_only`: public contract exists, but runtime execution is unavailable or not release-verified; validation blocks before unsupported dispatch.
- `deprecated`: compatibility-only provider or alias; new artifacts and samples must not use it.
- `unsupported`: not part of the public provider capability surface.

Server or dependency lifecycle words such as native, mock, ephemeral, framework-managed, and external are execution-profile details. They are not public support statuses. `external_native_evidence` is a separate acceptance-evidence field: it records whether a caller-owned external endpoint path has been accepted, without changing `support_status`.

| Provider Type | support_status | external_native_evidence | Release-verifiable sample | Runtime boundary |
| --- | --- | --- | --- | --- |
| `artifact_compare` | `supported` | not_applicable | `samples/20-provider-capability-p0/verification/artifact_compare/` | Common artifact read/diff utility for checked-in samples. |
| `common_verify` | `supported` | not_applicable | `samples/20-provider-capability-p0/verification/common_verify/` | JSON/schema/file assertion-only verifier contract. |
| `external_runner` | `contract_only` | not_applicable | n/a | Contract is documented, but general command execution is not a supported public release path. |
| `grpc_client` | `supported` | caller_acceptance_required | `samples/20-provider-capability-p0/rpc/grpc_mock/` | Unary client stimulus for checked-in gRPC mock capability samples; external native evidence uses `grpc_client_external/`. |
| `grpc_mock` | `supported` | not_applicable | `samples/20-provider-capability-p0/rpc/grpc_mock/` | WireMock gRPC extension, unary only. |
| `http_mock` | `supported` | not_applicable | `samples/20-provider-capability-p0/http/rest_client_with_wiremock/` | v0.3 protocol-level HTTP mock runtime backed by WireMock internals. |
| `ibm_mq` | `supported` | caller_verified | `samples/20-provider-capability-p0/messaging/ibm_mq/` | Local mock/client sample is release-verifiable. Native external execution runs only when queue-manager bindings are configured; the framework does not start IBM MQ. |
| `jdbc` | `supported` | caller_acceptance_required | `samples/20-provider-capability-p0/data/jdbc/` | JDBC fixture/query/cleanup with local H2 Oracle-compatible mode plus external `env://JDBC_CONNECTION` profiles. |
| `kafka` | `supported` | caller_verified | `samples/20-provider-capability-p0/messaging/kafka/` | Local mock/client sample is release-verifiable. Native external execution runs only when broker bindings are configured; the framework does not start Kafka. |
| `kafka_messaging` | `deprecated` | n/a | Compatibility alias only; use `kafka` for new artifacts. |
| `kubernetes_runtime` | `contract_only` | n/a | Contract vocabulary is documented; no release-verifiable runtime sample is published in v0.3.0. |
| `nats` | `supported` | caller_verified | `samples/20-provider-capability-p0/messaging/nats/` | Client runtime consumes owner/project-provided NATS bindings and emits masked event evidence. |
| `polling_observer` | `supported` | not_applicable | `samples/20-provider-capability-p0/verification/polling_observer/` | Observation polling only; it must not retry mutating execute actions. |
| `rest_client` | `supported` | caller_acceptance_required | `samples/20-provider-capability-p0/http/rest_client_with_wiremock/`, `samples/20-provider-capability-p0/http/rest_client_external/`, and `samples/20-provider-capability-p0/rpc/soap_mock/` | HTTP request client provider with Env_Profile-supplied endpoints or generated mock endpoints. |
| `sample_fake_provider` | `unsupported` | not_applicable | `samples/00-getting-started/golden_e2e/` | Framework-owned Golden E2E fake provider, not a public provider capability. |
| `shell_command` | `contract_only` | not_applicable | n/a | Contract vocabulary exists, but command execution requires a later safety-approved release path. |
| `soap_mock` | `supported` | not_applicable | `samples/20-provider-capability-p0/rpc/soap_mock/` | WireMock-backed SOAP HTTP/XML behavior, not a custom SOAP server. |
| `vm_runtime` | `contract_only` | not_applicable | n/a | Contract vocabulary is documented; no release-verifiable runtime sample is published in v0.3.0. |
| `wiremock_http_mock` | `deprecated` | not_applicable | n/a | v0.2 HTTP mock surface backed by WireMock. External `base_url` means an owner-provisioned WireMock-compatible Admin API endpoint, not a generic REST/SUT endpoint. Prefer `http_mock` as the v0.3 protocol capability name; generic project-provisioned HTTP endpoints are consumed through client providers such as `rest_client`. |

## Supported Mixed Suites

Framework `0.3.2` supports only explicit mixed-provider suite paths:

- `samples/10-contract-baseline/mixed_wiremock_jdbc_nats/`: `http_mock` + `rest_client` + `jdbc` + `nats`, framework verification evidence only.
- `samples/20-provider-capability-p0/http/rest_client_with_wiremock/`: `http_mock` + `rest_client`, framework verification evidence unless owner-provided release labels and bindings are present.
- `samples/30-cross-provider-groups/mock_server_cross_verify/`: checked-in REST/SOAP/gRPC mock-client child suites.
- `samples/20-provider-capability-p0/messaging/kafka_ibm_mq_mixed/`: v0.3 Kafka + IBM MQ client sample using one shared Env_Profile across multiple test cases.

Other arbitrary provider combinations remain blocked before dispatch until an explicit runtime path is implemented and tested.

## Release Evidence Rule

A result may set `release_evidence_eligible: true` only when suite evidence policy, test labels, and Env_Profile target evidence bindings all explicitly set `downstream_release_evidence: true` with `evidence_classification: product_release_evidence_candidate`. Any fake, deprecated, `contract_only`, `unsupported`, or framework-owned sample result must be labeled as framework verification or local/CI substitution evidence.
