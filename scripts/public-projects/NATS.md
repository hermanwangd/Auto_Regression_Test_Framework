# Public NATS protocol proof

This harness starts the official Go `nats-io/nats-server` v2.15.2 broker and calls the existing packaged product-testing module. The framework's existing native NATS provider sends `SUB` and `PUB`, then requires the exact echoed `MSG` subject, subscription ID, byte count, payload and CRLF. A `PONG` alone cannot pass.

The committed lock records the release commit, official SHA256SUMS, archive sizes/digests and extracted executable digests for Linux amd64 and macOS arm64. Downloads and extraction are bounded; only the regular broker executable is extracted. The broker listens on `127.0.0.1`, uses an owned random name and subject, and enables no JetStream, monitoring or persistence.

After building the framework and module, CI uses one harness:

```sh
python3 scripts/public-projects/nats.py verify \
  --output "$RUNNER_TEMP/nats-evidence" \
  --framework-jar "$CI_FRAMEWORK_JAR" \
  --framework-sha256 "$CI_FRAMEWORK_SHA256" \
  --module-cli integrations/swarm-product-test/target/swarm-product-test-1.0.0-cli.jar \
  --module-sha256 "$CI_MODULE_SHA256" \
  --ci-policy-sha256 <independently configured policy digest>
```

`verify` derives pins under the committed `nats-ci-policy.json`, whose actor is `local-ci-policy`. Its source, fixture, oracle and product asset hashes are checked first. The complete generated suite, profile, test, contract and handoff facts are compared with the committed expectations before every module call. Only local paths, the loopback port, owned UUIDs, current validity times and built-artifact hashes vary. The policy is automated local test authority; it does not represent human review.

The four cases must produce validated canonical evidence: positive PASS, a deliberately wrong published-value oracle FAIL (`ASSERTION_FAILED`), stopped-broker FAIL (`NATS_CONNECTION_FAILED`), and restarted-broker recovery PASS. Each invocation must have a fresh identity. The module independently checks the configured handoff pin and imports the framework's result, summary, evidence index and report. The harness records owned PID termination and closed-port cleanup, including when execution fails.

For a separately reviewed execution, first run `prepare` with the same artifact arguments. It writes `prepared-plan.json` and four exact handoff pins without executing the broker. Supply an independent JSON approval containing `decision: "APPROVE"`, `approved_by`, `plan_sha256`, and `handoff_sha256` (a map of all four case names to pins), then run:

```sh
python3 scripts/public-projects/nats.py run \
  --prepared /absolute/preparation-directory \
  --approval /absolute/independent-approval.json \
  --output /absolute/fresh-execution-directory
```

Use `--asset-dir` with `prepare` or `verify` to reuse a local cache; every archive, official checksum file and executable is verified again. Preparation requires 5 GiB free disk; downloaded assets, extracted binaries and evidence must stay within the 250 MiB task budget. The harness supports Python 3.9+ and Java 17+.

All output is `framework_verification_only`, with release eligibility false. This proves local native public-broker execution through the module CLI. It does not prove a native Swarm agent invocation, company environment acceptance, a CD deployment or SIT execution. Offline harness boundary checks run with `python3 -B -m unittest discover -s scripts/public-projects -p 'test_nats.py'`.
