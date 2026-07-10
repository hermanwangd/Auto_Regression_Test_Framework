# DSL v0.3 Samples

These samples exercise the DSL v0.3 preview public interface. v0.3 test cases do not use Provider Instance files.

## Layout

- `golden/`: deterministic framework lifecycle sample using `sample_fake_provider.v0.3`.

## Commands

```bash
regress validate --suite samples/v0_3_dsl/golden/suite_manifest.yaml --profile local_v03
regress run --suite samples/v0_3_dsl/golden/suite_manifest.yaml --profile local_v03 --dry-run
regress run --suite samples/v0_3_dsl/golden/suite_manifest.yaml --profile local_v03
```

## Rules

- Test cases reference suite target names directly.
- Suite targets define `provider_contract`.
- Env_Profile targets supply runtime mode and bindings for the same target names.
- v0.3 test cases must not contain `provider_id`, `provider_instance`, `parameters`, `bind_as`, `data_binding`, `datasets`, `fixtures`, or `expected_results`.
