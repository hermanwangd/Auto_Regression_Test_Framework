# External Runtime Setup

External runtime samples use Env_Profile bindings. The framework consumes
project-provided endpoints, credentials, and drivers; it does not provision
external systems for these samples.

## JDBC Oracle

- Dependency: reachable Oracle database and approved Oracle JDBC driver.
- Required env var: `JDBC_CONNECTION`.
- Validate: `java -jar ../spec-driven-auto-regression-{{VERSION}}.jar validate --suite samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml --profile external_oracle`.
- Run: add `--driver-path ./drivers/oracle/ojdbc11.jar`.
- Evidence: JDBC query, seed, cleanup, result JSON, and evidence index.
- Common failures: `JDBC_DRIVER_NOT_FOUND`, `JDBC_CONNECTION_FAILED`.

## JDBC DB2

- Dependency: reachable DB2 database and approved DB2 JDBC driver.
- Required env var: `JDBC_CONNECTION`.
- Validate: `java -jar ../spec-driven-auto-regression-{{VERSION}}.jar validate --suite samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml --profile external_db2`.
- Run: add `--driver-path ./drivers/db2/jcc.jar`.
- Evidence: JDBC query, seed, cleanup, result JSON, and evidence index.
- Common failures: `JDBC_DRIVER_NOT_FOUND`, `JDBC_CONNECTION_FAILED`.

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

For a remote NATS broker, use `env://NATS_CONNECTION` (or `env://NATS_SERVER`) with the complete broker URI supplied by the runner. The native transport uses plaintext TCP. For a broker requiring credentials, configure a secure tunnel or TLS proxy on numeric loopback and supply its local URI through the runner environment. Direct remote bindings accept credential-free URIs. Local test bindings accept numeric loopback. The reviewed NATS profile uses its existing pinned loopback binding.

## Compatibility readiness probes

The operator supplies the remote targets outside the RP package. Kubernetes API probes accept `env://K8S_API_SERVER` as the origin. Supply `namespace_ref` as a Kubernetes namespace name or `env://K8S_NAMESPACE`. Supply `deployment_ref` as a Deployment name or `env://K8S_DEPLOYMENT`. Deployment names may use the `deploy/`, `deployment/`, or `deployments/` prefix. Treat both name bindings as public identifiers because they appear in the API request path. To send a bearer credential, use an HTTPS origin and `env://K8S_BEARER_TOKEN`. HTTP readiness uses `env://VM_HEALTH_URL` for the complete remote URL. For TCP readiness set `VM_TCP_ENDPOINT` to `tcp://host:port` matching the requested destination. Numeric loopback remains available for local probes without bearer credentials.

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
