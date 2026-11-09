# A01 fixture-only build stage

This finite stage replaces the active b13 branch job with compilation of the
real Google microservices-demo product catalog fixture. It does not execute the
binary, run the Java baseline, rebuild Java, deploy, or establish A01 acceptance.
The existing P04 driver, 30 controls, contract and README remain byte-identical;
the old contract describes the historical Java stage, not this Go allocation.

Go1.27.1 Linux amd64 distribution checksum is from https://go.dev/dl/#go1.27.1:
63d339f0da5ab53635a56f2490a7984dfe12dfcff22ad749f63edaf590168445.
The official page shows rounded67MB, not an exact size. Acquisition is limited
to80MiB and must match this digest before extraction/execution. No setup action,
shared cache, auto toolchain, or fallback compiler is used.

The eight source files are individually pinned by Git blob, SHA256 and size at
GoogleCloudPlatform/microservices-demo c7cb6d5bfd6ea3aed768634ebbcb98cb7a523712.
Only server.go's one exact listen-address expression changes to loopback; its
patched bytes are separately pinned. The server is an upstream-derived variant,
not byte-identical upstream. The unchanged generated descriptor is extracted
with strict grammar and independently pinned as FDP/FDS/base64. Its go_package
contains the historical miscroservices-demo spelling; canonical proto generation
would not be equivalent. No protoc is invoked.

Sources and tool archive use fixed official hosts with restricted redirects and
no inherited proxy. Go dependencies use proxy.golang.org and sum.golang.org with
no direct/VCS fallback, using fresh owned module/build caches. Official proxy
storage redirects remain part of standard Go acquisition. go.mod/go.sum remain
unchanged; go mod verify and build metadata are recorded. Recorded module hashes
do not prove fully reproducible dependency resolution.

Root explicitly approved a separate prospective allocation:1500s job,1440s
driver including30s cleanup reserve,3GiB sampled process-group RSS,2GiB logical
owned disk,150MiB delivery and2compiler workers. All control checkout/source,
archive/extraction/cache/object/tmp/delivery bytes are charged to disk; cache1GiB
is a subset. Archive expansion768MiB,50000 entries,1MiB per-process output and
1MiB manifest remain bounded. These are sampled, not kernel hard caps. TwoGiB
feasibility is unproven until the real attempt. Exceeding a cap fails closed;
there is no retry or automatic increase. Checkout/action infrastructure runs
outside the Python driver, bounded by the job timeout; its checkout bytes are
included as soon as the driver starts. No historical Main/DB/P04 cap changes.

Upload is success-only and explicit:productcatalogservice,products.json,
fixtures/catalog.desc.b64,build-manifest.json. Failure diagnostics remain bounded
in CI output. No failed build is labeled runtime PASS. Artifact download may not
preserve executable mode; the runtime consumer must verify the pinned binary
hash before granting its own reviewed execution mode/permission.

Local controls: unchanged30 plus17 new standard-library tests. The descriptor
control text is the exact block extracted from the pinned generated source, not
a replacement runtime source. Tests execute only bounded Python fixture children
and mocked network; no Go, compiler, install, product or service execution.
Run from this folder using Python3 with this folder added to sys.path and unittest
loading test_build_driver and test_a01_build. Original missing-module RED is
retained as new-feature evidence, not a reproduced old defect. Native Swarm-agent
behavior acceptance remains untested; mechanical47 PASS is not native acceptance.

Publication belongs solely to parent, expected builder head:
80fbe25024ea890c6d24da3b417cfe9a292affc1 on codex/b13-build-only.
No alternative branch, production workflow or Main message. Final package review
and publication/actual CI authorization are separate from local preparation.
