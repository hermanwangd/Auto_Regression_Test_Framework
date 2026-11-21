# Codex Work handoff — finish and ship standalone Test Module

## Sources (authoritative)
- Module extraction candidate: `hermanwangd/Auto_Regression_Test_Framework`, branch `shipping/test-module-standalone-20261121`, PR #17.
- Original implementation: same repository, branch `plug-in-module`, `integrations/swarm-product-test/`, commit `597064a9c75c6323a164a06d050110e16d165aca`.
- Framework: same repository, `plug-in-module` currently declares Maven `com.specdriven:spec-driven-auto-regression:0.3.2`. The `main` branch is **0.2.7**; do not silently assume these are compatible.
- Consumer/host: `hermanwangd/engcim-swarm`, `main`. The Test Module must be usable from another Swarm without importing this host source.

## What has been implemented here
- Existing production module source extracted as an isolated root tree; its Maven coordinate remains `com.featuredeliveryintelligence:swarm-product-test:1.0.0`.
- CI at `.github/workflows/module-ci.yml` builds/verifies the module, installs it into local Maven cache, compiles/tests the independent `examples/company-swarm-consumer/`, and records JAR checksums.
- Standalone migration provenance in `EXTRACTION-PROVENANCE.md`; consumer dependency instructions in `docs/COMPANY-SWARM-IMPORT.md`.
- Historical CI run `44866602163` completed successfully and uploaded artifact `standalone-test-module-44866602163`. Re-run after subsequent documentation changes.

## Execute (do not replace with a new architecture proposal)
1. Check PR #17 and the newest green workflow. Correct any portability/test regressions. Retain original production Java code unless a failing test demonstrates a required change.
2. Create a **private** independent repo, preferred name `swarm-test-module`, under the company's approved organization. If the target organization is not accessible, report that single blocker; complete all other steps. Use `git archive` into a fresh `git init -b main` repository as documented in `EXTRACTION-PROVENANCE.md`. **Never push the staging branch with its framework ancestor history.**
3. Confirm a clean clone of the new repo passes `mvn -B -ntp verify`. Also run `mvn -B -ntp -DskipTests install` followed by `mvn -B -ntp -f examples/company-swarm-consumer/pom.xml test`. The company consumer must not reference `engcim-swarm` or parent framework source paths.
4. Publish immutable version `1.0.0` (or a deliberate subsequent version if changed) to the approved internal Maven registry with source commit, checksum and CI receipt. Verify a **fresh consumer** can resolve it from the registry, not just `~/.m2` local cache; preserve license and restrictions.
5. Add a minimal integration to the actual Swarm host that imports the versioned Maven artifact or invokes the CLI with approved config. Keep the framework a separately pinned SHA-256-verified runtime dependency; do not embed a second runner, DSL or provider stack.
6. Run one realistic local/component test using the approved Framework JAR and record result/evidence. Then evaluate the RP SIT and Cross-RP E2E gaps separately: the original module README expressly rejects unsupported SIT/preprod/live stages. Do **not** report these as PASS until real environments, explicit approval, gate semantics, and immutable evidence pass.
7. Submit actual PRs in the new module repo and host integration repo; report exact URLs, commits, CI run IDs, module/Framework artifact checksums, consumer install proof, and blocking gaps.

## Acceptance and stop conditions
- PASS: isolated repo/source history; independent Maven build; package available through internal registry; a fresh, unrelated consumer imports and executes the supported module interface; existing framework reused through a pinned binary; regression checks green.
- NOT DONE: any of the above missing, only tests from the integrated framework branch running, or a package that needs `../..` workspace contents to compile/run.
- SIT/E2E/Release Gate are separate acceptance gates; mark explicitly NOT VERIFIED until real deployment proof exists.
- Never include credentials, vendor JDBC drivers, production fixture payloads or unmasked evidence in source or release artifacts.
