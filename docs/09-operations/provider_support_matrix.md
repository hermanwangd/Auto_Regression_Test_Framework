# Provider Support Matrix

This matrix defines the Framework `0.2.4` public provider support claim. It does not decide downstream Product/RP release readiness; owners still provide AC, Env_Profile, deployment readiness, expected results, and release evidence.

Status meanings:

- `supported`: public contract exists, runtime exists, executable CI-verifiable usage-kit sample exists, and release verification passes for the available release environment.
- `contract_only`: public contract exists, but runtime execution is unavailable or not release-verified; validation blocks before unsupported dispatch.
- `deprecated`: compatibility-only provider or alias; new artifacts and samples must not use it.
- `unsupported`: not part of the public provider capability surface.

Server or dependency lifecycle words such as native, mock, ephemeral, framework-managed, and external are execution-profile details. They are not public support statuses.

| Provider Type | support_status | Release-verifiable sample | Runtime boundary |
| --- | --- | --- | --- |
| `artifact_compare` | `supported` | `samples/provider_capability/compare/` | Common artifact read/diff utility for checked-in samples. |
| `common_verify` | `supported` | `samples/provider_capability/common_verify/` | JSON/schema/file and observation-style verifier provider. |
| `external_runner` | `contract_only` | n/a | Contract is documented, but general command execution is not a supported public release path. |
| `grpc_client` | `supported` | `samples/provider_capability/grpc_mock/` | Unary client stimulus for checked-in gRPC mock capability samples. |
| `grpc_mock` | `supported` | `samples/provider_capability/grpc_mock/` | WireMock gRPC extension, unary only. |
| `ibm_mq` | `supported` | `samples/provider_capability/ibm_mq/` | CI verifies contract, local provider sample, and external profile validation. Native external execution runs only when queue-manager bindings are configured; the framework does not start IBM MQ. |
| `jdbc` | `supported` | `samples/provider_capability/jdbc/` | JDBC fixture/query/cleanup capability with owner-supplied or approved disposable DB bindings. |
| `kafka` | `supported` | `samples/provider_capability/kafka/` | CI verifies contract, local provider sample, and external profile validation. Native external execution runs only when broker bindings are configured; the framework does not start Kafka. |
| `kafka_messaging` | `deprecated` | n/a | Compatibility alias only; use `kafka` for new artifacts. |
| `kubernetes_runtime` | `contract_only` | n/a | Contract vocabulary is documented; no release-verifiable runtime sample is published in v0.2.4. |
| `nats` | `supported` | `samples/provider_capability/nats/` | Client runtime consumes owner/project-provided NATS bindings and emits masked event evidence. |
| `polling_observer` | `supported` | `samples/provider_capability/polling/` | Observation polling only; it must not retry mutating execute actions. |
| `rest_client` | `supported` | `samples/provider_capability/wiremock_http_request/` | HTTP request client provider with owner/project-supplied endpoint bindings. |
| `sample_fake_provider` | `unsupported` | `samples/golden_e2e/` | Framework-owned Golden E2E fake provider, not a public provider capability. |
| `shell_command` | `contract_only` | n/a | Contract vocabulary exists, but command execution requires a later safety-approved release path. |
| `soap_mock` | `supported` | `samples/provider_capability/soap_mock/` | WireMock-backed SOAP HTTP/XML behavior, not a custom SOAP server. |
| `vm_runtime` | `contract_only` | n/a | Contract vocabulary is documented; no release-verifiable runtime sample is published in v0.2.4. |
| `wiremock_http_mock` | `supported` | `samples/provider_capability/wiremock/` | Framework-managed WireMock HTTP mock; external base URL support is tracked by the v0.2.4 release plan. |

## Supported Mixed Suites

Framework `0.2.4` supports only explicit mixed-provider suite paths:

- `samples/contract_baseline/`: `wiremock_http_mock` + `jdbc` + `nats`, framework verification evidence only.
- `samples/provider_capability/wiremock_http_request/`: `wiremock_http_mock` + `rest_client`, framework verification evidence unless owner-provided release labels and bindings are present.
- `samples/provider_capability/mock_server_cross_verify/`: checked-in REST/SOAP/gRPC mock-client child suites.
- `samples/provider_capability/messaging_mixed/`: Kafka + IBM MQ client capability suite using one shared Env_Profile across multiple test cases.

Other arbitrary provider combinations remain blocked before dispatch until an explicit runtime path is implemented and tested.

## Release Evidence Rule

A result may set `release_evidence_eligible: true` only when suite evidence policy, test labels, and Provider Instance labels all explicitly set `downstream_release_evidence: true` with `evidence_classification: product_release_evidence_candidate`. Any fake, deprecated, `contract_only`, `unsupported`, or framework-owned sample result must be labeled as framework verification or local/CI substitution evidence.
