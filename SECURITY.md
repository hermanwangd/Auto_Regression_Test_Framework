# Security Policy

## Supported Versions

Only the latest published v0.2.x release line receives security fixes.

## Reporting Vulnerabilities

Report vulnerabilities privately to the repository owner before public disclosure. Include affected version, reproduction steps, impact, and any safe diagnostic output.

## Secret Handling

Do not commit raw credentials, JDBC URLs, bearer tokens, API keys, private keys, NATS credentials, Kafka credentials, or IBM MQ credentials. Runtime secrets must use approved refs such as `env://JDBC_CONNECTION` and must be supplied by the runner environment.

## Evidence and Logs

Evidence, result JSON, logs, and reports must mask secret values. If raw secret leakage is detected, validation/report commands should fail before evidence is used for release review.
