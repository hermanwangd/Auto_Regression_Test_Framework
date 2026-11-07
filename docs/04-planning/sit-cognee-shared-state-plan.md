# Swarm product-testing module implementation plan

The filename is retained for review continuity; the scope is now product testing across environments. Shared SIT is one optional framework extension, not the module identity.

**Implementation status:** The local vertical slice is implemented in the isolated checkout. [Operations and actual capability boundaries](../09-operations/product-testing-module.md) distinguish implemented behavior from extension targets and validation gaps. The checklist below records the design plan; final verification/packaged evidence is recorded separately under `evidence-v2/` and the delivery report. Later independent review tightened protected module approval pins, pinned workspace/project scope, default-provider packaging and explicit reviewed-entry rejection.

> For agentic workers: implement this plan task by task with the executing-plans workflow after coordinator review. The latest user instruction allows implementation and validation in parallel with Kimi cross-check. Independent findings are incorporated below; the coordinator-selected Astra max implementation worker owns subsequent code edits. This author remains plan-only to avoid overlap.

**Goal:** Deliver a pluggable Swarm product-testing module that consumes governed Cognee context and reviewed test artifacts, invokes the existing Test Framework, and imports its validated results/evidence; prove the integration locally and keep shared-SIT policy as an optional framework extension.

**Architecture:** A small application-composed ProductTestingModule coordinates the product-test workflow without misusing Swarm runtime execution receipts. Swarm retrieves and governs knowledge, analyzes requirements/cross-repository impact, proposes/reviews tests and analyzes evidence. The framework verifies a pinned reviewed handoff, takes a private snapshot of approved inputs, observes prerequisites through its existing readiness SPI, enforces explicit provider policy, and computes test verdicts through its existing runner/assertions. Cognee remains a candidate-retrieval dependency of Swarm, never a readiness or verdict authority.

**Tech stack:** Existing Java 17, Maven, Jackson/SnakeYAML, v0.3 execution plans, provider adapters and Result/Evidence; no added HTTP/RAG SDK, service, runner or infrastructure.

## 1. Existing facts and decision

**IMPLEMENTED:** The preceding slice contains a Java ServiceLoader readiness SPI, fixture adapter, READY/MISSING/INVALID/UNKNOWN/BLOCKED observations, versions/evidence, bounded preflight, standard evidence linkage, and local-only simulation restrictions. Existing providers still execute test phases. The coordinator reports 1,270 tests passing in full verification; that is evidence for the preceding slice, not this proposal.

**INSPECTED EXISTING SWARM SOURCE:**

- `engcim-swarm/src/main/java/com/featuredeliveryintelligence/fdi/orchestration/CogneeSearchClient.java`: loopback HTTP only, CHUNKS search, 10-second total bound, response size limit, dataset scoping and source-bound candidate metadata. Candidate fields include dataset/document/source IDs, positive source revision, provider revision, source ref, workspace/project refs, content SHA-256 and chunk count. IndexSchema.version is not treated as source revision.
- `.../application/Dev204Cli.java`: `dev204-knowledge-consume --phase selection` performs governed provider readback before candidate narrowing; selection receipts contain context, sourceSnapshotSha256, selectionReceiptSha256 and optional Cognee trace. Feedback replays exact snapshots and receipts. A successful HTTP response does not verify the deployed Cognee version (`UNVERIFIED_BY_HTTP_CLIENT`).
- `.../orchestration/SwarmKnowledgeGateway.java`: checks scope, exact versions, governance, freshness and applicability. Its decision process remains there.
- `engcim-swarm/docs/rc10/RC10-KNOWLEDGE-DESIGN.md` §32 pins Cognee 1.6.2 source commit `ba3631f2ed363a6ea50d649c34c56885af6b36fe`; deployed company instance identity remains unverified.

The inspected Swarm Git base is `fe80374f740ed3c604836431daca5c32e92cf844`. The source checkout is dirty and read-only; that base does not describe all current bytes. Reproducibility records both the base and actual inspected file digests:

| File | SHA-256 |
| --- | --- |
| CogneeSearchClient.java | 506f163a5294f68881996d38f06de47ae6aa7ee2321954f77535db5c0cfc4878 |
| Dev204Cli.java | ff0669763d5070e834ffba893a2ebe681a20e868c85fc0b9b595256e468069b8 |
| SwarmKnowledgeGateway.java | d2fb8408676d4de60d0f38528756558c0f669c61168b107ed1ea46aee23018d0 |

**PROPOSED decision:** Implement an offline consumer of a source-pinned reviewed artifact exported alongside that existing Swarm selection receipt. Do not copy its HTTP client, repeat knowledge governance, infer authority from similarity, or launch Swarm as a subprocess from the test runner. A minimal packaging command in the framework checks and hashes local artifacts; it does not generate tests, approve them or create knowledge decisions.

Options rejected: a second Cognee client in the runner (duplicates authority and network behavior); a general agent/external runner (bypasses framework contracts); adding only prose or receipt links (does not bind execution to reviewed bytes); pretending a local mutex protects distributed SIT (false guarantee).

## 1A. Inspected Swarm extension seam and proposed usable module

The existing `RuntimeBindingPort.execute(MissionExecutionEnvelope)` is constructor-injected into `SwarmMissionGateway`, but it returns `BindingReceipt`, which requires `multicaExecutionRef`. `WorkItemResult` and `VerificationResult` are deliberately distinct; `MissionFlowTests.T12_resultTypesRemainDistinct` preserves that distinction. This is **not a compatible local-framework return type**. Do not fabricate a Multica execution ID, fill it with a local locator, or claim the binding is a live Swarm runtime integration.

`FdiApplication.main` already delegates a narrow command family to `Dev204Cli.handles(args)` before Spring startup. Use the analogous application composition seam for the product-test module, with its **own** typed request/receipt and an injected `FrameworkExecutionPort`. No general module registry is needed. Keep Dev204/Cognee behavior intact.

Source pins for inspected boundaries: RuntimeBindingPort SHA-256 `4886053e3cd2f15b40b5445177cde1739267dc2591c22ac6eab1cb8be9cc0386`; SwarmMissionGateway `71048e8fcf1965296d4881a9ff649d4e53e2cd0dbdcab7235e458efb9040fb32`; MissionExecutionEnvelope `af8c28a029813e0d55509234213f0f8a95b2536b02a62144d2ad6559a9f16e90`. These augment the base/knowledge pins above.

Deliver a small source/JAR module under `integrations/swarm-product-test/`, in package `com.featuredeliveryintelligence.fdi.producttest`:

- `ProductTestingModule`: constructor-injected `FrameworkExecutionPort`; accepts an immutable `ProductTestRequest`, verifies supported capabilities and reviewed provenance, invokes the port once, and returns `ProductTestReceipt`. Correlate mission/request/revision/repository/source identities; do not infer commands from mission goal prose. Swarm analysis/review roles call the module through this explicit contract.
- `ProductTestRequest`: versioned structured input with correlation refs/revision, exact repository revisions and requirement-to-test mapping; test level/type/environment tuple; explicit knowledge mode; suite/profile; expected handoff digest; approved artifact/output locators. It never accepts an arbitrary executable, command string, credentials or RAG-supplied argv. Framework binary/classpath allowlist and timeout are deployment configuration, not request content.
- `FrameworkExecutionPort` and `FrameworkCliTransport`: a thin bounded `ProcessBuilder` caller of existing framework `run`, `validate-evidence` and `report`, using fixed argv, approved/pinned framework artifact and private output working directory. It never executes test steps or evaluates assertions. Propagate the handoff pin, use one total deadline and bounded outputs, and retain timeout/uncertainty without blind retries. The public `RegressionCommand.execute` is an alternative for in-process tests, not a second engine.
- `ProductTestReceipt`: execution correlation, validated framework result/evidence/summary paths and hashes, verbatim framework verdict, evidence-validation status and separate module execution outcome. Validate identity, path containment and fresh invocation provenance before acceptance. Exit0, completion, a stale result, malformed output, or absent evidence never grants PASS or verification.
- `ProductTestCli.handles(args)`: handles only `product-test run --request <json>` with fixed configured framework binding; returns false for other commands. Provide a two-line early composition hook beside Dev204 in an isolated `FdiApplication` overlay/patch. Do not modify the dirty original. Existing `dev204-*` continues unchanged. A direct module CLI main can execute the same module for local installation/testing without Spring startup.

The receipt has no `multicaExecutionRef`. Preserve completion, framework verdict and Swarm's higher-level verification as separate facts. The module does not automatically create `VerificationResult` or close a mission; Swarm's existing review/control consumes validated evidence and makes that separate decision.

Define `product-test-request.v1` and `product-test-receipt.v1` as module-local JSON contracts under `integrations/swarm-product-test/contracts/`; they do not expand the framework root schema allowlist. The module delivers deterministic handoff/invocation/import. Requirement/cross-repository analysis, reviewed test proposals and evidence interpretation remain existing Swarm agent responsibilities documented in the capability guide, not a newly automated LLM loop. The existing framework decides regression outcomes from reviewed oracle bytes.

**Capability matrix:** Separate test level/type from environment. V1 executes only `component / regression / local` with the existing fake provider. The optional same-JVM shared-state fixture uses that tuple to verify policy mechanics. Integration/system/acceptance levels, performance/security types and SIT/preprod/live environments are documented as extension targets, not supported execution. Unsupported tuples block before framework dispatch. Calling local execution SIT does not prove SIT support.

**Knowledge modes:** The request and pinned handoff specify `required` or `not_used`. Required uses the existing governed Swarm/Cognee receipt and blocks on absence/unavailability/mismatch. Not_used explicitly records no RAG use and still requires reviewed test/oracle provenance. It is suitable for already-reviewed local tests requiring no retrieval. No implicit optional mode or fallback is added; required cannot turn into not_used after a failure. Neither mode makes knowledge a verdict authority.

**Isolated build:** No prebuilt Swarm artifact is assumed. Build/test under the authorized workspace only. Verify source pins and compile only the required read-only API/application dependency closure into task `target/`, using cached dependencies. For the application hook, copy only `FdiApplication.java` to a tiny overlay, apply the hook there and test it with the module; do not clone/copy the whole repository or write to the original. Package only module classes; host Swarm APIs remain provided by the host. Record the real entrypoint/Dev204 compatibility harness and packaged-framework local run as local composition evidence, not installed/native/company Swarm acceptance.

## 2. Small contract surface

Add one environment-independent `product_test` object to v0.3 Env_Profile, independent of `sit_readiness`; only its optional shared_state extension requires shared-environment checks:

```yaml
product_test:
  contract_version: product-test.v1
  test_level: component          # v1 executable capability: component + regression + local
  test_type: regression
  environment_stage: local
  knowledge_mode: required       # required | not_used; no silent fallback
  reviewed_handoff_ref: handoff/reviewed-tests.json
  provider_modes:
    sample_runtime: simulated
  # Optional only when actual readiness checks are configured:
  dependency_modes:
    order_dataset: simulated
  # Optional shared-state extension, exercised locally with a fake store:
  shared_state:
    environment_id: local-shared-demo
    namespace: team-demo-orders
    owner: team-demo
    data_access: read_only       # default; read_only | owned
    cleanup: none               # default; none | owned_only
```

All values have strict types and bounded lengths; unknown fields/enums, duplicate IDs, nonunique references, omitted/mismatched mode coverage and explicit nulls block. Presence requires a reviewed handoff, not SIT readiness. A local component test can omit both readiness and shared_state. When readiness is configured, every check must have exactly one dependency mode; every executed provider target always has exactly one provider mode. The optional shared_state extension additionally requires readiness. A false stage label cannot bypass the independently pinned profile or capability checks. Compatibility applies only when **both** `product_test` and the invocation digest are absent. Legacy v0.2 rejects the new field just as it rejects `sit_readiness`.

### Invocation cannot downgrade the reviewed workflow

Capture a present `REGRESS_REVIEWED_HANDOFF_SHA256` once as an immutable invocation requirement before any `run` route or provider/driver dispatch. Presence includes an empty or malformed value, which blocks rather than behaving as absent. With that requirement, only a single v0.3 leaf suite with the exact pinned suite/profile and valid `product_test` may proceed. Removing/nulling/changing policy or profile cannot fall back to normal execution. Reject suite groups, legacy suites, package-run routes and unknown manifests **before any child/provider dispatch** in this first slice; do not implement reviewed group aggregation. `prepare-product-test-handoff` is a nonexecuting preparation command and does not confer this requirement or permission.

Put the common guard in `RegressionCommand` before route selection, repeat it at `runSuite`/dispatch boundaries, and pass the immutable requirement to the v0.3 runtime entry point for embedded callers. Direct reviewed calls must also reject missing policy. No downstream component re-reads the environment or converts a required workflow to optional. Tests use spies at all dispatch seams and require zero invocations for every downgrade attempt. Ordinary unreviewed invocations keep their current routes.

### Reviewed handoff, version `product-test-reviewed-handoff.v1`

Use JSON with strict duplicate-key detection and a maximum 2 MiB. It contains:

- `suite_id`, `profile`, `review_ref`, `reviewed_by`, `reviewed_at`, `valid_until` (UTC), `framework_baseline`, and `swarm_source` (base revision plus actual source file digests). These are traceability facts, not credentials or approval tokens.
- `cognee_source`: expected source version/commit; `runtime_version_verified: false` for the fake demonstration and current HTTP client. Never turn the expected source pin into a claim about a running server.
- `selection_receipt`: relative file path and raw-byte SHA-256 of the unmodified existing Dev204 selection receipt. Retain its `selectionReceiptSha256`, `sourceSnapshotSha256`, Cognee candidate digest, dataset ID and governed selected source identities as provenance. This field is required only in knowledge_mode required and absent for not_used. The importer consumes this envelope; it does not reimplement Swarm's canonical receipt replay algorithm or fetch a source.
- `knowledge_sources` (required mode only): selected source ID/revision/provider revision/ref/content digest, exact repository revision map, plus reviewed `test_case_ids` and requirement refs. Match each to the receipt's governed selected entries and candidate metadata; candidate-only or excluded entries cannot be used. Full snapshot/receipt replay and governance remain Swarm's upstream responsibility.
- `reviewed_files`: every supported manifest, selected profile, test definition, static fixture and static expected-result/oracle, each with suite-relative path, role and SHA-256. `reviewed_contracts` explicitly maps each used provider-contract ID to a suite-relative contract file and raw-byte SHA-256; the local reviewed bundle must contain those contract copies. Oracle provenance identifies an authoritative requirement/source and review ref. RAG text is never an expected result. Reject missing coverage rather than guessing an oracle.

For knowledge_mode required, consume exactly the inspected Dev204 receipt shape, with `phase: selection`, `status: ELIGIBLE_CONTEXT`, `cogneeSelection.searchType: CHUNKS` and `searchStatus: SEARCHED`. No synonym guessing or older receipt migration is added. Concrete field mapping:

| Handoff provenance / check | Exact receipt fields |
| --- | --- |
| Selected source identity and version | `context.selected[].recordKey` / `recordVersion` equal `cogneeSelection.candidateSearch.candidates[].sourceId` / `sourceRevision` |
| Provider revision | selected `providerRevision` equals candidate `providerRevision` |
| Source locator | candidate `sourceRef` is a member of selected `proposal.sourceRefs`; retain selected `knowledgeRef` separately |
| Workspace/project scope | candidate `workspaceRef` equals `context.request.workspaceRef` and `context.readWorkspaceRef`; candidate `projectRef` equals `context.request.knowledgeProjectRef` and `context.readProjectRef` |
| Repository/test applicability | retain selected `repositoryRevisions`, `applicableProjectRefs`, and request `currentRepositoryRevisions` / `consumerProjectRef`; require the reviewed mapping to match those exact receipt values |
| Governance provenance | preserve selected `proposalDigest`, `decisionRecordKey`, `decisionRecordVersion`, `decisionProposalDigest`, `governance.decisionRef` and freshness fields; never manufacture a decision |
| Retrieval provenance | retain candidate `datasetId`, `documentId`, `contentSha256`, `chunkCount` and `cogneeSelection.datasetId`, `candidateSearchSha256`, `querySha256`, `providerVersion` |
| Exclusions | reject any requested source in `context.excluded[].recordKey` / `knowledgeRef`, ambiguous duplicate matches, and candidates lacking an exact selected match |

Candidate `contentSha256` describes retrieved content; it is **not** selected `proposalDigest`. Preserve both without conflating them or claiming local reconstruction of authoritative content. The raw receipt digest binds this exact record of upstream governance; this importer is not a new governance or receipt-replay engine.

The approved handoff digest comes independently from the controlled invocation as `REGRESS_REVIEWED_HANDOFF_SHA256`. It must equal the SHA-256 of the exact handoff bytes. A digest embedded inside the same handoff or profile cannot self-approve it. This environment value is an integrity anchor supplied by a trusted pipeline/reviewer, **not authentication or authorization**; an attacker controlling the process environment, code or protected pipeline can replace it. Actual approval identity, signing, access and deployment remain pipeline responsibilities. V1 supports one reviewed handoff and one v0.3 leaf suite per process; multiple suites use separate invocations. A present invocation pin remains mandatory even if the selected profile has no handoff field.

`prepare-product-test-handoff --suite <manifest> --profile <id> --review-input <json> --selection-receipt <json> --out <json>` packages existing, reviewed local inputs and emits the digest for review. `review-input` supplies review metadata, source/test mappings and version pins; the command never manufactures those facts. Its output is **prepared, not approved**. The coordinator/pipeline reviews it and separately supplies the digest to `run`. No generated tests or automatic knowledge ingestion are added.

When knowledge_mode is required, an absent/unavailable Cognee receipt, non-ELIGIBLE_CONTEXT status, missing selected records, expired review, revision mismatch or malformed provenance blocks with a handoff reason. There is no fallback to unqualified RAG or an invented oracle. This is an opted-in reviewed workflow, not a new mandatory knowledge dependency for existing suites.

### Immutable input handling and explicit contracts

Scope this first reviewed mode to the sample fake provider's known operations, static local fixture/oracle references, literal expected operands, and its typed `step://` outputs as actual values or the known cleanup workspace binding. Reject other provider families, runtime-generated oracle/fixture paths, environment-substituted test inputs, `generated://` artifact mechanisms, arbitrary dynamic references and unsupported reference kinds. Do not migrate all existing provider mechanisms into this slice. Such suites remain available only through ordinary, unreviewed execution when neither invocation pin nor policy is present.

The current plan digest incorporates definitions/references but is not a complete fixture/oracle content digest. Do not rely on it alone. Verify independently pinned bytes, reject absolute/traversal/symlink paths (including linked parents), and copy only the complete reviewed allowlist into a private framework-owned temporary directory. Limits: 128 files, 16 MiB total, 2 MiB per file. Reject hardlinked inputs if their content could change during copy; read each accepted file once, hash those bytes and write those same bytes.

The current `FrameworkProviderContractCatalog.resolveDirectory()` prefers CWD contracts, then ancestors, then bundled resources; `V03ProviderContractCatalog.load()` uses it. **Reviewed mode must never call that fallback chain.** Prepare requires explicit local `reviewed_contracts` copies, verifies their IDs and bytes, and materializes only those verified bytes into the private snapshot's contract directory. Add explicit directory overloads to baseline validation and typed catalog loading; pass the same directory through validation and plan compilation. An absent, duplicate, unexpected or digest-mismatched required contract blocks. Default resolution remains unchanged outside reviewed mode. Test hostile CWD shadow contracts and source contract mutation both before and after snapshot creation; neither can influence reviewed execution.

Compile and execute against that snapshot, including artifact resolution and assertions, so later changes to the source checkout do not change execution. Check copied artifact references remain contained and covered before any provider runs. Record reviewed file digests and the executed plan digest; do not assume absolute-path-sensitive plans have the same digest after relocation. The handoff/receipt are external provenance inputs, not self-hashed entries in their own allowlist. Private snapshots are not published as result evidence: index only sanitized provenance summaries/hashes. Delete only the framework-created snapshot directory after completion; never remove original input files or other runs' directories.

## 3. Optional shared-state policy and SIT provider extension

This belongs in the Test Framework and is applied only when product_test.shared_state is present; local product tests do not require it. Introduce an opt-in `SitPolicyAwareProvider` interface alongside `V03ProviderRuntimeAdapter`, with:

```java
boolean simulated();
SitOperationEffect sitEffect(String providerContract, String operation);
V03StepResult executeWithSitPolicy(V03ExecutionStep step,
    V03ExecutionContext context, SitExecutionScope scope);
```

`SitOperationEffect` is READ_ONLY, OWNED_WRITE, OWNED_CLEANUP or UNKNOWN. A scope binds environment, namespace, owner, **framework-generated** run ID, data/cleanup policy and a provider-owned opaque ownership token. Do not accept caller-chosen run IDs or trust DSL labels to classify arbitrary SQL/HTTP as read-only. Provider code explicitly declares effects and enforces ownership at its actual operation boundary. Unknown effect or a provider lacking this interface blocks before any phase; ordinary provider execution remains available for verified local product tests without shared_state; when shared_state is present, unsupported providers block. Completely unreviewed compatibility requires both product_test and invocation pin absent.

- `read_only` permits only READ_ONLY effects. Local evidence writes are permitted, but setup/cleanup that changes shared state is not. If the plan includes prohibited effects, block the whole run; do not silently skip a phase and report PASS.
- `owned` permits writes only inside the declared owner/namespace for this run. `cleanup: none` forbids cleanup effects. `owned_only` requires an exact same-run ownership token and deletes only that run's resources through existing provider cleanup operations; no prefix, wildcard or environment-wide cleanup.
- `real`/`simulated` must match the selected readiness adapter and opted-in provider metadata **before** their observations/operations are invoked. A mismatch blocks; real failure never triggers a simulated fallback. Any simulated dependency/provider keeps framework-verification-only evidence and forbids downstream release evidence.
- Reuse current bounded readiness calls. Ownership acquisition/checks are immediate, nonblocking in the local fake; contention returns BLOCKED without retry. Every fake shared effect must atomically compare the exact owner/run/token and apply its mutation inside the same per-namespace critical section or map computation. A separate `check()` followed by mutation is forbidden. Loss blocks the attempted effect and subsequent mutation/cleanup. Preserve any original test failure and record policy/cleanup failures separately.

**Concrete local proof, one JVM only:** Extend `SampleFakeProviderV03Adapter` only for its known sample operations. `LocalFakeSitOwnership` holds immutable per-(environment,namespace) state and exact fake resource ownership. Its scoped setup/cleanup uses atomic conditional transitions rather than a free-standing permission check. No general callback-based mutation API, seed/delete operation, scheduler or distributed lease is introduced.

| State / event | Conditional transition |
| --- | --- |
| UNCLAIMED or RELEASED, acquire after all gates pass | atomically allocate fresh opaque token → OWNED(owner, run, token, empty resources) |
| OWNED, setup/write | exact owner+run+token comparison and add this run's fake resource in the same atomic operation |
| OWNED, owned cleanup | exact comparison and remove only that run's recorded resources in the same atomic operation; report failure without deleting foreign resources |
| OWNED, finish with no resources or completed exact owned cleanup | exact-token conditional transition → RELEASED |
| OWNED, finish with resources retained or cleanup failed | exact-token conditional transition → RETAINED with original owner/resource identities |
| RETAINED, acquire | BLOCKED; no reassignment, expiry takeover or implicit cleanup |
| Any state, stale/foreign effect or finalizer | no state change; preserve the current owner's resources and token |

Failed handoff/policy/readiness gates acquire nothing. Ownership is acquired immediately before the first allowed provider phase; read-only phases acquire no mutable ownership. A stale `finally` cannot release a replacement owner's namespace. Distinct namespaces proceed independently. Tests interleave an ownership generation change with old effects/finalizers and verify the new owner's resources survive. All contention claims are explicitly **same-JVM-only fake proof**; separate processes do not share this store. Retention is deliberately conservative; this slice provides no reclaim command.

**VALIDATION GAP:** This local fake/store is not a distributed lease, database authorization boundary or protection for real SIT. No real provider opts in during this slice. Real provider policy therefore blocks until a later audited adapter implements server-side ownership checks/transactions or a real lease service. Java plugins remain trusted code; the framework cannot make arbitrary provider code read-only by declaration. Do not add a lease scheduler, generic distributed lock, seeding API or cleanup platform here.

## 4. Files and ordered implementation tasks

All files below are in the existing isolated framework clone. Preserve the prior uncommitted slice. Do not edit the original Swarm checkout.

### Task 0 — Swarm module contract and application composition proof

Create under `integrations/swarm-product-test/`: `src/main/java/com/featuredeliveryintelligence/fdi/producttest/{ProductTestingModule,FrameworkExecutionPort,ProductTestRequest,FrameworkCliTransport,ProductTestReceipt,ProductTestCli}.java`, `contracts/{product-test-request.v1,product-test-receipt.v1}.schema.json`, `README.md`, `CAPABILITY.md`, `scripts/build-local.sh`, `patches/FdiApplication-product-test.patch`, and `src/test/java/com/featuredeliveryintelligence/fdi/producttest/ProductTestingModuleTest.java`. Add `src/test/java/com/specdriven/regression/producttest/SwarmProductTestModuleIT.java` for the bounded local packaged-framework/application harness.

- [ ] First test the typed module with an injected fake FrameworkExecutionPort: correlation/review/source mismatch and unsupported capability tuples invoke nothing; compatible input invokes exactly once. Test required vs explicit not_used knowledge without fallback.
- [ ] Test completion separately from verdict: exit0 plus missing/malformed/stale result remains indeterminate; a valid failed/blocked verdict is preserved; failed evidence validation never grants successful verification. No receipt contains a fabricated Multica ID.
- [ ] Implement only typed module/CLI and fixed-argv transport. Tests cover one deadline, bounded output, containment, correlation and no blind retry after uncertainty.
- [ ] Compile an isolated minimal FdiApplication overlay with the new early handler; show unrelated/dev204 commands retain their original routing. Prove a local reviewed product test with no SIT fields completes module→framework→validated receipt using the actual packaged framework.

### Task 1 — contract and offline reviewed handoff

Create:
- `src/main/java/com/specdriven/regression/producttest/ProductTestPolicy.java` — stage-independent test-level/type/environment capability tuple, modes and optional shared_state value with strict defaults.
- `src/main/java/com/specdriven/regression/producttest/ReviewedProductTestHandoff.java` — immutable parsed handoff/provenance model.
- `src/main/java/com/specdriven/regression/producttest/ProductTestHandoffService.java` — bounded local preparation, validation and private snapshot; immutable nested `Snapshot implements AutoCloseable` owns only its temporary root.
- `src/test/java/com/specdriven/regression/producttest/ProductTestHandoffServiceTest.java`.
- `schemas/product_test_reviewed_handoff.v1.schema.yaml` and byte-identical `docs/02-architecture/contracts/product_test_reviewed_handoff.v1.schema.yaml`.

Modify `cli/RegressionCommand.java` to dispatch the packaging command with no provider/network execution and enforce the immutable invocation requirement before every run route. Put the requirement as a nested immutable value in `ProductTestHandoffService`; pass it explicitly instead of introducing a second orchestration layer. Add paired Env_Profile fields and legacy rejection to `ContractBaselineService`. Update `scripts/ci/check-schema-drift.sh` required allowlist to include `product_test_reviewed_handoff.v1.schema.yaml` (16 required schemas), and test that both copies are required and byte-identical.

- [ ] Write failing common-dispatch cases for pin present with removed/null policy, changed profile, legacy/package routes and suite groups: every case blocks with zero dispatches.
- [ ] Write failing cases for external digest missing/mismatch, unchanged-file success, altered fixture/oracle/receipt, excluded candidate, revision/scope mismatch, expired review, malformed/duplicate/oversized JSON, missing oracle provenance, file containment, exact receipt field mapping, selected/excluded ambiguity and complete static-reference coverage.
- [ ] Run only `ProductTestHandoffServiceTest`; confirm behavior is missing before implementation.
- [ ] Implement the typed contract/parser and local packager. Use the inspected receipt shape and a wholly synthetic fixture; do not ship source content from the dirty Swarm checkout.
- [ ] Assert that preparing a handoff never runs a provider and never declares approval or READY/PASS.

### Task 2 — provider policy and conservative ownership proof

Create:
- `sit/SitPolicyAwareProvider.java`, `sit/SitOperationEffect.java`, `sit/SitExecutionScope.java`, `sit/SitExecutionGuard.java`.
- `sit/LocalFakeSitOwnership.java` — local synthetic ownership/resource state only; injectable per test.
- `src/test/java/com/specdriven/regression/sit/SitExecutionGuardTest.java`.

Modify `contract/v03/adapter/SampleFakeProviderV03Adapter.java` to implement only the scoped fake path; retain old `execute` unchanged for absent policy. Do not change provider support status or advertise another runtime family.

- [ ] Write same-JVM tests with latches/barriers for two claims on the same namespace, unrelated namespaces, ownership-generation change interleaved with mutation, stale finalizers, retained namespaces and a foreign cleanup token. Assert state/resource preservation, not just returned status.
- [ ] Write tests for default read-only blocking write/cleanup, `none` blocking cleanup, unsupported provider/effect, and real/simulated mismatch before any spy invocation.
- [ ] Implement the transition table using atomic compare-and-effect for each namespace; acquire only after gates, finalize only the exact current token, retain nonempty/failed cleanup state. No waiting, retry, takeover or reclaim API.
- [ ] Verify original setup/execute/cleanup behavior remains unchanged without policy.

### Task 3 — one runner, two separate gates, standard evidence

Modify:
- `contract/v03/V03EnvironmentProfile.java`, `V03ExecutionPlanBuilder.java`, `V03DryRunRenderer.java` — compile/pin the optional policy; dry run reports both handoff/readiness not evaluated.
- `contract/ContractBaselineService.java` and `contract/v03/V03ProviderContractCatalog.java` — add explicit verified-directory entry points for reviewed validation/compilation, with no call to CWD/ancestor/bundled fallback. Thread the directory through local method arguments; do not change process-global catalog state.
- `contract/v03/V03RuntimeExecutionService.java` — verified snapshot → compile → handoff evidence → policy precheck → existing readiness → scoped provider calls → existing assertions/results. Keep all failed gates outside every provider phase and ownership acquisition. Require the invocation pin at direct runtime entry as well as CLI dispatch.
- `sit/SitReadinessService.java` — optional required-mode map, with pre-call metadata rejection; preserve existing overload/behavior.
- `evidence/EvidenceHardeningService.java` and paired evidence index schemas — add `product_test_handoff` and `product_test_execution_policy` evidence types.
- `src/test/java/com/specdriven/regression/contract/v03/ProductTestSharedExecutionTest.java` and `src/test/java/com/specdriven/regression/cli/ProductTestHandoffCommandTest.java`.

- [ ] Write integration tests showing changed original fixture/oracle bytes after snapshot cannot affect executed inputs; missing snapshot coverage and unsupported dynamic references block.
- [ ] Test CWD contract shadowing, missing explicit contracts and contract mutation before/after snapshot. Verify validator and compiler consume the same pinned contract bytes without fallback.
- [ ] Assert failures write run/suite/profile-linked sanitized evidence and result BLOCKED with zero provider invocations. Knowledge/provenance status never substitutes for test assertions.
- [ ] Assert runtime assertion failure remains failed even when all knowledge and readiness gates pass; lost ownership cannot convert it into PASS and cannot run foreign cleanup.
- [ ] Record source/receipt/file hashes, scope/modes/ownership transitions and skipped/blocked cleanup reasons. Keep secrets, query text, raw knowledge bodies and ownership credentials out of published evidence.
- [ ] Ensure snapshot cleanup and fake ownership finalization use `finally` without masking primary failures; only owned resources may be touched.

### Task 4 — usable fake vertical slice and documentation

Create `samples/50-product-testing/reviewed_local/` with a reviewed local component-regression suite that has no SIT/readiness/shared-state fields. Also create `samples/40-sit-readiness/reviewed_shared_fixture/` with two optional shared-state demonstration suites (read-only and owned/owned_only), synthetic Dev204-compatible receipt and source mappings, review input, fixture/oracle files and README. Show the exact prepare → review digest → run → validate-evidence workflow, plus tampered-input, dependency-mode mismatch and ownership-contention demonstrations. The receipt says synthetic/unverified deployment; it must not claim a live Cognee round trip.

Modify `README.md`, `docs/09-operations/sit-readiness.md` and `samples/40-sit-readiness/README.md`; create `docs/09-operations/swarm-product-testing.md` and `samples/50-product-testing/README.md`. Create `docs/09-operations/istqb-alignment.md` with the mapping below and link it from both module guides.

- [ ] Run packaged CLI preparation and fake suite execution without network; validate resulting evidence and expected nonzero failure exits.
- [ ] Confirm actual existing Swarm receipt field compatibility against inspected source/test fixtures. If an isolated source build is needed for fixture generation, use a fresh copied checkout; never build or modify the dirty original.
- [ ] Document that obtaining the selection receipt is an existing authorized Swarm operation; framework packaging/importing performs no Cognee request, source readback or approval.

### Task 5 — independent review and verification

- [ ] Repeat focused tests after each coherent change, then run the repository's full bounded offline verify and schema drift check. No new dependencies are needed.
- [ ] Coordinator performs independent review and authorized sanitized Kimi cross-check. Address findings with failing regression tests before repairs.
- [ ] Record test counts, exact commands, smoke evidence paths, implemented vs proposed vs validation-gap status. No deploy, push, PR, upload, knowledge write, company-system access or real SIT operations.

Commands (normal wrapper distribution or the already-approved cached equivalent):

```sh
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -o \
  -DargLine='-Xmx1024m' \
  -Dtest=ProductTestHandoffServiceTest,SitExecutionGuardTest,ProductTestSharedExecutionTest,ProductTestHandoffCommandTest test
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -o -DargLine='-Xmx1024m' verify
scripts/ci/check-schema-drift.sh
```

No commits are required for intermediate steps; retain a reviewable diff over the existing delivery.

## 5. Acceptance criteria

| ID | Required observed behavior |
| --- | --- |
| AC1 | Only when both policy and invocation pin are absent do existing routes behave unchanged. A present pin plus removed/null policy, changed profile, group, package or legacy route blocks at common dispatch with zero provider calls. |
| AC2 | Separately pinned reviewed bundle and complete source/oracle bytes are required; tampering, missing review/source scope/version and ungoverned RAG candidates block before providers. |
| AC3 | Static snapshot-backed execution survives original fixture/oracle/contract mutation and hostile CWD contracts. Validator/compiler use the same explicit verified catalog; unsafe, unlisted, oversized or dynamic inputs block. |
| AC4 | Cognee unavailable/unknown/deployment-unverified does not become readiness or PASS. The fake receipt is visibly synthetic; no HTTP call is made. |
| AC5 | Default read-only/none rejects shared writes/cleanup; provider opt-in/effect/mode unknowns fail closed before invocation. |
| AC6 | Within one JVM, same-namespace claims have one winner and distinct namespaces remain independent. Token check plus effect is atomic; foreign effects/stale finalizers preserve the current owner. Nonempty/failed-cleanup namespaces become RETAINED and reject reassignment. |
| AC7 | Simulated/real mismatches are caught before live-capable callbacks; no fallback, and simulation cannot generate downstream release evidence. |
| AC8 | Assertion failure, readiness timeout, lost ownership and cleanup failure retain correct non-PASS results and actionable indexed evidence. |
| AC9 | Packaged CLI demonstrably consumes a Dev204-compatible local receipt and executes existing provider phases only after gates pass; knowledge remains separate evidence. |
| AC11 | Application-composed Swarm module invokes the existing framework once for a supported local tuple, validates result/evidence identity, and returns execution facts separately from verdict/verification. Local operation needs no SIT policy; unsupported level/type/environment pairs block. |
| AC10 | Module documentation correctly separates gTAA capabilities from current TAF layers, maps actual files/roles, labels IMPLEMENTED/PROPOSED/VALIDATION GAP, links primary sources and makes no certification claim. |

## 6. ISTQB alignment to publish (short paraphrase, not a syllabus reproduction)

The v2.0 TAE syllabus describes gTAA interfaces to the SUT and project, test and configuration management, with optional generation, definition, execution and adaptation capabilities (§3.1.1). Separately, its TAF layering groups test scripts, business logic and core libraries (§3.1.3). Map reviewed DSL/oracles to definition/test scripts and reusable domain workflows to business logic. Framework runner, assertions and evidence utilities are reusable core libraries; provider adapters implement SUT-facing adaptation, with domain-specific provider behavior kept distinct from reusable framework core. Swarm proposals are optional generation inputs, not execution authority. Test-data ownership and reporting are cross-cutting responsibilities.

The CT-GenAI material discusses evaluation, hallucination/reasoning/bias, privacy/security and adoption. Source pinning, untrusted retrieved content, reviewed tests/oracles and deterministic verdicts are our engineering safeguards informed by those topics, not verbatim ISTQB mandates.

Primary references:
- [ISTQB CTAL-TAE syllabus v2.0, §§3.1.1 and 3.1.3, pp.23–25](https://istqb.org/wp-content/uploads/2024/11/ISTQB_CTAL-TAE_Syllabus_v2.0.pdf)
- [ISTQB Certified Tester Testing with Generative AI](https://istqb.org/certifications/gen-ai/)

Do not label the older four-capability diagram as v2.0 layers. Do not claim certification, full syllabus conformance, production validation or a live Cognee integration from these local tests.

## 7. Review decisions and remaining limits

**Latest scope correction:** the deliverable is a pluggable Swarm product-testing module, not a SIT-only gate. The module core/request/handoff is stage-independent; SIT readiness and shared-state ownership remain optional Test Framework extensions. The corrected scope is also being checked by Kimi while coordinator-authorized implementation may proceed.

**Plan revision after independent Astra review:** invocation downgrade, explicit contract resolution and atomic ownership/retention blockers are addressed above; the latest user instruction permits coordinator-owned implementation in parallel with corrected-scope Kimi feedback; this author remains plan-only. No architecture choice is intentionally left unspecified: use the narrow existing application/CLI composition pattern with a new typed ProductTestingModule and its own receipt (the Multica RuntimeBinding receipt is deliberately not reused), fixed-argv existing-framework transport, product-test reviewed handoff, protected invocation digest, private static-input snapshot, explicit capabilities/modes, optional provider ownership opt-in and same-JVM fake proof. Review must assess whether these are the smallest sufficient boundaries before implementation. If a scope element is rejected, revise this plan first.

Kimi plan cross-checks and independent assessments have informed the implementation; their source reports are retained under `evidence-v2/`. Outstanding deployment validation remains explicit: exact deployed Cognee identity; real company source access/governance; real SIT provider ownership enforcement and distributed contention/lease behavior. None is silently satisfied by the proposed local proof. Readiness remains point-in-time; snapshot integrity does not reserve external state. This plan does not add RAG ingestion, a second knowledge store, automatic test approval, a live lease service or company-specific provider calls.
