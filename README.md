# Spec-Driven Auto Regression

A command-line regression test framework for **suite-mode provider execution**. You describe a test suite and its providers declaratively (YAML), validate them against provider contracts, run them against real or mocked backends, and get back structured results plus release-grade evidence.

- **Version:** 0.2.5 &nbsp;·&nbsp; **Contract version:** `v0.2`
- **Runtime:** Java 17+, built on Spring Boot 3.5 (Maven)
- **License:** Apache-2.0

## What it does

Given a **suite manifest** and a **profile**, the CLI:

1. Validates the suite and its provider instances against their **provider contracts**.
2. Resolves the profile (environment + runtime mode + bindings) and executes each test through a **provider runtime**.
3. Writes a **result JSON** and a masked **evidence** tree, then lets you report on and re-validate that evidence.

It consumes bindings to external systems; it does **not** provision brokers, queue managers, or databases itself.

### Supported providers

Per the [provider support matrix](docs/09-operations/provider_support_matrix.md), the `supported` provider types in 0.2.5 are:

- **HTTP** — `rest_client`, `wiremock_http_mock`
- **Messaging** — `kafka`, `ibm_mq`, `nats`
- **Data** — `jdbc`
- **RPC** — `grpc_mock` (with `grpc_client` stimulus), `soap_mock`
- **Verification** — `common_verify`, `artifact_compare`, `polling_observer`

Additional types (`external_runner`, `shell_command`, `kubernetes_runtime`, `vm_runtime`) are `contract_only`: the contract is published but runtime execution is not yet a supported release path. Each provider ships a contract under `docs/02-architecture/contracts/provider-contracts/`.

## Requirements

- JDK 17+
- No system Maven required — the repo ships the Maven Wrapper (`./mvnw`).

## Build

```bash
./mvnw package        # compile, test, and build the runnable jar
```

The jar lands at `target/spec-driven-auto-regression-0.2.5.jar`.

## Quick start

Run the smallest end-to-end sample (`samples/00-getting-started/golden_e2e/`):

```bash
# Validate the suite against its contracts and profile
./regress validate --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml --profile local_golden

# Execute it (writes a result JSON + evidence)
./regress run --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml --profile local_golden
```

The `regress` script compiles the project on first use and runs the CLI directly — no separate `package` step needed. To use the packaged jar instead:

```bash
java -jar target/spec-driven-auto-regression-0.2.5.jar validate --suite <suite_manifest> --profile <profile>
```

## CLI commands

The public suite-mode interface is five commands:

| Command | Purpose |
|---|---|
| `validate --suite <manifest> [--profile <p>]` | Validate a suite against provider contracts (and, with `--profile`, profile compatibility). |
| `run --suite <manifest> --profile <p>` | Execute the suite; emit result JSON + evidence. |
| `run --suite <manifest> --dry-run [--profile <p>]` | Resolve and plan execution without invoking providers. |
| `report --result <result_json> [--format text\|yaml]` | Summarize a result JSON. |
| `validate-evidence --result <result_json>` | Re-validate the evidence tree (masking, coverage, classification). |

`--suite` accepts either a leaf suite manifest or a suite-group manifest (`child_suites[]`).

Additional product-repo/readiness subcommands (`init-product-repo`, `check-readiness`, `init-rp`, `check-rp`, `generate-tests`, `draft-expected-results`) support the spec-driven authoring workflow; run `./regress` with no arguments for usage.

## Concepts

- **Suite manifest** — the entry point: a directory with `suite_manifest.yaml` listing `tests[]` (a leaf) or `child_suites[]` (a group), plus artifact roots for profiles, provider instances, fixtures, and expected results.
- **Provider contract** — declares a provider type's operations, inputs, bindable outputs, and executable runtime modes.
- **Provider instance** — a concrete provider that conforms to a contract.
- **Profile** — an environment/execution profile that selects the **runtime mode** and supplies binding keys for a run.
- **Runtime modes** — `native`, `mock`, `stub`, `ephemeral`. `native` runs require externally provisioned endpoints (and, for Kafka/IBM MQ/NATS, release secrets); the framework consumes the bindings rather than starting the backend.
- **Evidence** — masked, classified artifacts written alongside each result, re-checkable with `validate-evidence`.

## Samples (usage kit)

The `samples/` tree is the public usage kit. Canonical groups:

| Path | Contents |
|---|---|
| `00-getting-started/golden_e2e/` | Smallest executable lifecycle sample. |
| `10-contract-baseline/mixed_wiremock_jdbc_nats/` | Mixed-provider contract baseline. |
| `20-provider-capability-p0/` | P0 provider capability suites by family. |
| `30-cross-provider-groups/mock_server_cross_verify/` | Cross-provider mock-server verification group. |
| `40-evidence-reporting/evidence_hardening/` | Result and evidence validation fixtures. |
| `90-compatibility/dummy_rest/` | Compatibility-only fixture (not a capability gate). |

See `samples/README.md` for the layout rules.

## Repository layout

```
src/            Java source (com.specdriven.regression) and tests
schemas/        v0.2 artifact schemas (suite_manifest, provider_contract, result, ...)
docs/           Spec-driven lifecycle (00-intake-scope … 10-change-control) + user guide
samples/        Public usage-kit sample suites
scripts/        CI and release gate scripts
agent-skills/   product-repo-readiness Claude skill
regress         CLI launcher wrapper
```

The full user guide lives at `docs/09-operations/test_framework_user_guide.md`.

## Development

```bash
./mvnw test                              # unit/component tests (*Test)
./mvnw verify                            # unit + integration (*IT) + coverage report
./mvnw test -Dtest=RegressionCommandTest # a single test class
```

Tests mirror the source package layout under `src/test/java/`; framework-verification fixtures live under `src/test/resources/framework-verification/`. See [`AGENTS.md`](AGENTS.md) for the full contribution guidelines and coding conventions.

## License

Apache License 2.0. See the license headers and `pom.xml` for details.
