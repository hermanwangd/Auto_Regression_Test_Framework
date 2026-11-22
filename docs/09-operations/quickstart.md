# Quick Start

This usage kit is paired with `spec-driven-auto-regression-{{VERSION}}.jar`.
Run commands from the extracted `usage-kit/` directory.

## Golden E2E

```bash
java -jar ../spec-driven-auto-regression-{{VERSION}}.jar validate --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml
java -jar ../spec-driven-auto-regression-{{VERSION}}.jar run --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml --profile local_v03
java -jar ../spec-driven-auto-regression-{{VERSION}}.jar report --result <generated_result_json> --format text
java -jar ../spec-driven-auto-regression-{{VERSION}}.jar report --result <generated_result_json> --format json
```

## Provider Capability Suite

```bash
java -jar ../spec-driven-auto-regression-{{VERSION}}.jar validate --suite samples/20-provider-capability-p0/suite_manifest.yaml
java -jar ../spec-driven-auto-regression-{{VERSION}}.jar run --suite samples/20-provider-capability-p0/suite_manifest.yaml --dry-run
```

Kafka, IBM MQ, and external JDBC samples validate without external systems, but
native runtime execution requires project-provided environment bindings and
approved drivers or client endpoints.
