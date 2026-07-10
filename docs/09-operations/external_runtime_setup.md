# External Runtime Setup

External runtime samples use Env_Profile bindings. The framework consumes
project-provided endpoints, credentials, and drivers; it does not provision
external systems for these samples.

## JDBC Oracle

- Dependency: reachable Oracle database and approved Oracle JDBC driver.
- Required env var: `JDBC_CONNECTION`.
- Validate: `java -jar ../spec-driven-auto-regression-{{VERSION}}.jar validate --suite samples/20-provider-capability-p0/data/jdbc/suite_manifest_external_oracle.yaml --profile external_jdbc_oracle_env_secret_ref`.
- Run: add `--driver-path ./drivers/oracle/ojdbc11.jar`.
- Evidence: JDBC query, seed, cleanup, result JSON, and evidence index.
- Common failures: `JDBC_DRIVER_NOT_FOUND`, `JDBC_CONNECTION_FAILED`.

## JDBC DB2

- Dependency: reachable DB2 database and approved DB2 JDBC driver.
- Required env var: `JDBC_CONNECTION`.
- Validate: `java -jar ../spec-driven-auto-regression-{{VERSION}}.jar validate --suite samples/20-provider-capability-p0/data/jdbc/suite_manifest_external_db2.yaml --profile external_jdbc_db2_env_secret_ref`.
- Run: add `--driver-path ./drivers/db2/jcc.jar`.
- Evidence: JDBC query, seed, cleanup, result JSON, and evidence index.
- Common failures: `JDBC_DRIVER_NOT_FOUND`, `JDBC_CONNECTION_FAILED`.

## Kafka

- Dependency: reachable Kafka bootstrap servers.
- Required env var: `KAFKA_BOOTSTRAP_SERVERS`.
- Validate: `java -jar ../spec-driven-auto-regression-{{VERSION}}.jar validate --suite samples/20-provider-capability-p0/messaging/kafka/suite_manifest.yaml --profile ci_kafka_external`.
- Run: set broker env vars before `run`.
- Evidence: publish/consume metadata and result JSON.
- Common failures: `KAFKA_CONNECTION_FAILED`, `MESSAGING_TIMEOUT`.

## IBM MQ

- Dependency: reachable IBM MQ queue manager and channel.
- Required env vars: project-specific host, port, queue manager, channel, queue,
  and credentials through Env_Profile secret refs.
- Validate: `java -jar ../spec-driven-auto-regression-{{VERSION}}.jar validate --suite samples/20-provider-capability-p0/messaging/ibm_mq/suite_manifest.yaml --profile ci_ibm_mq_external`.
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
