# Company Swarm — standalone import and version pinning

## Build and test

Requirements: JDK 17+, Maven 3.9+, and access to the approved dependency mirror.

```bash
mvn -B -ntp verify
mvn -B -ntp -DskipTests install
mvn -B -ntp -f examples/company-swarm-consumer/pom.xml test
```

A successful independent consumer test proves the public library classes and ServiceLoader registration work without any `engcim-swarm` source tree. It does **not** prove real SIT, E2E, or downstream deployment.

## Import from a company package registry

Publish the standard (non-`cli`) artifact to an approved internal Maven Registry, retaining this POM coordinate:

```xml
<dependency>
  <groupId>com.featuredeliveryintelligence</groupId>
  <artifactId>swarm-product-test</artifactId>
  <version>1.0.0</version>
</dependency>
```

Register the company's credentials, repository URL and artifact permissions in the consumer's Maven settings or dependency-management configuration; never commit credentials. Prefer a fixed version and immutable artifact checksum. Do not use `SNAPSHOT` for company-wide imports.

## Runtime dependency

The module intentionally does not depend on framework source. Supply the trusted Framework JAR and independently verified SHA-256 through host configuration, as described in [README](../README.md). Call the exposed `product-test run --request <request.json>` CLI or `ProductTestingModules.resolve(...)` API.

Only the current local and explicitly supported integration slices are claimed. Real RP SIT, Cross-RP E2E and release promotion require separate runtime evidence and policy gates.
