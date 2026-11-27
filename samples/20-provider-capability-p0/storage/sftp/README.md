# SFTP order reconciliation

This native v0.3 suite publishes `fixtures/orders.csv` and waits up to five seconds for the batch application to produce exactly one return file, `reconciliation.csv`. The two expected UTF-8 reconciliation rows and their SHA-256 are fixed in the test. Counts include every physical line, including any header. The sample return has no header.

| Runner environment variable | Value |
| --- | --- |
| `REGRESS_SFTP_ENDPOINT` | SFTP origin such as `sftp://127.0.0.1:2222` |
| `REGRESS_SFTP_USERNAME` | Account permitted to create and remove its batch directories |
| `REGRESS_SFTP_PRIVATE_KEY` | Contents of the runner's unencrypted SSH private key, supplied by the secret store |
| `REGRESS_SFTP_KNOWN_HOSTS` | Independently trusted OpenSSH known_hosts entry for the destination, including `[host]:port` when using a nondefault port |
| `REGRESS_SFTP_ROOT` | Existing canonical absolute directory on the server, reserved for the runner and batch application |

The runner creates `batch-<run/test/target digest>/incoming` and `returns` under that root. It publishes the input with a rename to `incoming/orders.csv`. The application reads that file and atomically publishes the return in `returns/`. The runner downloads the return over SFTP, verifies its filename, file count, record count and raw-byte digest, then removes its own batch on success or failure. A local ownership receipt prevents cleanup from adopting a preexisting remote batch. Keep the batch root under the control of the fixture account and its application.

```sh
./regress run --suite samples/20-provider-capability-p0/storage/sftp/suite_manifest.yaml --profile external_sftp
./regress validate-evidence --result <result.json>
```

Files are limited to 1 MiB and each batch subdirectory to 32 entries. Suite-supplied filenames must start with an ASCII letter or digit and may additionally contain dots, underscores and hyphens. Cleanup handles flat files in the two assigned subdirectories. Evidence includes the observed SHA-256, byte and record counts, batch identity and cleanup outcome.

The CI script `scripts/ci/verify-native-sftp.sh` runs the packaged suite against loopback OpenSSH and an independent order-processing fixture. It checks the success case, changes only the expected return checksum for a failing run, and inspects the server directory after each run.
