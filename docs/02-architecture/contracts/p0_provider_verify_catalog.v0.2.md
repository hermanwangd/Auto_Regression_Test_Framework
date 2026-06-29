# P0 Provider and Verify Catalog v0.2

Status: framework-owned current-stage contract.

This catalog defines the Track A contract baseline. It is not a runtime-complete statement. Track B proves the framework lifecycle with a fake provider; real P0 provider behavior remains placeholder-only until Track C provider scenarios.

| Area | P0 Contract Surface | Runtime Boundary |
|---|---|---|
| HTTP / Mock | `wiremock_http_mock`, `http_stub`, `http_mock_called`, `http_mock_request_body_match` | Contract placeholder and dry-run validation; full WireMock behavior is not Track A. |
| DB | `jdbc_database`, `secret_ref` connection, SQL params binding, Oracle / DB2 dialect metadata, `db_record_exists`, query evidence | Contract placeholder and validation; full JDBC runtime is not Track A. |
| NATS / Event | `nats_messaging`, `event_published`, `event_payload_match`, `consume_from: test_start_time`, subject handling, event evidence | Contract placeholder and validation; full NATS runtime is not Track A. |
| Polling | `polling_observer`, timeout, `poll_interval`, last observed evidence, poll-until-condition semantics | Provider target for observation plus verify contract and evidence shape. |
| JSON / Schema / File | `artifact_compare`, `json_match`, `schema_match`, `ignore_paths`, `file_diff`, `normalize`, `ignore_order` | Provider target for artifact loading plus verify contract and result/evidence shape. |
| Test Data Injection | `data_binding`, `db_seed`, `db_cleanup`, `http_stub` | Fixture contract and dry-run validation only. |
| Reporting | standard result JSON, evidence folder structure, deterministic report output | Contract-complete report shape only. |

Provider naming is mandatory in v0.2 public contracts. Public docs must describe `jdbc_database`, `nats_messaging`, and other runtime targets as Provider Contracts and Provider Instances.
