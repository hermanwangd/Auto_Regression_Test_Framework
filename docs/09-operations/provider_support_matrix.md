# Provider Support Matrix

This matrix defines Framework `0.2.2` production support by provider runtime mode. It does not decide downstream Product/RP release readiness; owners still provide AC, Env_Profile, deployment readiness, expected results, and release evidence.

Status meanings:

- `production-ready`: framework can execute this mode and publish standard result/evidence when the owner provides valid Env_Profile bindings.
- `framework-verification-only`: executable for local/CI substitution or framework capability proof, but not downstream SIT/preprod release evidence.
- `contract-only`: valid vocabulary and schema boundary, but blocked before provider dispatch in this release.
- `escape-hatch`: executable only with explicit provider safety approval.
- `deprecated`: compatibility only; new artifacts must use the canonical provider type.

| Provider Type | Native | Mock | Ephemeral | Production Boundary |
| --- | --- | --- | --- | --- |
| `rest_client` | production-ready | framework-verification-only | n/a | Native evidence requires owner-provided endpoint bindings and readiness context. |
| `grpc_client` | production-ready for unary calls | framework-verification-only | n/a | Streaming is outside v0.2.2. |
| `wiremock_http_mock` | n/a | framework-verification-only | n/a | Local/CI REST dependency substitute only. |
| `soap_mock` | n/a | framework-verification-only | n/a | WireMock-backed SOAP mock; not a custom SOAP server. |
| `grpc_mock` | n/a | framework-verification-only | n/a | WireMock gRPC extension, unary only. |
| `jdbc` | production-ready with owner DB binding | n/a | production-ready for approved disposable DB/profile | Real Oracle/DB2 release confidence still requires owner-provided pilot/SIT evidence. |
| `nats` | production-ready with owner broker binding | framework-verification-only | production-ready for approved local/CI provisioner | Mock/ephemeral evidence is not downstream release evidence. |
| `kafka` | contract-only | framework-verification-only | contract-only | Native broker and Testcontainer execution are future runtime slices. |
| `ibm_mq` | contract-only | framework-verification-only | contract-only | Native queue-manager and Testcontainer execution are future runtime slices. |
| `kafka_messaging` | deprecated | deprecated | deprecated | Compatibility alias only; use `kafka`. |
| `artifact_compare` | production-ready | n/a | n/a | Common verifier utility for JSON/schema/file artifacts. |
| `polling_observer` | production-ready | n/a | n/a | Observation polling only; it must not retry mutating execute actions. |
| `kubernetes_runtime` | production-ready for readiness/log probes | framework-verification-only | n/a | Requires owner-provided cluster context and non-secret refs. |
| `vm_runtime` | production-ready for readiness/log probes | framework-verification-only | n/a | Requires owner-provided host/user refs and safety controls. |
| `shell_command` | escape-hatch | framework-verification-only | n/a | Command-capable providers require explicit safety policy. |
| `external_runner` | escape-hatch | framework-verification-only | n/a | Approval is provider safety approval, not release approval. |
| `sample_fake_provider` | n/a | framework-verification-only | n/a | Golden E2E framework verification only. |

## Release Evidence Rule

Only native or approved ephemeral modes marked `production-ready` can contribute framework runtime evidence that may be reviewed alongside downstream RP release evidence. Any mock, fake, deprecated, or contract-only result must be labeled as framework verification or local/CI substitution evidence.
