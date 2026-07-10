# Release Process

## Pre-Release Checks

Run bounded local verification before tagging:

```bash
MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m" ./mvnw verify
scripts/ci/check-schema-drift.sh
scripts/ci/verify-contracts.sh
scripts/ci/check-public-support-contract.sh
scripts/release/build-usage-kit.sh <version>
scripts/release/verify-usage-kit.sh target/spec-driven-auto-regression-<version>-usage-kit.zip <version>
scripts/release/verify-supported-provider-samples.sh <version>
```

## Release Artifacts

Publish the framework jar, usage-kit zip, checksums, SBOM, dependency-check report, and release notes. The usage kit must include docs, schemas, provider contracts, samples, and JDBC driver placeholders, but never vendor driver binaries.

## Acceptance

A release is acceptable only when CI is green, release assets are present, checksums are generated, usage-kit verification passes, and provider support status matches the support matrix.
