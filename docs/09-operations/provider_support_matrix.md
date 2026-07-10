# Provider Support Matrix

This matrix defines the Framework `0.2.7` public provider support claim. It does not decide downstream Product/RP release readiness; owners still provide AC, Env_Profile, deployment readiness, expected results, and release evidence.

Status meanings:

- `supported`: public contract exists, runtime exists, executable CI-verifiable usage-kit sample exists, and release verification passes for the available release environment.
- `contract_only`: public contract exists, but runtime execution is unavailable or not release-verified; validation blocks before unsupported dispatch.
- `deprecated`: compatibility-only provider or alias; new artifacts and samples must not use it.
- `unsupported`: not part of the public provider capability surface.

Server or dependency lifecycle words such as native, mock, ephemeral, framework-managed, and external are execution-profile details. They are not public support statuses.

| Provider Type | support_status | Release-verifiable sample | Runtime boundary |
| --- | --- | --- | --- |
| `artifact_compare` | `supported` | `samples/20-provider-capability-p0/verification/artifact_compare/` | Common artifact read/diff utility for checked-in samples. |
| `common_verify` | `supported` | `samples/20-provider-capability-p0/verification/common_verify/` | JSON/schema/file and observation-style verifier provider. |
| `external_runner` | `contract_only` | n/a | Contract is documented, but general command execution is not a supported public release path. |
| `grpc_client` | `supported` | `samples/20-provider-capability-p0/rpc/grpc_mock/` | Unary client stimulus for checked-in gRPC mock capability samples. |
| `grpc_mock` | `supported` | `samples/20-provider-capability-p0/rpc/grpc_mock/` | WireMock gRPC extension, unary only. |
| `ibm_mq` | `supported` | `samples/20-provider-capability-p0/messaging/ibm_mq/` | CI verifies contract, local provider sample, and external profile validation. Native external execution runs only when queue-manager bindings are configured; the framework does not start IBM MQ. |
| `jdbc` | `supported` | `samples/20-provider-capability-p0/data/jdbc/` | JDBC fixture/query/cleanup plus Oracle/DB2 CRUD capability samples. `local_jdbc` uses approved local H2 Oracle/DB2 modes; native external execution is split into single-provider Oracle or DB2 suites selected by `JDBC_EXTERNAL_DIALECT`, using `connection.secret_ref: env://JDBC_CONNECTION` and runner env var `JDBC_CONNECTION`. |
| `kafka` | `supported` | `samples/20-provider-capability-p0/messaging/kafka/` | CI verifies contract, local provider sample, and external profile validation. Native external execution runs only when broker bindings are configured; the framework does not start Kafka. |
| `kafka_messaging` | `deprecated` | n/a | Compatibility alias only; use `kafka` for new artifacts. |
| `kubernetes_runtime` | `contract_only` | n/a | Contract vocabulary is documented; no release-verifiable runtime sample is published in v0.2.7. |
| `nats` | `supported` | `samples/20-provider-capability-p0/messaging/nats/` | Client runtime consumes owner/project-provided NATS bindings and emits masked event evidence. |
| `polling_observer` | `supported` | `samples/20-provider-capability-p0/verification/polling_observer/` | Observation polling only; it must not retry mutating execute actions. |
| `rest_client` | `supported` | `samples/20-provider-capability-p0/http/rest_client_with_wiremock/` | HTTP request client provider with owner/project-supplied endpoint bindings. |
| `sample_fake_provider` | `unsupported` | `samples/00-getting-started/golden_e2e/` | Framework-owned Golden E2E fake provider, not a public provider capability. |
| `shell_command` | `contract_only` | n/a | Contract vocabulary exists, but command execution requires a later safety-approved release path. |
| `soap_mock` | `supported` | `samples/20-provider-capability-p0/rpc/soap_mock/` | WireMock-backed SOAP HTTP/XML behavior, not a custom SOAP server. |
| `vm_runtime` | `contract_only` | n/a | Contract vocabulary is documented; no release-verifiable runtime sample is published in v0.2.7. |
| `wiremock_http_mock` | `supported` | `samples/20-provider-capability-p0/http/wiremock_http_mock/` | Framework-managed WireMock HTTP mock; project-provisioned external base URL support is validated by release tests. |

## Supported Mixed Suites

Framework `0.2.7` supports only explicit mixed-provider suite paths:

- `samples/10-contract-baseline/mixed_wiremock_jdbc_nats/`: `wiremock_http_mock` + `jdbc` + `nats`, framework verification evidence only.
- `samples/20-provider-capability-p0/http/rest_client_with_wiremock/`: `wiremock_http_mock` + `rest_client`, framework verification evidence unless owner-provided release labels and bindings are present.
- `samples/30-cross-provider-groups/mock_server_cross_verify/`: checked-in REST/SOAP/gRPC mock-client child suites.
- `samples/20-provider-capability-p0/messaging/kafka_ibm_mq_mixed/`: Kafka + IBM MQ client capability suite using one shared Env_Profile across multiple test cases.

Other arbitrary provider combinations remain blocked before dispatch until an explicit runtime path is implemented and tested.

## Release Evidence Rule

A result may set `release_evidence_eligible: true` only when suite evidence policy, test labels, and Provider Instance labels all explicitly set `downstream_release_evidence: true` with `evidence_classification: product_release_evidence_candidate`. Any fake, deprecated, `contract_only`, `unsupported`, or framework-owned sample result must be labeled as framework verification or local/CI substitution evidence.
