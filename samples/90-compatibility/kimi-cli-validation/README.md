# Click compatibility sample

This source-only candidate derives from the twelve reviewed source, documentation and license members of the original SWARM-CLI-01 first batch. It lives at `samples/90-compatibility/kimi-cli-validation/`; the earlier proposed destination was `kimi-cli-click-first-batch/`. Original ZIP SHA-256: `6f98487ba84152ef861db8a1bede98052b43e66722da1e4d190fa878cdf00563` (32,384 bytes). Original evidence is retained privately and excluded here.

The historical package runs remain distinct: run-001 passed 5/6 (CLK-004 expectation-string failure); run-002 passed 6/6 with changed test/manifest pins. These are historical package observations, not fresh Framework or Module acceptance. Click has no member in the existing 116-case catalog. Supplemental executions leave that denominator and formal completion count unchanged. This sample grants no Native Swarm, SIT or release credit.

The manifest and dependency files retain first-batch Click 8.5.1 provenance. Fresh execution must record the interpreter and actual dependency versions separately; the bounded integration check uses Python 3.12.14 / Click 8.4.2 and does not claim the historical lock closure. Click requires Python >=3.10. Its BSD-3-Clause license and metadata remain in `licenses/`.

`CliRunner` overrides explicitly remove inherited `SWARM_NAME` and `SWARM_COUNT` unless a case supplies intentional values. An empty environment mapping alone does not isolate process variables. The runner allocates an exclusive UUID directory for each invocation; it admits only a new report from that directory and emits blocked cases after startup, execution or missing-report failure. Genuine fresh pytest assertion failures retain their failed verdicts. The runner is a local developer sample, not an alternative Framework runner.

In a task-owned environment with the recorded dependencies installed, run the narrow regression controls:

```sh
python -B tests/test_runner_safety.py
```

The optional package harness requires pytest. Store generated outputs in a private evidence root:

```sh
python -B scripts/run_suite.py --suite click --evidence-root /absolute/private/evidence
```

Each run produces a fresh `run-<uuid>/` with input hashes, environment inventory, command and timestamps, process status, fresh JUnit when available, and case observations. Do not commit generated evidence. The received dependency lock is a version snapshot without distribution hashes. Generic optional input flags and full transitive source/dependency enforcement remain outside this narrow change.

The real integration route is the existing reviewed `cli_process.v0.3` provider through the packaged Swarm product-test module. Invoke the reviewed app directly with a pinned interpreter and literal argv; package pytest success cannot establish Framework or Module success. The supplemental CLK-002 contract is exit 0, stdout `wrote=default.txt;count=1;upper=false\n`, empty stderr, and file bytes `default-bot\n`, with both SWARM variables absent. A fresh reviewed handoff, concrete dependency pins and independent actual-evidence review are required for that invocation.

The fresh Framework/Module CLK-002 case is **NOT_RUN**: the protected full-workspace census exceeded its 200,000-entry bound before completing, before any product child started. The bound, protected controller, source JAR and 116-case catalog remain unchanged. The invocation packet is prepared; it is not execution evidence.
