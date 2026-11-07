# Public SQLite CLI validation

This C project supplements the earlier Go PocketBase and C jq checks. It exercises SQLite's real command-line process through the packaged Swarm product-testing module and existing `cli_process.v0.3` framework provider. The tuple is `integration / regression / local`, `knowledge_mode: not_used`; evidence remains `framework_verification_only` with release eligibility false. Native Swarm agents, live Cognee, shared SIT and deployment acceptance are **NOT RUN**.

[`asset-lock.json`](asset-lock.json) pins SQLite 3.54.1, the official [GitHub mirror commit](https://github.com/sqlite/sqlite/tree/57520227c95396ce39ba6ecaac8d475b78dd3964), the exact official amalgamation archive, all four source files, the Fossil source ID and the exact-commit license. The official [download page](https://www.sqlite.org/download.html) publishes the archive SHA3-256; the [release note](https://www.sqlite.org/releaselog/3_54_1.html) independently publishes the `sqlite3.c` SHA3-256 and source ID. SQLite's deliverable source and CLI are [public domain](https://www.sqlite.org/copyright.html).

The same pinned source archive builds on Linux and macOS using an existing C compiler. The build uses the documented [direct CLI compilation](https://www.sqlite.org/howtocompile.html#compiling_the_command_line_interface), `-O0 -DSQLITE_THREADSAFE=0 -DSQLITE_OMIT_LOAD_EXTENSION shell.c sqlite3.c -lm`; it installs no compiler, dependencies, containers or system packages. The executable's SHA-256 is host specific and is retained with compiler identity, flags, source hashes, architecture and archive hashes in `build.json`. That digest pins the resulting local executable; it does not promise byte-identical Linux and macOS binaries or pin OS libraries.

The execution environment must permit Java to observe its owned process tree. A macOS sandbox that denies the `sysctl` query makes the provider fail closed with uncertain cleanup; the canonical verdict remains failed even when a later check confirms the child stopped. Preserve that evidence and resolve the environment permission before an independently approved fresh attempt. The harness does not retry or relax cleanup checks automatically. See the [local process boundary](../../../../docs/09-operations/reviewed_cli_process.md).

[`cases.json`](cases.json) contains fixed expected values reviewed before any product execution. Values come from the SQL fixtures and documented [CLI semantics](https://www.sqlite.org/cli.html), never captured product output:

| Case | Expected observation / framework verdict |
| --- | --- |
| `argv-query` | Filter and order three literal rows: `bolt\|4`, `washer\|2`; PASS |
| `stdin-rollback` | Roll back an update, then observe count 3 and sum 26; PASS |
| `expected-nonzero` | `.exit 7` with empty streams; PASS |
| `stderr` | Missing-table preparation error on stderr, exit 1; PASS |
| `wrong-oracle` | Deliberately expect quantity 999 while actual quantity is 2; `ASSERTION_FAILED` |
| `timeout` | Recursive SQL exceeds 500 ms; `PROVIDER_TIMEOUT`, no assertion outputs |
| `output-limit` | Output exceeds 1,024 bytes; `OPERATION_FAILED`, exact byte-budget diagnostic, no assertion outputs |
| `pin-mismatch` | All-zero executable pin prevents launch; `PROVIDER_UNAVAILABLE` |
| `recovery` | Repeat the original query successfully after the failures; PASS |

SQLite 3.54.1's pinned `shell.c` reports the missing-table error as `Parse error in 7th command line argument: no such table: absent\n`. The ordinal comes from the fixed SQL position in argv. Every command supplies `-noinit`, which prevents SQLite from reading user startup SQL even when OS user-home lookup remains available. This oracle was determined from the pinned source before execution. All databases are `:memory:`; no application fixture database needs deletion. The verifier checks canonical native provider observations, literal argv/stdin digests, exact executable pins, expected verdict/failure codes, stopped known process IDs and complete cleanup. It invokes the existing [`smoke.py`](../../scripts/smoke.py), which obtains framework evidence validation and reporting; there is no second product execution engine.

From the repository root, fetch and compile into a **new** task-owned cache:

```sh
python3 -B integrations/swarm-product-test/scripts/public-cli-project.py fetch \
  --project sqlite --cache-dir /absolute/new/sqlite-cache
```

Only the URLs in the lock are fetched. Redirects, encrypted or unexpected archive entries, symlinks, traversal and expanded sources over 20 MiB are rejected. The harness checks a combined 200 MiB cache/evidence budget and a minimum 5 GiB free reserve. Downloads or failed runs are retained for inspection; it never deletes existing cache or evidence directories.

After building both framework and module as described in the [reproduction guide](../../../../docs/09-operations/product-testing-reproduction.md), prepare exact reviewed inputs. Supply the independently selected hashes of the actual JARs and a JDK 17 location:

```sh
python3 -B integrations/swarm-product-test/scripts/public-cli-project.py prepare \
  --project sqlite --cache-dir /absolute/sqlite-cache \
  --evidence-dir /absolute/new/sqlite-evidence \
  --framework-jar /absolute/framework.jar --framework-sha256 APPROVED_FRAMEWORK_SHA256 \
  --module-sha256 APPROVED_MODULE_CLI_SHA256 --java-home "$JAVA_HOME" \
  --reviewed-by independent-review-pending --review-ref local-test-review://sqlite-runtime
```

`prepare` runs only the framework's handoff preparation command; it does not execute SQLite. An independent reviewer inspects the fixed inputs, generated suites and `prepared-plan.json`, then provides a separate approval JSON containing `status: approved`, `reviewer`, `review_ref`, `prepared_plan_sha256`, `harness_sha256`, `framework_sha256`, `module_cli_sha256`, `binary_sha256`, and `cases` mapping each case name to its exact handoff SHA-256. Approval is a separate artifact; the harness does not generate it.

```sh
python3 -B integrations/swarm-product-test/scripts/public-cli-project.py verify \
  --evidence-dir /absolute/sqlite-evidence --approval-file /absolute/reviewed-approval.json \
  --java-home "$JAVA_HOME"
```

Automated local CI may instead supply the separately reviewed [`local-ci-policy.json`](local-ci-policy.json) and its externally pinned SHA-256 to **both** `prepare` and `verify` using `--local-ci-policy PATH --policy-sha256 PIN`; omit the manual review-identity options and `--approval-file`. The actor is explicitly `local-ci-policy`. This policy pins harness, source lock, fixed fixtures, provider contract and review template. Only workspace/output paths, current IDs/times and hashes of artifacts built from reviewed inputs may vary. Before any module invocation, the harness compares every full generated suite and review envelope to those committed expectations and rejects extra operations, providers, inputs or changed oracles. The module still receives the independently configured prepared handoff pin. This is automated execution of reviewed local policy and does not claim a new human approval or authority for SIT/CD.

Run the offline harness boundary tests with:

```sh
python3 -B -m unittest discover \
  -s integrations/swarm-product-test/scripts/tests -p test_public_cli_project.py -v
```
