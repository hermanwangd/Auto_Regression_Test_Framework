# Standalone Redis string fixture

Point the runner at a dedicated standalone Redis endpoint using `FRAMEWORK_REDIS_URI`. The URI requires a host and port, with optional credentials and database index. For example, use `redis://127.0.0.1:6379/0` for a local service. `rediss` uses the JVM trust store with hostname verification. The client has two-second connection and socket timeouts.

```sh
java -jar target/spec-driven-auto-regression-0.3.2.jar run \
  --suite samples/20-provider-capability-p0/data/redis/suite_manifest.yaml --profile external_redis
```

The suite writes a UTF-8 Redis string, checks the complete value and a remaining TTL in the range 0–120,000 ms, then removes the string and its ownership record. Keys have the pattern `sdr:redis:{<run-scoped-id>}:value` and `sdr:redis:{<run-scoped-id>}:owner`. The setup step exposes the data key as `step://create_string/physical_key` for an application under test. Provider evidence records this key, outcomes and TTL. Each test case and target has an independent namespace. The server expires both keys even if the runner stops before cleanup.

Key aliases allow 1–64 ASCII letters, digits, underscores and hyphens, starting with a letter or digit. Values allow up to 16 KiB of UTF-8 data, including the empty string. TTL inputs accept integers from 1 to 3,600 seconds. Runtime operations use fixed Lua scripts so Redis checks ownership atomically. The fixture account needs EVAL and the scripts' EXISTS, SET, TYPE, GETRANGE, PTTL and DEL commands on the two fixture keys. A pre-existing key prevents creation. Cleanup preserves a replaced value and reports an ownership conflict.

For cache invalidation, seed a key with a TTL long enough for the test, pass the setup output `physical_key` to the application, then check the same alias after the application acts. The read-only check passes when the data key is absent, including when the ownership record is still present. A present data key fails with `REDIS_KEY_PRESENT`. Cleanup then removes the remaining ownership record. Redis reports the same absence after TTL expiry, so choose a TTL longer than the application's expected response time when testing explicit invalidation.

```yaml
verify:
  - id: confirm_invalidation
    type: provider_check
    target: cache
    op: redis_absent
    with:
      key: order
```
