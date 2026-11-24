# Security Policy

## Supported Versions

Only the latest published v0.2.x release line receives security fixes.

## Reporting Vulnerabilities

Report vulnerabilities privately to the repository owner before public disclosure. Include affected version, reproduction steps, impact, and any safe diagnostic output.

## Secret Handling

Do not commit raw credentials, JDBC URLs, bearer tokens, API keys, private keys, NATS credentials, Kafka credentials, or IBM MQ credentials. Runtime secrets must use approved refs such as `env://JDBC_CONNECTION` and must be supplied by the runner environment.

## Evidence and Logs

Evidence, result JSON, logs, and reports must mask secret values. If raw secret leakage is detected, validation/report commands should fail before evidence is used for release review.

## Static analysis for imports from main

Pull requests run `SAST / new High or Critical` against the PR base. Every push to `main` runs `SAST / full repository` on the resulting commit with the pinned GitLab and Semgrep community rules. It submits all tracked regular files, including tests and samples, and checks both rendered runner Jobs (`produce` and `collect`). Any High or Critical finding fails the check, including findings that predate the push. Scanner failures and diagnostics also fail it, except for the known YAML parser diagnostic on the raw Helm Job template when both rendered Jobs pass both rule sets.

Before importing, verify the full-repository check on the exact commit SHA. Its `full-sast-<SHA>` artifact contains severity counts, blocking rule IDs and locations, diagnostics, and scan coverage. Source excerpts are omitted.
