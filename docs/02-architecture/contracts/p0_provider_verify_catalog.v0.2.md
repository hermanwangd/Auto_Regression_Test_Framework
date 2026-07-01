# P0 Provider and Verify Catalog v0.2

Status: framework-owned current-stage contract.

This catalog defines the v0.2 P0 contract and provider capability baseline. Track B proves the framework lifecycle with a fake provider. Track C rows identify framework-owned executable provider scenarios only inside the stated local/CI verification boundary.

| Area | P0 Contract Surface | Runtime Boundary |
|---|---|---|
| HTTP / Mock | `wiremock_http_mock`, `rest_client`, `http_request`, `http_stub`, `http_mock_called`, `http_mock_request_body_match`, `http_request_response` evidence | Track C executable framework evidence through `samples/provider_capability/wiremock_http_request/` for local/CI happy, failure, and boundary paths. This is not downstream SIT/preprod endpoint release evidence. |
| DB | `jdbc`, `connection.secret_ref`, SQL params binding, Oracle / DB2 dialect metadata, `db_seed`, `db_query`, `db_record_exists`, `db_cleanup`, query evidence | PR-004 runtime capability for local/CI framework evidence; no MariaDB, MongoDB, Testcontainers, or full polling engine. |
| NATS / Event | `nats`, `nats_publish`, `nats_observe`, `event_published`, `event_payload_match`, `consume_from: test_start_time`, subject handling, event evidence | PR-005 runtime capability for local/CI framework evidence; no Kafka, JetStream, durable consumer, or request/reply scope. |
| Polling | `polling_observer`, timeout, `poll_interval`, last observed evidence, poll-until-condition semantics | Provider target for observation plus verify contract and evidence shape. |
| JSON / Schema / File | `artifact_compare`, `json_match`, `schema_match`, `ignore_paths`, `file_diff`, `normalize`, `ignore_order` | Provider target for artifact loading plus verify contract and result/evidence shape. |
| Test Data Injection | DSL `data` catalog, operation `inputs`, `db_seed`, `db_cleanup`, `http_stub` | Fixture contract and dry-run validation only. |
| Reporting | standard result JSON, evidence folder structure, deterministic report output | Contract-complete report shape only. |

Provider naming is mandatory in v0.2 public contracts. Public docs must describe `jdbc`, `nats`, and other runtime targets as Provider Contracts and Provider Instances.
