# Troubleshooting

Use `validate` before `run`. Validation failures are contract or configuration
issues and do not enter provider runtime.

## Common Failures

- `MISSING_PROVIDER_INSTANCE`: check the suite `artifact_roots.provider_instances`.
- `MISSING_ENV_PROFILE`: check `env_profiles/<profile>.yaml` exists.
- `UNSUPPORTED_OPERATION`: the DSL operation is not allowed by the Provider Contract.
- `UNSUPPORTED_BIND_AS`: the parameter binding is not allowed by the Provider Contract.
- `JDBC_DRIVER_NOT_FOUND`: supply the Oracle or DB2 driver through `--driver-path`,
  `--driver-dir`, `REGRESS_DRIVER_PATH`, or `drivers/`.
- `NATS_CONNECTION_FAILED`, `KAFKA_CONNECTION_FAILED`, `IBM_MQ_CONNECTION_FAILED`:
  verify external messaging endpoint, credentials, and network access.

## Evidence Checks

Run:

```bash
java -jar ../spec-driven-auto-regression-{{VERSION}}.jar validate-evidence --result <result_json>
```

Evidence validation checks result references, evidence index files, required
provider metadata, and secret masking.
