# Fixed-source P04 focused tests and packaging

This is a five-file update to the existing nonproduction builder on
`codex/b13-build-only`. Product source is the independently reviewed commit
`173ee0ecb4212b0e0963aa3c574d6c02c30eb3aa`, tree
`5d7c8aaa2685c02dc8855630a0457ae9bc616fef`, based on
`b13c503cc9fadd930e11fb8adc6496308697a966`.

The workflow runs only on this builder branch. It changes no production,
release, shared SIT or existing product execution resource contract. It uses
`contents: read`, pinned actions, no persisted credentials, no secrets, no OIDC,
no production environment and no external services. Source and control
checkouts are separate. Source pre/post commit, tree and tracked cleanliness
must match. No source branch creation is needed for this exact-commit checkout.

## Fixed tests, not formal coverage

On the pinned Java 17 toolchain the driver runs nine root classes:

- P04DescriptorAdmissionTest: 2 JUnit testcases
- ReviewedGrpcCapabilityTest: 3 JUnit testcases
- ReviewedGrpcIntegrationTest: 31 JUnit testcases
- RouteGuideProfileTest: 32 JUnit testcases
- GrpcClientExternalEndpointSupportTest: 2 JUnit testcases
- GrpcMetadataDeadlineContractTest: 8 JUnit testcases
- ReviewedGrpcProofIdentityTest: 62 JUnit testcases
- P04OwnedStreamingTest: 1 JUnit testcases
- P04FourCardinalityTest: 1 JUnit testcases

The root total is 142. Four Module classes total seven cases:

- GrpcDescriptorAdmissionProofTest: 1 JUnit testcases
- GrpcBindingTest: 1 JUnit testcases
- GrpcOwnedStreamingProofTest: 1 JUnit testcases
- GrpcFourCardinalityProofTest: 4 JUnit testcases

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
classes. One XML file is limited to 512 KiB, all thirteen reports to 4 MiB.
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

The successful prior focused run 42665015387 validated the unchanged original
140 Framework plus two Module JUnit cases and produced separately retained
artifacts. Its prior count-mismatch failure 42663421700 remains preserved.
The incorrect expected 27 for ReviewedGrpcIntegrationTest was corrected to
31 from its exact annotations; that correction is retained, not changed here.

The merged candidate additionally retains the server-only checkpoint tests
(one root and one Module case), then adds one four-cardinality root test and
four parameterized Module cases. These are 142 root plus seven Module cases,
149 total. Internal assertion counts are not JUnit testcases or catalog rows.

The local source-overlay four-case attempt completed once: real unary RPC
succeeded, three streaming modes produced the exact shared-guard rejection
without channel creation. The independent Module consumer checked each same
retained result. This was JDK21 with source/target17, not the new packaged
Module or Java17 execution. Original public-project SG02 and SIT remain
NOT_ACCEPTED / NOT_RUN. No formal coverage credit is claimed.

All 25 standard-library driver controls are retained unchanged and passed
in 0.588 seconds against the finalized fixed source bindings. An initial
isolated-mode launcher omitted the local module path and failed to import
build_driver before any test; the corrected exact-path launcher is recorded. No local Maven,
Java, tool download or product test is part of this control-plane preparation.

Publication remains limited to the existing nonproduction builder branch,
using an expected-head fast-forward lease. The merged 19-file source passed the existing final technical review. Its
commit object and tree were created and independently read back; the exact
pins above are now fixed. The final five-file CI package requires only its
existing finite hash/control confirmation before normal publication. No source
branch is created, and no production or plug-in-module pipeline changes.
