# External Runtime Setup

External runtime samples use Env_Profile bindings. The framework consumes
project-provided endpoints, credentials, and drivers; it does not provision
external systems for these samples.

## JDBC Oracle

- Dependency: reachable Oracle database and approved Oracle JDBC driver.
- Required runner variables are `JDBC_CONNECTION` and `REGRESS_JDBC_APPROVED_SQL_SHA256`. The latter authorizes the sample’s seed, query and cleanup SQL. See [JDBC driver setup and SQL approval](driver_setup.md#external-sql-approval).
- Validate: `java -jar ../spec-driven-auto-regression-{{VERSION}}.jar validate --suite samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml --profile external_oracle`.
- Run: add `--driver-path ./drivers/oracle/ojdbc11.jar`.
- Evidence: JDBC query, seed, cleanup, result JSON, and evidence index.
- Common failures: `JDBC_SQL_NOT_APPROVED`, `JDBC_DRIVER_NOT_FOUND`, `DB_CONNECTION_FAILED`.

## JDBC DB2

- Dependency: reachable DB2 database and approved DB2 JDBC driver.
- Required runner variables are `JDBC_CONNECTION` and `REGRESS_JDBC_APPROVED_SQL_SHA256`. The latter authorizes the sample’s seed, query and cleanup SQL. See [JDBC driver setup and SQL approval](driver_setup.md#external-sql-approval).
- Validate: `java -jar ../spec-driven-auto-regression-{{VERSION}}.jar validate --suite samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml --profile external_db2`.
- Run: add `--driver-path ./drivers/db2/jcc.jar`.
- Evidence: JDBC query, seed, cleanup, result JSON, and evidence index.
- Common failures: `JDBC_SQL_NOT_APPROVED`, `JDBC_DRIVER_NOT_FOUND`, `DB_CONNECTION_FAILED`.

## JDBC MariaDB

Use the native `external_mariadb` profile with `suite_manifest_mariadb.yaml` under
`samples/20-provider-capability-p0/data/jdbc/`. Configure Connector/J,
`JDBC_CONNECTION`, and the five operation-specific
SQL approvals. To exercise this sample during release verification, set
`REQUIRE_EXTERNAL_JDBC=true` and `JDBC_EXTERNAL_DIALECT=mariadb`.
See [driver setup](driver_setup.md#mariadb-example) for the invocation.

## Kafka

- Dependency: reachable Kafka bootstrap servers.
- Required env var: `KAFKA_BOOTSTRAP_SERVERS`.
- Validate: `java -jar ../spec-driven-auto-regression-{{VERSION}}.jar validate --suite samples/20-provider-capability-p0/messaging/kafka/suite_manifest.yaml --profile external_kafka`.
- Run: set broker env vars before `run`.
- Evidence: publish/consume metadata and result JSON.
- Common failures: `KAFKA_CONNECTION_FAILED`, `MESSAGING_TIMEOUT`.

## IBM MQ

- Dependency: reachable IBM MQ queue manager and channel.
- Required env vars: project-specific host, port, queue manager, channel, queue,
  and credentials through Env_Profile secret refs.
- Validate: `java -jar ../spec-driven-auto-regression-{{VERSION}}.jar validate --suite samples/20-provider-capability-p0/messaging/ibm_mq/suite_manifest.yaml --profile external_ibm_mq`.
- Run: set MQ env vars before `run`.
- Evidence: put/get metadata and result JSON.
- Common failures: `IBM_MQ_CONNECTION_FAILED`, `MESSAGING_TIMEOUT`.

## NATS

- Dependency: reachable NATS server.
- Required env var: `NATS_CONNECTION`.
- Validate: `java -jar ../spec-driven-auto-regression-{{VERSION}}.jar validate --suite samples/20-provider-capability-p0/messaging/nats/suite_manifest.yaml`.
- Run: set `NATS_CONNECTION` before external runtime execution.
- Evidence: publish/observe metadata and result JSON.
- Common failures: `NATS_CONNECTION_FAILED`, `MESSAGING_TIMEOUT`.

The native NATS client opens plaintext connections only to `127.0.0.1` or `[::1]`. If the listener's initial `INFO` requires TLS, the client closes the connection before sending credentials or payloads. For a remote broker, configure a runner-managed tunnel that exposes a plaintext NATS listener on loopback, encrypts the remote hop, and verifies the remote peer. Set the profile's environment binding, such as `NATS_CONNECTION`, to the local listener URI (for example, `nats://127.0.0.1:4222`). The reviewed disposable profile accepts only its pinned numeric IPv4 loopback URI.

## Compatibility readiness probes

The runner supplies remote origins and commands outside the RP package. Use HTTPS for remote Kubernetes and HTTP health probes. Set `K8S_API_SERVER` or `VM_HEALTH_URL` in the runner. A Kubernetes bearer token requires HTTPS and `env://K8S_BEARER_TOKEN`. Local HTTP fixtures may use numeric loopback. For a TCP reachability probe, set `VM_TCP_ENDPOINT` to the exact numeric loopback address and port, such as `tcp://127.0.0.1:8080`. A remote TCP service needs a runner-managed encrypted tunnel or a separate HTTPS health check.

Kubernetes CLI probes use the system-installed kubectl. Use a namespace and deployment name or `env://K8S_NAMESPACE` and `env://K8S_DEPLOYMENT`. A pod name may use `env://K8S_POD`. To select a context, set `K8S_CONTEXT` on the runner. The suite can reference that context or omit it. Without a configured context the runner uses its current context.

For SSH, set `VM_SSH_ENDPOINT` and `VM_SSH_COMMAND` on the runner and configure its known_hosts file. For WinRM, set `VM_WINRM_ENDPOINT` to an HTTPS endpoint and configure `VM_WINRM_COMMAND`. The supported SSH query is `systemctl is-active <unit>`. The supported WinRM query is `Get-Service <name>`. Suite host, port, user and command values must match their runner bindings. The optional tool name is limited to `kubectl`, `ssh` or `winrs` for the selected transport.

## REST Endpoint

- Dependency: reachable HTTP endpoint or framework-managed WireMock sample.
- Required binding: `base_url` in Env_Profile.
- Validate: `java -jar ../spec-driven-auto-regression-{{VERSION}}.jar validate --suite samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest.yaml`.
- Run: use the matching profile for managed or external base URL.
- Evidence: request URL, status, response body sample, and result JSON.
- Common failures: `HTTP_REQUEST_FAILED`, `UNEXPECTED_HTTP_STATUS`.

## gRPC Endpoint

- Dependency: reachable gRPC endpoint or framework-managed gRPC mock sample.
- Required binding: target endpoint in Env_Profile.
- Validate: `java -jar ../spec-driven-auto-regression-{{VERSION}}.jar validate --suite samples/20-provider-capability-p0/rpc/grpc_mock/suite_manifest.yaml`.
- Run: use the matching profile for mock or external endpoint.
- Evidence: request metadata, response metadata, and result JSON.
- Common failures: `GRPC_CALL_FAILED`, `UNSUPPORTED_OPERATION`.


Portable source admission and bounded collection use the existing runner with external testware and an external SUT. See [portable runner contracts and limits](portable_runner.md). Native runtime bindings remain unbound/contract_only; module SIT is unchanged.
