# Fixed-source P04 focused tests and packaging

This is a five-file update to the existing nonproduction builder on
`codex/b13-build-only`. Product source is the independently reviewed commit
`c20829f9b574e753ecb6258aac8f4ba22a9e0b59`, tree
`fab39f4a4d297967a5bdb56217948839d46fa68e`, based on
`b13c503cc9fadd930e11fb8adc6496308697a966`.

The workflow runs only on this builder branch. It changes no production,
release, shared SIT or existing product execution resource contract. It uses
`contents: read`, pinned actions, no persisted credentials, no secrets, no OIDC,
no production environment and no external services. Source and control
checkouts are separate. Source pre/post commit, tree and tracked cleanliness
must match. No source branch creation is needed for this exact-commit checkout.

## Fixed tests, not formal coverage

On the pinned Java 17 toolchain the driver runs these seven root classes:

- P04DescriptorAdmissionTest: 2 JUnit testcases
- ReviewedGrpcCapabilityTest: 3
- ReviewedGrpcIntegrationTest: 31
- RouteGuideProfileTest: 32
- GrpcClientExternalEndpointSupportTest: 2
- GrpcMetadataDeadlineContractTest: 8
- ReviewedGrpcProofIdentityTest: 62

The root total is 140. The Module classes are
GrpcDescriptorAdmissionProofTest and GrpcBindingTest, one testcase each.
Their exact fully-qualified names are frozen in the driver and contract.
Both Maven test commands require nonempty matching tests. The root uses
`-Pno-ibm-mq`. They are followed by the original two serial `package` commands
with `-Dmaven.test.skip=true`, so packaging does not rerun tests.

All target directories must initially be absent. Each Surefire report directory
must also be absent immediately before its test command. After exit 0, the
parser requires exactly the expected report files and classes, the exact
number of actual testcase nodes, unique `(classname, name)` identities, and
zero failure, error or skipped nodes. XML counters must agree with the nodes.
It rejects DTD/entities, malformed XML, nested cases, symlinks and foreign
classes. One XML file is limited to 512 KiB, all nine reports to 4 MiB.
The same report summaries and hashes must remain unchanged through packaging.

The manifest records every testcase identity, report byte count and SHA-256,
class totals, process exit/output hashes, source and workflow pins, tool and
resolved dependency hashes, resource observations, and binary hashes. The XML
and raw logs are not uploaded. A nonzero process exit includes only a JSON-safe
tail of its already-capped output, at most 8 KiB after the final error JSON encoding. Child environments
exclude credentials.

This is focused unit/regression validation. It is not a complete packaged
Module-to-Framework execution, a SIT run, or acceptance of the original P04
row. The new descriptor-only capability does not turn the original live-lease
`grpc_client` row into an accepted case. Formal coverage credit stays zero
for this build. The many assertions within individual tests are not additional
JUnit cases or catalog rows.

## Tools, bounds and artifacts

Tool and action pins are unchanged from the successful original b13 builder:
Eclipse Temurin 17.0.21+9 Linux x64 and Maven 3.9.11, both hash-checked before
extraction; checkout and artifact actions are immutable commit pins. Maven
uses fresh settings and a fresh local repository, with official Maven Central
as the sole repository mirror. Recorded resolved dependency hashes do not
prove fully reproducible dependency resolution.

All commands share the existing 840-second driver deadline including a
30-second cleanup reserve, inside a 900-second GitHub job. Limits remain:
2 GiB logical owned storage, 50,000 entries, 1 GiB dependency cache,
2 GiB sampled process-group RSS, Maven heap 1 GiB, metaspace 384 MiB,
code cache 128 MiB, output 1 MiB per process, delivery 256 MiB and manifest
1 MiB. RSS/storage are sampled, not kernel-enforced hard limits. Normal
process-group descendants receive bounded termination and cleanup. Nothing
raises the historical Main/DB/P09 caps or moves old evidence. No automatic
retry, broader tests, downloads beyond the pinned tools/dependencies,
import, deployment or release is introduced.

After all checks pass, the workflow retains exactly:

- spec-driven-auto-regression-0.3.2.jar
- swarm-product-test-1.0.0-cli.jar
- build-manifest.json

The Framework must have the exact root entry
`META-INF/regression/runner-build.properties`, schema `runner-build.v1`,
variant `no-ibm-mq`, and `ibm_mq=unsupported`. Duplicates, a conflicting
BOOT-INF entry, malformed metadata and IBM MQ allclient payload are rejected.
No artifact is uploaded from a failed job. Retention is seven days.

## Evidence and activation

The prior fixed b13 build succeeded in
[run 42653127637](https://github.com/hermanwangd/Auto_Regression_Test_Framework/actions/runs/42653127637).
Its binaries remain separate from this candidate. The first historical failed
run is preserved; no previous evidence is relabeled.

Local Python standard-library driver controls: 25 passed in 0.577 seconds.
These comprise the prior 19 controls and six focused JUnit/tail tests, including
negative subcases. They did not execute Maven, Java or product code.
The new real focused CI and packaged Module execution are still NOT_RUN at
preparation. Normal publication must preserve the current builder branch
history and use an expected-head lease. It must not create another source
branch or write to production/main/plug-in-module as part of this CI update.

## Exact parameterized-count correction

[First focused run 42663421700](https://github.com/hermanwangd/Auto_Regression_Test_Framework/actions/runs/42663421700)
failed closed after the root Maven process exited 0, because the frozen expected
JUnit count was wrong. No artifact was produced. Exact source review of all
seven root classes establishes counts 2/3/31/32/2/8/62, total 140, plus the two
Module cases. ReviewedGrpcIntegrationTest has two plain @Test methods and
ValueSource groups 5/6/14/2/2. The earlier expected 27 omitted the last two groups
of two. No product source or tests have changed, and no validator has been
relaxed. The driver now includes classname, expected and actual counts in a
count-mismatch error. A negative fixture verifies that exact diagnostic.
The corrected 142-case CI remains NOT_RUN until the next authorized run.
