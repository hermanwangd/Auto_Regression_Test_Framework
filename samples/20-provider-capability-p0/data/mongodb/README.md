# Native MongoDB document lifecycle

The suite stores an order, reads it back, compares its complete BSON payload with the independently authored expected artifact, and checks its presence. Cleanup deletes the document selected by the same run, case, target and logical key. Repeating setup within that run upserts the same document. Repeating cleanup succeeds with a deletion count of zero.

The stored document has three top-level fields. `_id` is a string, `owner` is the framework's run-scoped ownership marker, and `payload` contains the fixture object. Other steps can use `step://seed_order/document_id` to pass the exact MongoDB `_id` to the application under test before cleanup.

`mongo_find` queries that document by its logical key and a JSON criteria artifact. Supply 1–16 top-level payload fields with scalar values, for example `{"status":"confirmed","expedited":false}`. A field name starts with an ASCII letter or underscore and may contain letters, digits, underscores and hyphens up to 64 characters. MongoDB checks equality for every supplied field using exact BSON types and binary string comparison. A null criterion matches an explicit null. Missing fields, a different value or an absent owned document yield `matched: false`. The sample asserts both a match and a miss.

Use a MongoDB fixture database with read/write access to `swarm_fixture_documents`. The URI must include the database name. MongoDB may create the collection on first write. The collection remains available for subsequent runs.

From the repository root, after packaging the runner, export the fixture database URI as `MONGODB_URI`. For a local fixture, use the standard MongoDB URI scheme with host `127.0.0.1`, port `27017` and database path `/swarm_fixture`.

```sh
: "${MONGODB_URI:?Export the MongoDB fixture URI before running this sample.}"
java -Xmx512m -jar target/spec-driven-auto-regression-0.3.2.jar run \
  --suite samples/20-provider-capability-p0/data/mongodb/suite_manifest.yaml \
  --profile external_mongodb
```

The provider accepts a logical key of 1–64 ASCII letters, digits, hyphens or underscores. JSON fixture files may contain nested documents, arrays and scalar values up to 64 KiB total and 16 levels deep. Field names beginning with a dollar sign or containing a dot or NUL are rejected. JSON integers must fit a signed 64-bit value and floating-point numbers must be finite. The read checks BSON equality, including numeric types and array order. Each operation has a five-second driver deadline. The MongoDB evidence records the scoped document identifier, acknowledgement, comparison result, existence or deletion count. Connection details and document contents stay outside these records.
