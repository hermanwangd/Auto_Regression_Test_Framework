# Mixed Contract Baseline

This suite keeps the historical `mixed_wiremock_jdbc_nats` path for public path
stability, but the active sample uses the Framework v0.3 authoring model.

The suite verifies one deterministic mixed-provider baseline:

- `http_mock.v0.3` starts the local HTTP mock target.
- `rest_client.v0.3` calls the generated mock `base_url`.
- `jdbc.v0.3` seeds, queries, and cleans up order data.
- `nats.v0.3` publishes and observes an event payload.

The test cases reference suite target names directly. They do not use
Provider Instance files, copied Provider Contracts, `provider_id`, `parameters`,
or `bind_as`.

Run it with:

```bash
regress validate --suite samples/10-contract-baseline/mixed_wiremock_jdbc_nats/suite_manifest.yaml --profile local_v03
regress run --suite samples/10-contract-baseline/mixed_wiremock_jdbc_nats/suite_manifest.yaml --profile local_v03
```

The evidence is framework verification evidence only. It does not prove a
downstream Product/RP release unless owners provide real AC, expected results,
environment bindings, and release evidence policy.
