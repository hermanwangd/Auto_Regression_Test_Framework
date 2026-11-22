# Local runner variant without IBM MQ

The opt-in Maven profile `no-ibm-mq` creates an additional local delivery candidate. The default full build retains its MQ dependency. The profile keeps the SDK on the provided compile/test classpath and explicitly excludes its JAR from Spring Boot packaging; it does not delete files from a completed artifact.

A generated immutable descriptor controls the vendor-free runtime factory, registries and capability preflight. `build-info --format json` declares artifact availability, not protocol acceptance. Missing, duplicate or invalid descriptors disable MQ. JVM properties, environment values and mission fields cannot enable it. A full descriptor also requires the SDK class resource to be physically present, without loading it.

IBM MQ requests are UNSUPPORTED before SDK linkage/operations. Existing `unsupported_suite_runtime` preflight and generic `UNKNOWN_PROVIDER_TYPE`/`TARGET_RESOLUTION_FAILED` vocabulary is preserved with an explicit build-variant reason. Direct unavailable factory execution throws UnsupportedOperationException; it is not a fabricated canonical MQ result or ClassNotFound substitute.

This variant omits IBM MQ client/provider execution. It is not full-runner parity. Other packaged providers remain unverified unless their actual new-variant receipts demonstrate behavior. Full-runner MQ mock/source tests do not become native MQ evidence and do not transfer. Requirement/AC/risk coverage remains UNKNOWN without authoritative scope; packaging/source test counts are not scenario coverage.

Actual source/JAR/descriptor/dependency/image/bundle pins and REST/CLI/unsupported-MQ/report/log/receipt collection results must be recorded independently. No external publication, upload, credentials, sharing or SIT change is authorized. MQ omission is not a complete third-party redistribution review. Source authoring is not a successful build or runtime result.
