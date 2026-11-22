# Deprecated Sample Paths

The v0.3 usage kit no longer exposes staging or root-level v0.2 alias paths.

| Deprecated path | Replacement |
|---|---|
| `samples/v0_3_dsl/golden` | `samples/00-getting-started/golden_e2e` |
| `samples/v0_3_dsl/http_mock_rest_client` | `samples/20-provider-capability-p0/http/rest_client_with_wiremock` |
| `samples/v0_3_dsl/data/jdbc` | `samples/20-provider-capability-p0/data/jdbc` |
| `samples/v0_3_dsl/messaging/nats` | `samples/20-provider-capability-p0/messaging/nats` |
| `samples/v0_3_dsl/messaging/kafka` | `samples/20-provider-capability-p0/messaging/kafka` |
| `samples/v0_3_dsl/messaging/ibm_mq` | `samples/20-provider-capability-p0/messaging/ibm_mq` |
| `samples/v0_3_dsl/messaging/kafka_ibm_mq_mixed` | `samples/20-provider-capability-p0/messaging/kafka_ibm_mq_mixed` |
| `samples/v0_3_dsl/rpc/soap_mock_rest_client` | `samples/20-provider-capability-p0/rpc/soap_mock` |
| `samples/v0_3_dsl/rpc/grpc_mock_grpc_client` | `samples/20-provider-capability-p0/rpc/grpc_mock` |
| `samples/v0_3_dsl/verification/common_verify` | `samples/20-provider-capability-p0/verification/common_verify` |
| `samples/v0_3_dsl/verification/artifact_compare` | `samples/20-provider-capability-p0/verification/artifact_compare` |
| `samples/v0_3_dsl/verification/polling_observer` | `samples/20-provider-capability-p0/verification/polling_observer` |
| `samples/v0_3_dsl/multi_test` | `samples/20-provider-capability-p0/verification/multi_test_shared_env` |
| `samples/v0_3_dsl/negative/bindings/invalid_runtime_mode` | `samples/80-negative/bindings/invalid_runtime_mode` |
| `samples/v0_3_dsl/negative/bindings/missing_required_binding` | `samples/80-negative/bindings/missing_required_binding` |
| `samples/v0_3_dsl/negative/bindings/unknown_binding_key` | `samples/80-negative/bindings/unknown_binding_key` |
| `samples/v0_3_dsl/negative/legacy_fields/data_binding` | `samples/80-negative/legacy-fields/data_binding` |
| `samples/v0_3_dsl/negative/operations/unsupported_input` | `samples/80-negative/operations/unsupported_input` |
| `samples/v0_3_dsl/negative/operations/unsupported_operation` | `samples/80-negative/operations/unsupported_operation` |
| `samples/v0_3_dsl/negative/refs/forward_step_ref` | `samples/80-negative/refs/forward_step_ref` |
| `samples/v0_3_dsl/negative/refs/invalid_artifact_ref` | `samples/80-negative/refs/invalid_artifact_ref` |
| `samples/v0_3_dsl/negative/refs/symlink_escape` | `samples/80-negative/refs/symlink_escape` |
| `samples/v0_3_dsl/negative/runtime/cleanup_failure_preservation` | `samples/80-negative/runtime/cleanup_failure_preservation` |
| `samples/v0_3_dsl/negative/secrets/raw_secret_dsl` | `samples/80-negative/secrets/raw_secret_dsl` |
| `samples/v0_3_dsl/negative/secrets/raw_secret_env` | `samples/80-negative/secrets/raw_secret_env` |
| `samples/v0_3_dsl/negative/target_resolution/missing_env_profile_target` | `samples/80-negative/target-resolution/missing_env_profile_target` |
| `samples/v0_3_dsl/negative/target_resolution/missing_provider_contract` | `samples/80-negative/target-resolution/missing_provider_contract` |
| `samples/v0_3_dsl/negative/target_resolution/unknown_target` | `samples/80-negative/target-resolution/unknown_target` |
| `samples/golden_e2e` | `samples/00-getting-started/golden_e2e` |
| `samples/contract_baseline` | `samples/10-contract-baseline/mixed_wiremock_jdbc_nats` |
| `samples/provider_capability` | `samples/20-provider-capability-p0` |
| `samples/evidence_hardening` | `samples/40-evidence-reporting/evidence_hardening` |

`samples/90-compatibility/legacy-v0.2/` is retained only in the source tree for reference and is not a current usage-kit sample root.
