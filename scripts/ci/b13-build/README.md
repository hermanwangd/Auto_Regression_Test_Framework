# A01 source admission and execution copy regression CI

This source-bound nonproduction candidate tests commit 357bd3b51b02b2fbb75f7f121dc6ebf7118ee67a, tree 3b032dbc43506e6c4bc20ffdffe14837804aa38f. It adds the fixed A01 raw-source admission and byte-identical private execution copy checks. The original launch suite, source hashes, lease bytes and live owner checks remain bound. All earlier sources, CI42733402337, artifact intake and failed runtime receipts are retained.

The exact expected tests are 307: 248 Framework and 59 Module across 20 XML reports. Existing 267 cases remain selected. The additions are 16 A01SourceAdmissionTest cases and the existing 24 ProductTestHandoffServiceTest cases. Counts come from source annotations and were frozen before CI, not inferred from successful results. Fresh exact reports, unique classname/name identities, matching counters and zero failures/errors/skips remain mandatory. XML bounds remain 512 KiB per report and 4 MiB aggregate, including failure diagnostics.

Java 17.0.21+9, Maven 3.9.11, pinned Actions, 900-second job / 840-second driver including 30-second cleanup reserve, 2 GiB sampled process RSS, 2 GiB owned storage, 1 GiB dependency-cache subset, 256 MiB delivery and 1 MiB manifest limits are unchanged. Official Maven Central remains the sole dependency mirror. No Go build, owner launch, provider RPC, deployment or SIT runs in this workflow.

Owner packaging remains exactly 9 compiled class entries and four ordered dependency GAVs: Jackson annotations 2.22, core/databind 2.22.0 and protobuf-java 4.33.4. Classpath, ZIP, path, byte-count and nested-class guards are unchanged. The changed A01 policy means the new owner-support JAR requires new runtime identity binding; earlier identical-JAR exemptions cannot be reused.

The success artifact contains the two application JARs, five owner/dependency JARs, 20 XML reports and a build manifest. Failure diagnostics have their own bounded artifact and never qualify as success or coverage. Packaging rechecks accepted report hashes.

Local Java/JUnit tests were NOT_RUN because a verified Maven/JUnit project classpath is unavailable. The actual prior Framework admission failure and the same-parser 9-fixture diagnostic are retained as behavioral RED. Existing 44 standard-library builder controls are rerun for this exact source-bound bundle; their result is recorded separately and does not prove Java or native-agent behavior.

Publish only to the existing codex/b13-build-only branch using expected head d7b787ce43d783ba77998d237d08cfca774d3a3d after final exact-bundle review. This workflow does not move plug-in-module or modify production pipelines. After CI, artifacts and the changed owner JAR must be verified. A01 product reentry uses a new finite grant, current prelaunch/no-RPC controls and preserved cumulative resource history. Unit success alone does not establish A01 group, native-agent or SIT coverage.
