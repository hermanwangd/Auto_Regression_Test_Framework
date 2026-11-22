# Reviewed CLI provider verification — 2026-11-07

This candidate adds `cli_process.v0.3` to the existing framework and Swarm product-test module. Swarm still proposes/reviews tests; the framework executes and determines verdicts. No separate test runner was introduced.

## Checks

- Framework `mvn -o -B -ntp verify`: **1,492 passed** (1,477 Surefire + 15 Failsafe), zero failures/errors/skips.
- Module `mvn -o -B -ntp -f integrations/swarm-product-test/pom.xml package`: **53 passed**, zero failures/errors/skips.
- Focused CLI/runtime/adapter/assertion regressions: 37 passed. Test-first failures were retained for missing CLI support, empty assertion literals, NUL stdin, secret-like output and catalog registration.
- Existing usage-kit packaging includes the CLI contract, operations guide and five sample files; seven packaged files were checked byte-for-byte against source.

The framework JAR tested here has SHA-256 `43f94ca73462f061852a5403efa3d0726700d36eedeba63580d03bdf860d8ba2`; the module CLI JAR has SHA-256 `8f517927af5c13458b1c0976f24424493243c3d0b2be7605cd49782fc74b4696`. These hashes identify the actual tested binaries, not a claim of reproducible byte-identical builds.

## Actual public products

[jq 1.8.2](https://github.com/jqlang/jq/releases/tag/jq-1.8.2), source commit `34f7186b86743a083a589741b6cea95293524108`, was downloaded from the official release and verified against its official checksum. The macOS arm64 executable SHA-256 is `2d75340ba57a4b4b4c8708a21c2dc8e958a48aaa8bba13b27f77f6e4c0eca07e`. The retained official COPYING file records MIT licensing and notices for bundled components.

[PocketBase 0.40.6](https://github.com/pocketbase/pocketbase/releases/tag/v0.40.6), source commit `9d281ccaba65f2686eb7b0b614ccda72d1116a1b`, uses the previously verified official binary, SHA-256 `d313ba59879e96a5ab566c6763f4ae9b63dc62e9fee78782276407808905d54f` (MIT).

All nine cases ran through the packaged module, which invoked the existing framework, canonical evidence validation and reporting. Each suite also passed the normal `validate` command before execution.

| Case | Framework verdict | Expected detection |
| --- | --- | --- |
| filter | passed | Exact exit/stdout/stderr assertions |
| expected-nonzero | passed | Exact exit/stdout/stderr assertions |
| stderr | passed | Exact exit/stdout/stderr assertions |
| wrong-oracle | failed | ASSERTION_FAILED |
| literal-argv | passed | Exact exit/stdout/stderr assertions |
| timeout | failed | PROVIDER_TIMEOUT |
| pin-mismatch | failed | PROVIDER_UNAVAILABLE |
| recovery | passed | Exact exit/stdout/stderr assertions |
| pocketbase-version | passed | Exact exit/stdout/stderr assertions |

Every module outcome was `COMPLETED`, evidence was `validated`, and release eligibility remained false. Executable/argv/stdin hashes were checked independently, and recorded owned process IDs were no longer running. The expected-nonzero case used the actual checked-in usage-kit manifest/profile/test, configuring only the executable path and independently verified digest. Its exit 1 is an expected product behavior and produces a passed test.

Prepared plan SHA-256: `d967d2f6e94ef6ed2d7f533e769343ebdd4fb849a2a294fda3f50c6a9fb4715e`. Execution harness SHA-256: `873c69dad6041a76756f4431c1dda7af8175c620612ceaa70d28476ea6b6d38a`. An independent Sol 6.1 xhigh reviewer checked the plan, concrete pins and oracles before execution.

## Related coverage and limits

The preceding HTTP increment was separately exercised against Datasette 0.65.5 (four cases) and PocketBase 0.40.6 (six cases): positive reads, wrong oracle, service outage and recovery; PocketBase additionally covered pagination and expected 404. Those runs remain bound to the earlier HTTP build, not relabeled as this CLI candidate. PocketBase fixture item rows stayed unchanged; startup did modify its internal authentication metadata. The original startup log is restricted locally; only a credential-free derivative is suitable for sharing.

These checks establish framework/module behavior against real public executables. They do not establish native autonomous Swarm-agent behavior, company/shared/cloud SIT, distributed ownership, live Cognee retrieval, cross-repository orchestration or transitive binary dependency integrity. The cases use `knowledge_mode: not_used`. The executable remains trusted code, with best-effort child-process observation rather than OS sandbox isolation.

[ISTQB guidance mapping](../09-operations/product-testing-module.md#istqb-informed-architecture-and-genai-safeguards) applies to adaptation/execution separation, independent oracles, traceability and grounded analysis. This is not certification or a claim that the complete gTAA/CT-GenAI syllabi are implemented.
