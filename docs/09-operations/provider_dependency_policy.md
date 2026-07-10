# Provider Dependency Policy

This policy defines which provider dependencies may be bundled in the public framework jar and which dependencies must be supplied by the runner or owning project.

## Policy

- The core framework jar may include CI-verifiable open-source clients needed by supported provider runtimes.
- Oracle and DB2 vendor JDBC drivers are not bundled in public release assets.
- Vendor JDBC drivers must be supplied through `--driver-path`, `--driver-dir`, `REGRESS_DRIVER_PATH`, `usage-kit/drivers/`, or an approved internal provider pack.
- Kafka, IBM MQ, NATS, REST, SOAP, gRPC, and JDBC external endpoints are owner/project-provisioned runtime dependencies unless a future framework slice explicitly adds provisioning.
- Dependency-Check suppressions require owner, reason, affected provider surface, expiry, and upgrade plan.
- Future v0.3 may split provider packs. v0.2.7 remains a single framework jar with external driver loading for Oracle/DB2 JDBC.

## Dependency Inventory

| Dependency | Provider Surface | Bundled in Public Jar | License Review Needed | Security Review Owner | Upgrade Cadence |
| --- | --- | --- | --- | --- | --- |
| Spring Boot / Spring Framework | CLI and framework runtime | Yes | Standard OSS review | Framework maintainer | Each release |
| Jackson | JSON result/report/schema handling | Yes | Standard OSS review | Framework maintainer | Each release |
| SnakeYAML | YAML contracts and samples | Yes | Standard OSS review | Framework maintainer | Each release |
| WireMock | `wiremock_http_mock`, `soap_mock`, `grpc_mock` samples | Yes | Standard OSS review | Framework maintainer | Each release |
| gRPC / Protobuf | `grpc_mock`, `grpc_client` sample runtime | Yes | Standard OSS review | Framework maintainer | Each release |
| Apache Kafka client | `kafka` client provider | Yes | Standard OSS review | Framework maintainer | Each release |
| IBM MQ allclient | `ibm_mq` client provider | Yes | IBM license review required before redistribution changes | Framework maintainer | Each release |
| NATS client | `nats` provider | Yes, when present in dependency tree | Standard OSS review | Framework maintainer | Each release |
| H2 Database | Local JDBC Oracle/DB2 compatibility samples | Yes | Standard OSS review | Framework maintainer | Each release |
| Oracle JDBC driver | External Oracle JDBC native runtime | No | Owner/internal review | Environment owner | Owner controlled |
| IBM DB2 JDBC driver | External DB2 JDBC native runtime | No | Owner/internal review | Environment owner | Owner controlled |

## Suppression Rule

Any vulnerability suppression must include:

- owner
- reason
- affected provider surface
- expiry date
- upgrade or removal plan

Suppressions without those fields are not release-ready.

## Runner Responsibilities

Owners running native external tests must provide runtime endpoints and credentials through Env_Profile-approved values and runner environment variables. Do not place raw secrets, JDBC URLs, broker credentials, or vendor driver binaries in checked-in samples.
