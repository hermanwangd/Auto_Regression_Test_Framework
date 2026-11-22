# Public products, native NATS and CI/CD verification

This increment connects repeatable local product tests to dedicated GitHub CI and provides a separate reusable post-deployment CD SIT hook. It reuses the existing framework, assertions, canonical evidence and packaged Swarm module.

## Actual public product coverage

| Public project | Implementation language | Executed interface | Verified cases |
| --- | --- | --- | --- |
| [SQLite 3.54.1](https://github.com/sqlite/sqlite/tree/57520227c95396ce39ba6ecaac8d475b78dd3964) | C | Native CLI; SQL over argv/stdin, not JDBC | Nine: query, stdin rollback, expected exit 7, stderr, wrong oracle, timeout, output limit, executable pin mismatch, recovery |
| [NATS Server 2.15.2](https://github.com/nats-io/nats-server/tree/5a815f11a911efa2c88fd1dbfb18ed89a449bfc1) | Go | Native NATS wire protocol through the existing NATS provider | Four: exact publish/echo, wrong oracle, broker outage, recovery |

All thirteen cases used the packaged module and existing framework, produced validated canonical evidence, and matched their independently reviewed expectations: seven passed tests and six intentionally failed tests. They remain `framework_verification_only` and release-ineligible. Each run and invocation is distinct. Recorded owned processes stopped and the NATS port closed. Source/asset locks, fixtures, bounded download/build logic and policy checks are committed alongside each harness.

SQLite was built from the pinned official amalgamation; its CLI disables extension loading and uses `-noinit` to prevent unreviewed user startup SQL. The first attempt under the Mac tool sandbox correctly failed the CLI cleanup guard because process-tree observation was denied. An identical bounded observation probe reproduced the permission difference; the complete retry used native process-observation permission without changing source, binary, oracle or guards. The original failed receipt remains preserved. This is environment diagnosis, not a SQLite defect or a Java repair.

NATS tests exposed eight false-success cases in the prior native transport. Thirteen protocol-peer tests retained the failing baseline; the fix now requires a byte-exact, correctly framed native message and enforces payload/frame/deadline limits. Peer fixtures are unit evidence, distinct from the four actual official-broker cases.

## Verification and delivery paths

- Framework `mvn -o -B -ntp verify`: **1537 tests**, zero failures, errors or skips.
- Module `mvn -o -B -ntp -f integrations/swarm-product-test/pom.xml package`: **54 tests**, zero failures, errors or skips.
- SQLite harness: nine Python boundary tests; NATS harness: nine; CD gate: twelve.
- Actual CD command-line missing-configuration and fixture-adapter attempts returned non-success `BLOCKED` before any framework launch.
- Three finite SIT suite compilation checks used the actual packaged framework at accepted timeout boundaries, without provider execution. They caught and fixed assertion/timeout/profile filename contract mismatches.
- Local workflow artifact patterns were checked against all thirteen actual receipts and their canonical evidence/report paths. Remote Actions execution is a separate check, not implied by local success.

Tested framework JAR SHA-256: `092720cbf1aca0198dd390f4198db30e5ee36801cbb17aa9995e0c76655efd2c`.

Tested module CLI JAR SHA-256: `c93df2aeb56b44a8bac7581ece2917857480d0f6a77f7d6623980961a1599bbd`.

[Local CI and CD usage](../09-operations/ci-cd-product-tests.md) documents the actual entrypoints. Local CI derives only bounded ephemeral fields and trusted build hashes under the independently reviewed `local-ci-policy`; it does not fabricate a new human approval. The CD hook instead requires a protected independently pinned deployment handoff, snapshot, framework/readiness plugins, compiled plan and deployed-version evidence. It invokes the existing framework for a finite native REST GET/HEAD subset. Missing configuration, invalid or simulated readiness and unsupported provider scope fail closed. It does not deploy or use local CI approval as CD authority.

## Limits

The verified product runs are macOS local executions. Linux official NATS assets were hash-checked; Linux behavior requires the actual CI run. Native autonomous Swarm agents, company/cloud/shared SIT, live Cognee retrieval and broader CD protocols were not executed. The Swarm module still supports local stages only; the CD hook uses the framework directly. Prior public HTTP/CLI cases retain their original version bindings.

[ISTQB gTAA and CT-GenAI guidance mapping](../09-operations/product-testing-module.md#istqb-informed-architecture-and-genai-safeguards) applies to provider adaptation, framework verdict authority, reviewed oracles, versioned evidence and explicit validation gaps. This does not imply certification or full syllabus implementation.
