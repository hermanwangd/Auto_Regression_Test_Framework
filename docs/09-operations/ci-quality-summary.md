# CI quality summary for a main commit

After the CI workflow finishes for a push to `main`, **Main quality summary** joins the CI, Local Product Tests and Static security analysis runs for that full Git SHA. Its Actions job summary is readable in the browser. The `quality-summary-<commit>` artifact retains the same summary in Markdown and machine-readable `ci-quality.v1` JSON. The output links to the contributing run attempts.

- JUnit totals distinguish Linux framework, Windows CLI and Linux Swarm module assertions. The second Linux framework build in Local Product Tests is excluded from those totals.
- Product case counts use SQLite and NATS module receipts and the matching canonical result digests. They include deliberately failing product scenarios. A skipped test, blocked case and invocation that never ran keep distinct outcomes.
- Per-contract receipts record the platform, scenarios and operations observed by the conformance kit. Native and external scenario rows use the conclusion of each named CI step. The two packaged suite traceability runs retain their selected and unselected cases.
- SAST counts and diagnostics are per rule pack and scan input. The JSON retains the diagnostic records and the unanalysed file list. Dependency-Check findings, actually suppressed findings and the checked-in suppression rules with expiry dates are reported separately.
- The report records missing artifacts and unverified environments. A missing required input makes the summary job fail after publishing its partial report. Company SIT and native Oracle, Db2 and macOS require independent runs.

To reproduce a summary from the same checkout with an authenticated GitHub CLI, use a fresh directory for inputs. The collector reads only completed `main` push runs for the specified commit.

```sh
sha=$(git rev-parse HEAD)
inputs=$(mktemp -d)
bash scripts/ci/collect-quality-inputs.sh "$sha" "$inputs"
python3 scripts/ci/quality_summary.py "$sha" "$inputs" target/quality
```

[Quality workflow](../../.github/workflows/main-quality.yml) · [Provider conformance](../04-planning/provider-adapter-conformance-kit.md) · [Run traceability and coverage](../oracle-trace-coverage.md#review-a-single-invocation)

Local Python runs of the conformance gate and quality summary use the hash-pinned XML parser in [quality-requirements.txt](../../scripts/ci/quality-requirements.txt). Install it in a virtual environment with `python3 -m pip install --require-hashes -r scripts/ci/quality-requirements.txt`.
