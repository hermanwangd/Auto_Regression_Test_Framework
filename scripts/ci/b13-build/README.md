# A01 focused Java/Module build preparation

SOURCE BOUND, NOT RUN: parent officially read back product commit
6c2ba5400c7458a2409477fb7ce08ad42e3fd646, tree
20c155097e753ea45389c7944b481192eb1bc369, parent e08cdb2b4c4aaddc8317c140058b2469ddc73c85.
All22 source files495472B match the reviewed owner manifest. Driver/workflow/
contract now bind those actual identities. Final exact bundle review precedes
parent publication/CI; no build or product execution occurred locally.

Uses original Java17/Maven pins, actions,30controls,900s job/840s driver incl30s
cleanup,2GiB sampled RSS/2GiB total disk,1GiB cache subset,256MiB delivery and1MiB
manifest. Charges source/control/work roots and all owner/JUnit copies. The prior
Go1500s/3GiB grant does not apply. No Go rebuild, service launch, runtime or SIT.

Declared263 JUnit cases:208root+55Module across18exact reports; old149 preserved,
new114 owner-declared. Fresh XML, exact class/count/unique testcase identities,
zero skipped/errors/failures, original512KiB/report4MiB aggregate, and unchanged
post-packaging report hashes are mandatory. Source annotation derivation is an
owner/reviewer input; counts are not inferred from a successful run.

Package exact9owner/policy/descriptor class entries into owner/owner-support.jar;
copy exact ordered Jackson annotations2.22/core2.22.0/databind2.22.0 and
protobuf-java4.33.4. This five-JAR closure stays≤8JARs/32MiB, with no external
Manifest Class-Path, unsafe paths, duplicate ZIP entries, symlinks or undeclared
nested class expansions. Whole-JAR hashes and compiled class input hashes appear
in manifest. Nothing runs this owner in build. Runtime launch identity is frozen
later against actual outputs, not synthetic values or local compilation receipts.

Success uploads exact2application JARs+5owner/support/dependency JARs+18XML+manifest.
Combined artifact bytes≤255MiB plus manifest≤1MiB. Full XML is copied only after
strict validation and checked again against accepted fingerprints. Failure-only
diagnostics use a separate artifact and contain only expected XML paths≤512KiB
each/4MiB total and failure.json≤64KiB. Partial/malformed XML is explicitly
FAILED_DIAGNOSTICS_ONLY, never admission or JAR success. Collection cannot turn
failure into success; deadline cleanup reserve can prevent diagnostic collection.

Local evidence: original30 baseline PASS; new10 first RED1failure+9errors against
unmodified driver; implemented controls44PASS (original30+14),0.662s,23072KiB
parent RSS under256MiB virtual ceiling. Python fixture children only; no Maven,
Java, Go, network or product execution. Original RED/green receipts retained.
These mechanical tests do not claim native Swarm-agent acceptance.

Parent alone publishes against expected builder head
12c62d3f37cee7ba2bc82ab522942156b91f5816 after final bound-byte review. Historical
P04source173ee/CI42681020126 and GoCI42715816478 remain unchanged. After build,
artifact intake and owner/process/normal+NOT_FOUND runtime plans remain separate;
263unit PASS would not establish A01/runtime/SIT acceptance.

Preparation note: initial metadata generation had a syntax error before writing
workflow/contract; corrected generation succeeded. No build/test failure hidden.
