# Local SIT readiness integration examples

See [the operations guide](../../docs/09-operations/sit-readiness.md) for runnable commands, the report contract and external JAR loading.

`local_fixture` uses only fixture observations and the existing sample fake provider. Profiles: `local_v03` (READY), `missing_data` (MISSING), `unhealthy_dependency` (INVALID), and `version_mismatch` (INVALID). Every failing preflight skips all provider phases, including cleanup. These examples verify framework behavior; they do not observe SIT.

`plugin-example` is a separately compiled ServiceLoader JAR, also synthetic. It demonstrates replacing the adapter without changing the framework or its provider runtime.
