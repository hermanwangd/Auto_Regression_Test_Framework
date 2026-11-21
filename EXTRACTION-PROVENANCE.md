# Standalone Test Module extraction provenance

This root tree was extracted without rewriting production Java source from:

- Original repository: `hermanwangd/Auto_Regression_Test_Framework`
- Original branch: `plug-in-module`
- Source commit: `597064a9c75c6323a164a06d050110e16d165aca`
- Original path: `integrations/swarm-product-test/`
- Original source subtree Git SHA: `d1422f10c6055229fbf1ec63dc08fe2551d67e2b`
- Extraction commit: `37dc538b5da30e3f5003ab0fd7d173e78c804c45`

This is an independently buildable Java 17 Maven module and a candidate for its own **private** company repository. The source subtree is unchanged at extraction; standalone CI, an external consumer smoke test, and documentation are additions.

## Scope and boundaries

- The test module calls an externally supplied, SHA-256-pinned Auto Regression Test Framework JAR; it does **not** duplicate the framework runner, providers, DSL or assertions.
- Company Swarms use the Maven library or the CLI without importing `engcim-swarm` source.
- Tested scopes described in the original README are local; do not infer production SIT/E2E, release gate or live-provider acceptance from a passing build.
- Host-specific overlay sources under `src/host`, `src/host-test` and `host-overlay` are optional integration material and are **not** compiled by the default standalone Maven source layout.
- A fresh consumer repo must compile and discover `ProductTestingModuleProvider`; a real framework-backed run remains a separate acceptance gate.

## Migration to a truly independent GitHub repository

Create a private empty repository for the company and push this **standalone tree**, not the entire framework repository history. For example, in a Git worktree with this extraction branch checked out:

```bash
git remote add company-module https://github.com/<company-org>/<test-module-repo>.git
git push company-module HEAD:main
```

Do **not** run the push command until the intended organization, permissions and repository are confirmed. Keep the current framework main branch unchanged. Change Maven coordinates only as a deliberate compatibility/versioning change.
