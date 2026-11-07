# SIT readiness before v0.3 suite execution

An optional `sit_readiness` section on the selected Env_Profile checks prerequisites before the existing runner starts any provider phase. It supports test data, external system health and pinned cross-repository versions through replaceable Java adapters. The only bundled implementation is a **local fixture**. Actual SIT remains unverified until an approved read-only adapter observes it.

## Run the local example

Build with Java 17 and bounded memory:

```sh
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -o -DskipTests package
java -Xmx1024m -jar target/spec-driven-auto-regression-0.3.2.jar run \
  --suite samples/40-sit-readiness/local_fixture/suite_manifest.yaml --profile local_v03
java -Xmx1024m -jar target/spec-driven-auto-regression-0.3.2.jar run \
  --suite samples/40-sit-readiness/local_fixture/suite_manifest.yaml --profile missing_data
```

The first run passes the local demonstration. The second returns a nonzero exit and blocks all phases with MISSING test data. Profiles `unhealthy_dependency` and `version_mismatch` demonstrate an unhealthy external system and a mismatched dependency build. These examples make no network calls. Their provider remains `sample_fake_provider`; they never establish product release evidence.

Use the printed `result_json` path with `report --result <path>` and `validate-evidence --result <path>`. Each child run contains `readiness/sit-readiness.json`, referenced by standard `result.json.evidence_refs` and `evidence_index.yaml` as `sit_readiness`. It contains the run/batch/suite/profile identity, plan digest, observation timestamps, adapter ID/version, expected/observed dependency version, synthetic marker, evidence facts, reason code and owner action. A blocked run has no provider operations or cleanup.

## Configuration and outcomes

```yaml
# Inside an existing v0.3 Env_Profile; keep its profile_id and target bindings.
sit_readiness:
  timeout_ms: 3000
  checks:
    - id: order_dataset
      kind: test_data
      adapter: team-sit
      expected_version: orders-seed-2026.11.1
      config:
        namespace: approved-team-test-namespace
        dataset: orders
    - id: inventory_dependency
      kind: dependency
      adapter: team-sit
      expected_version: immutable-build-or-commit-id
      config:
        service: inventory
        namespace: approved-team-test-namespace
```

`team-sit` is an illustrative, unimplemented adapter ID; selecting it without a plugin produces UNKNOWN and blocks. Required configuration keys are exact: `timeout_ms` and `checks`, with every check declaring `id`, `kind`, `adapter`, `expected_version`, `config`. Kinds are `test_data`, `external_system`, `dependency`. Use unique simple IDs, a nonempty expected version and a flat string options map. Quote YAML values that otherwise parse as numbers or booleans. Credentials must stay outside the profile/evidence; pass only non-secret identifiers or secret references understood by the adapter.

The total timeout is 1–60000 ms for all checks, with at most 32 checks and 32 options/evidence facts per check. Keys and versions are limited to 256 characters, IDs to 128, and option/fact values and owner actions to 2048. Each successful observation must be created during that check, have nonempty evidence facts and an exact matching version. The framework does not infer readiness from an exit code or an empty response.

| State | Meaning | Typical owner action |
| --- | --- | --- |
| READY | Fresh evidence verifies this prerequisite at the expected version | Continue only within pipeline permissions |
| MISSING | Required data or dependency is absent | Data/dependency owner supplies the isolated fixture or service |
| INVALID | Present but unhealthy or at a different version | Owner restores health or the pinned version |
| UNKNOWN | Observation unavailable, timed out, malformed or plugin unresolved | Repair access/plugin/timeouts and observe again |
| BLOCKED | Access, policy or another explicit prerequisite prevents checking | Resolve the named policy or dependency blocker |

Overall precedence is BLOCKED, INVALID, MISSING, UNKNOWN, READY; all individual gaps remain in evidence. Every non-READY outcome blocks the regression result. A fixture READY is accepted only with `execution_mode: local`, `evidence_classification: framework_verification_only` and no downstream release evidence. `validate` checks contracts and `--dry-run` compiles the plan; neither probes SIT. Dry runs explicitly print `sit_readiness: not_evaluated_dry_run`. The readiness configuration participates in the plan digest. Absent configuration retains existing behavior. Legacy v0.2 profiles reject this field.

## Build and replace a plugin

Implement `com.specdriven.regression.sit.SitReadinessAdapter` with a public no-argument constructor and stable unique `id()`, `version()`, `simulated()` metadata. Implement `check(SitReadinessRequest)` using read-only APIs and return `SitReadinessObservation`. `simulated()` must return true for every fake, fixture, cache playback or synthetic implementation. A real adapter must verify current state, health, namespace ownership as required, and versions; unsupported conditions return UNKNOWN, never READY.

Register the fully qualified class name in:

```text
META-INF/services/com.specdriven.regression.sit.SitReadinessAdapter
```

The registry discovers plugins using Java ServiceLoader and the context classloader. Unknown IDs, duplicate IDs, failed discovery and malformed results fail closed. There is no provider switch to edit. To replace a plugin, stop the process, replace its JAR (retain one implementation per ID), and restart. This is startup classpath discovery, not hot reload. The bundled `fixture` ID is reserved; use a distinct team ID. Embedded callers can inject `SitReadinessRegistry` / `SitReadinessService` into `V03RuntimeExecutionService`.

An executable external JAR example is included. After building the framework, from the repository root:

```sh
mkdir -p target/sit-example/classes target/sit-example/plugins
javac --release 17 -cp target/classes -d target/sit-example/classes \
  samples/40-sit-readiness/plugin-example/src/example/LocalReadinessAdapter.java
cp -R samples/40-sit-readiness/plugin-example/resources/META-INF target/sit-example/classes/
jar --create --file target/sit-example/plugins/local-readiness.jar -C target/sit-example/classes .
cp -R samples/40-sit-readiness/local_fixture target/sit-example/suite
python3 - <<'PY'
from pathlib import Path
p = Path('target/sit-example/suite/env_profiles/local_v03.yaml')
p.write_text(p.read_text().replace('adapter: fixture', 'adapter: example-local'))
PY
java -Xmx1024m -Dloader.path=target/sit-example/plugins/ \
  -cp target/spec-driven-auto-regression-0.3.2.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher run \
  --suite target/sit-example/suite/suite_manifest.yaml --profile local_v03
```

This example remains simulated and makes no external calls. The evidence identifies `example-local`, version `example-1`. `java -jar` with this project's default Boot JarLauncher does not add arbitrary plugin JARs to the runtime classpath. Use the explicit PropertiesLauncher command and `loader.path`, or a correctly assembled ordinary classpath. Compile against `target/classes` or the original thin artifact; Boot's repackaged JAR stores API classes under `BOOT-INF/classes`. The test suite also builds two independent replacement JARs with the same ID and verifies changed behavior without editing framework sources.

## Operational responsibilities

- **Agent:** explain report facts, missing evidence and owner actions; never invent readiness or promote a fixture result.
- **Adapter:** perform bounded read-only observations, return immutable facts, avoid payloads/secrets, honor the request deadline and interruption, and close its clients. Metadata methods must not block. An adapter receives only its check specification and deadline, not provider runtime context.
- **Pipeline / SIT owner:** select and enforce the approved profile, pin version expectations, authorize the suite, manage namespaces/reservations and coordinate provisioning, seeding, cross-repository deployment and cleanup. Apply the gate to each executable leaf suite. A configured prerequisite list is only as complete as the pipeline's reviewed requirements.

READY is a time-scoped observation, not a lock, reservation or authorization to mutate shared SIT. State can change after preflight. The subsequent existing provider suite may mutate systems, so the pipeline must separately enforce ownership and permissions. This extension does not implement shared-environment locks or lease renewal.

Plugins run as trusted Java code in-process. One deadline bounds framework waiting; the worker pool has at most four daemon threads and no queue. Timed-out tasks are interrupted; subsequent checks in that preflight are UNKNOWN. An uncooperative plugin cannot be forcibly stopped, may occupy a worker until it returns, and causes capacity exhaustion to block later checks. Use client connect/read/query deadlines and cooperative cancellation; do not install untrusted code. Exceptions are reported with stable codes, not raw exception text. Existing evidence masking is defense in depth, not a substitute for adapters withholding sensitive data.

Provider support is unchanged: `external_runner`, `kubernetes_runtime`, and `vm_runtime` remain contract-only. This slice does not perform ADO, MES, device, real SIT or cross-repository deployment calls.
