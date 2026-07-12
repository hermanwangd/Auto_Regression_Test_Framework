# v0.3 External Native Acceptance Closure Plan

## Goal

Close the three v0.3.1 full-acceptance gaps without changing the public CLI,
adding a provider, changing DSL syntax, or making the framework aware of
pi-run. The outcome is a contract-valid JDBC failure path and executable v0.3
external REST and gRPC acceptance artifacts.

## Scope Boundaries

- Keep `ProviderEvidence` and standard result failure fields as the failure
  evidence channel. A failure-only evidence reference is not an operation
  output and must not become bindable DSL data.
- Framework artifacts declare contracts, suites, profiles, and release checks.
  The caller owns external service/Testcontainer lifecycle and supplies only
  environment values defined by the Env_Profile.
- Do not add a command, command-specific mode, pi-run identifier, runtime
  provisioner, or Testcontainers dependency to the framework.
- Do not change `support_status`. Add a separate acceptance-evidence statement
  so `supported` never implies that every native runtime mode was accepted.

## Required Documentation First

Update these documents before implementation:

- `docs/01-specs/05_dsl_v0_3_no_provider_instance_spec.md`: state that external
  client endpoints are Env_Profile bindings and failure evidence is not an
  operation output.
- `docs/02-architecture/08_dsl_v0_3_no_provider_instance_architecture.md`:
  define the boundary between framework artifacts and caller-owned external
  service lifecycle.
- `docs/03-acceptance/05_dsl_v0_3_acceptance_criteria.md`: add JDBC failure,
  external REST, and external gRPC happy/failure/boundary AC.
- `docs/07-validation-evidence/11_dsl_v0_3_test_plan.md`: map each AC to a
  framework test, checked-in suite, and caller-owned external acceptance run.
- `docs/09-operations/provider_support_matrix.md` and
  `docs/09-operations/test_framework_user_guide.md`: distinguish local
  capability evidence from `external_native_verified` evidence.

## Implementation Slices

### 1. JDBC Contract-Valid Failure Result

Modify `JdbcProviderRuntime.failed(...)` to remove `failure_detail_ref` from
`ProviderOperationResult.outputs()`. Preserve the existing failure evidence
entry, safe failure code, reason, and owner action.

Add focused Java tests for an invalid JDBC connection that assert all of:

- command exits non-zero;
- result JSON exposes `DB_CONNECTION_FAILED`, not
  `undeclared_provider_output`;
- `validate-evidence` succeeds;
- indexed failure evidence exists and contains no raw JDBC URL or credential;
- no operation output named `failure_detail_ref` is present.

After framework tests pass, the external caller runs Oracle and DB2 separately
with their existing `external_oracle` and `external_db2` profiles, then proves
seed, query, assertion, cleanup, result, and evidence behavior against each
database. An invalid-connection probe is a failure-path check only; it is not
native CRUD acceptance.

### 2. External REST v0.3 Suite

Add a checked-in v0.3 leaf suite below
`samples/20-provider-capability-p0/http/rest_client_external/` with a standard
`rest_client.v0.3` target and this profile shape:

```yaml
env_profile_id: external_native
targets:
  external_api:
    runtime_mode: native
    bindings:
      base_url: env://REST_BASE_URL
```

The DSL test case uses only `http_request` plus normal assertions and checked-in
expected artifacts. It must not name WireMock. Add a local unit/integration test
that supplies a temporary generic HTTP endpoint and proves request/response
evidence, profile, runtime mode, and no mock-provider dispatch.

The external acceptance caller supplies `REST_BASE_URL`, starts its own generic
HTTP Testcontainer, and independently records a unique request header/path.
The release-jar result/evidence must match that recorded request.

### 3. External gRPC v0.3 Suite

Add a checked-in v0.3 leaf suite below
`samples/20-provider-capability-p0/rpc/grpc_client_external/` with a standard
`grpc_client.v0.3` target and this profile shape:

```yaml
env_profile_id: external_native
targets:
  external_service:
    runtime_mode: native
    bindings:
      target: env://GRPC_TARGET
```

Keep the descriptor and request/expected-response artifacts in the suite. The
framework exercises the current public scope only: plaintext unary gRPC. A
local test supplies a temporary generic unary server and checks profile/runtime
metadata plus client evidence.

The external acceptance caller owns the gRPC Testcontainer, supplies
`GRPC_TARGET`, and independently records the method plus a unique message
identity. Framework response/status evidence must match the server record.

### 4. Release Artifact and Verification Alignment

Extend the existing release verification and usage-kit manifests, not the CLI:

- Normal framework CI validates external REST/gRPC profiles with non-secret,
  unreachable placeholder endpoint values; it does not invoke the runtimes.
- Existing release scripts include the new suites in usage-kit packaging and
  structural/profile validation.
- External execution remains a caller-owned release-jar invocation. It runs
  only when all bindings for that suite have been explicitly supplied; missing
  values block before runtime and never fall back to a local/mock profile.
- No script starts containers or contains pi-run names, paths, or assumptions.

Create a checked-in coverage table with one row for every `supported` provider
contract: declared operations, declared runtime modes, local release gate,
external-native gate where applicable, and evidence status. The table is
reviewed by the existing support-matrix consistency tests. The only new
external-native rows in this slice are JDBC, REST client, gRPC client, NATS,
Kafka, and IBM MQ.

## Acceptance Criteria

The slice is complete only when all conditions hold:

1. Invalid JDBC connection returns `DB_CONNECTION_FAILED` with valid masked
   failure evidence and no undeclared provider output.
2. Oracle and DB2 each pass separate caller-owned external CRUD acceptance.
3. REST external suite validates and runs through the release jar using
   `REST_BASE_URL`; evidence and independent server observation agree.
4. gRPC external suite validates and runs through the release jar using
   `GRPC_TARGET`; evidence and independent server observation agree.
5. Explicit external profiles retain `profile: external_native` and
   `runtime_mode: native`; missing values block before dispatch and never use
   local/mock bindings.
6. Usage-kit verification, schema/contract checks, secret scan, bounded Maven
   tests, local provider suites, and negative suites remain green.
7. The support matrix states whether each public runtime has local-only or
   external-native acceptance evidence; no release claim says full acceptance
   until every required external-native row is verified.

## Delivery Order

1. Commit documentation and AC/test-plan changes.
2. Commit JDBC failure-contract tests and runtime fix.
3. Commit REST external suite, tests, and usage-kit/release-script alignment.
4. Commit gRPC external suite, tests, and usage-kit/release-script alignment.
5. Commit coverage-table/support-guide alignment.
6. Run caller-owned release-asset acceptance and publish its evidence before
   creating a new release tag.
