# Reviewed local product tests

These are synthetic, offline `component / regression / local` examples using only `sample_fake_provider.v0.3`. They prove the reviewed-input boundary; they do not observe SIT, verify a deployed Cognee instance, or qualify release evidence.

- `reviewed_local`: explicitly uses `knowledge_mode: not_used`; no Cognee metadata, readiness, or shared state is required.
- `reviewed_required`: adds a synthetic receipt with the exact inspected Dev204 `context.selected` / `context.excluded` / `cogneeSelection.candidateSearch.candidates` envelope. Its opaque upstream digest fields are demonstration values, not evidence of an actual upstream replay.
- `assertion_failure`: has the same valid local handoff mechanics but expects the wrong status, so the framework assertion must fail.

The checked-in review identities and future validity dates are fixtures, not actual approval. `review-input.json` records supplied metadata and explicit file/contract allowlists. `handoff/reviewed-tests.json` contains their raw-byte digests; the independent `.sha256` sidecar is illustrative input to the controlled invocation. A real controlled pipeline must review and supply its own pin.

From the framework repository, prepare the local example:

```sh
java -jar target/spec-driven-auto-regression-0.3.2.jar prepare-product-test-handoff \
  --suite samples/41-product-testing/reviewed_local/suite_manifest.yaml \
  --profile local_v03 \
  --review-input samples/41-product-testing/reviewed_local/review-input.json \
  --out samples/41-product-testing/reviewed_local/handoff/reviewed-tests.json
```

Preparation returns `prepared_not_approved`; it hashes and validates existing static files and never runs a provider. After independently reviewing the JSON and digest, invoke the single leaf suite with the approved pin:

```sh
REGRESS_REVIEWED_HANDOFF_SHA256="$(cat samples/41-product-testing/reviewed_local/handoff/reviewed-tests.json.sha256)" \
  java -jar target/spec-driven-auto-regression-0.3.2.jar run \
  --suite samples/41-product-testing/reviewed_local/suite_manifest.yaml --profile local_v03
```

For `reviewed_required`, use the corresponding paths and additionally pass `--selection-receipt samples/41-product-testing/reviewed_required/handoff/selection-receipt.json` to preparation. Obtaining a real receipt belongs to the existing authorized Swarm selection operation; this command performs no Cognee request or governance decision.

Use the returned result path with the existing `validate-evidence --result <result.json>` and `report --result <result.json>` commands. Check the framework verdict separately from command completion. Replacing any pinned fixture, oracle, manifest, profile, contract, or receipt must block before provider dispatch. The handoff itself must match the separately supplied raw-byte digest. Changes made after snapshot creation cannot affect the private execution inputs.
