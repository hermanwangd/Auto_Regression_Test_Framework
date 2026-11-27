# Native S3 object round trip

This standalone DSL v0.3 suite uploads the fixture bytes, checks object metadata with HEAD, compares a fresh GET against the upload's SHA-256, and removes the object during cleanup. It uses path-style requests with AWS Signature Version 4 and supports MinIO.

Supply an existing bucket and four environment variables on the runner.

| Variable | Value |
| --- | --- |
| `REGRESS_S3_ENDPOINT` | An HTTP(S) service origin, for example `http://127.0.0.1:9000` for local MinIO |
| `REGRESS_S3_BUCKET` | Name of the existing fixture bucket |
| `REGRESS_S3_ACCESS_KEY` | Access key supplied by the environment owner |
| `REGRESS_S3_SECRET_KEY` | Corresponding secret key |
| `REGRESS_S3_REGION` | Optional signing region, default `us-east-1` |

For local MinIO, start the server with a disposable data directory using `minio server /path/to/disposable/data --address 127.0.0.1:9000`. Using an already configured MinIO Client alias, create the bucket with `mc mb --ignore-existing local/regress-fixtures`. Set `REGRESS_S3_BUCKET=regress-fixtures` and export the other variables using the endpoint and credentials for that server.

From the framework repository root, run the suite and validate the result file printed by the runner.

```sh
./regress run --suite samples/20-provider-capability-p0/storage/s3/suite_manifest.yaml --profile external_s3
./regress validate-evidence --result <result.json>
```

The runner needs GetObject, PutObject and DeleteObject permissions under `regress/`. S3 may require ListBucket permission to distinguish a missing object from an access denial during repeated cleanup. Versioned buckets require GetObjectVersion and DeleteObjectVersion. Each generated key includes a hash of the run ID, test case and target. A PUT uses `If-None-Match: *`. Cleanup checks the run's ownership metadata, ETag and version ID before issuing an exact-key DELETE, then issues a HEAD to confirm removal.

Logical names are 1–96 ASCII letters, digits, dots, underscores or hyphens, starting with a letter or digit. The fixture and downloaded object are each limited to 1 MiB. The provider records the actual digest and byte count in the run evidence.

## Verify an application-created export

The optional [application export suite](suite_manifest_app_export.yaml) requires an application with a synchronous export endpoint and access to the same bucket. Set `REST_BASE_URL` to the application's origin and supply the S3 variables above. The endpoint accepts `POST /exports` with a JSON body containing `object_key`, writes the five UTF-8 bytes `hello` (no newline) to that key, and returns HTTP 201 once the object is readable.

```sh
./regress run --suite samples/20-provider-capability-p0/storage/s3/suite_manifest_app_export.yaml --profile external_export
```

In the [test case](test_cases/app_export.yaml), `s3_object_key` derives a destination under `regress/<run-test-target-hash>/exports/` without contacting S3. The HTTP step passes `step://assign_key/object_key` in its request body. The HEAD and SHA-256 checks both set `application_export: true` with the original logical name. The runner independently derives the same key, reads application-created objects with ordinary S3 metadata, and compares the downloaded bytes to a known digest.

The framework's S3 steps need only read access to the export path. The application or bucket owner manages retention of its exports. Fixture PUT and DELETE operate in the separate fixture path and enforce the framework's ownership marker.
