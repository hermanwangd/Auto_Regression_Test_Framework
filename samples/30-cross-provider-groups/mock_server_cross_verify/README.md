# Mock Server Cross-Provider Verification

This sample proves that framework-managed mock server providers can be verified through real client/execution providers with the v0.3 DSL.

The top-level `suite_manifest.yaml` is a suite group. It aggregates multiple executable child suites and writes:

- `suite_summary.json`
- `suite_summary.yaml`
- `allure-results/*-result.json`
- `allure-results/*-container.json`

The child suites remain split by provider pair so each protocol can be inspected independently:

- `rest_wiremock_http/`: `http_mock -> rest_client`
- `soap_mock_http_client/`: `soap_mock -> rest_client`
- `grpc_mock_grpc_client/`: `grpc_mock -> grpc_client`

Each provider pair has one v0.3 canonical suite manifest:

- `suite_manifest.yaml`

Negative and legacy boundary/failure fixtures are kept under `samples/80-negative/` or `samples/90-compatibility/legacy-v0.2/`, not in the active v0.3 group.

Each test case follows the same lifecycle:

1. Load a mock server stub.
2. Resolve the generated endpoint or target into the client provider.
3. Execute the client provider operation.
4. Observe the mock server request journal.
5. Verify response and request evidence.
6. Produce result JSON and evidence index.

These suites are framework capability evidence only. They do not claim downstream product or release evidence.
