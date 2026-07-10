# Contributing

## Branches

Use short-lived branches for focused changes. Release stabilization may use `release/<version>` branches.

## Development

Use Java 17+ and Maven Wrapper. Keep commands bounded in memory, for example:

```bash
MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m" ./mvnw test
```

## Tests

Add or update focused tests for changed behavior. Use `*Test` for unit/component tests and `*IT` for integration tests. For runtime/provider changes, include happy, failure, and boundary coverage where applicable.

## Commits

Use concise imperative commit messages such as `fix: enforce schema contract drift gate` or `feat: add jdbc driver discovery diagnostics`.

## Pull Requests

Include summary, changed files, test evidence, release impact, and any known limitations. User-facing interface changes must update docs, samples, and contract tests.
