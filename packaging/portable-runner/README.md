# Local image packaging source

This source kit packages an externally accepted framework JAR and wrapper. It does not implement a native gateway or execute product tests by itself. Docker engine access and Python 3.12 on Linux are required. Use a fresh, caller-owned writable copy; generated context/, evidence/, exports/ and accepted external producer inputs must remain untracked.

Supply an accepted-runner-producer.v1 JSON manifest with independently reviewed source, JAR, wrapper, schema and observer pins. The manifest paths must refer to accessible external files; source self-reporting is not acceptance authority. The fixture root is the existing PUBLIC_SYNTHETIC evidence_hardening sample directory containing valid_result.json and evidence/. Its canonical fixtures are static artifact controls, not a new SUT execution. The lock parent must already exist and be caller-owned.

```sh
python3 package_image.py --accepted-producer /external/accepted-producer.json --expected-producer-sha256 EXTERNAL_SHA256 --fixture-root /external/evidence_hardening --lock /owned/heavy.lock
```

The fixed official amd64 base pins are shipped as base-pins.json. The main operator consumes them and refuses an existing context. Resolving new metadata is a separate explicit action: in a fresh owned copy without base-pins.json, run `python3 resolve_bases.py`. It creates evidence/ and refuses existing command records or pins. It does not pull images. Do not delete unrelated evidence to simulate a fresh run.

The fixed local smoke controls use a read-only root filesystem, network none, capabilities dropped, empty proxy environment and a nonroot identity. The image default is UID:GID 65532:65532. Only CD/collector writer controls use the measured nonroot host UID:GID with private caller-owned output directories. This distinction does not establish that default UID65532 can write deployed storage. No registry publication, K3s, VM, SIT, remote execution or company product ownership is claimed.

Failed/guarded log retrieval is a PARTIAL source capture. Available finite bytes and runtime facts can be saved through the existing collector, while the original resource remains retained. A saved finite snapshot does not prove all source logs were captured. Original removal requires complete source capture, verified readback, exact ownership and a stopped workload. Unexpected controller failure reaps its owned CLI child; it does not authorize container removal. Export has one 180-second deadline through independent readback/publication and a live one-MiB stderr cap. Failure after publication attempts to revoke only its own exact hard link; rollback uncertainty is an error, never success.

```sh
python3 -B -m unittest discover -s tests -v
```

Tests use owned temporary fixtures and subprocesses with mocked Docker calls; they do not build or run images. Docker28 compatibility fixtures exercise the existing converter without extracting layers. Generated OCI archives must be independently verified; Docker-save bytes must not be labelled OCI.

The prior local package has 13 mapped frozen controls (12 direct plus one composed retention control), 12 exact literal matches and one accepted diagnostic-name variance. Its 16 recorded container operations are not a 16-case coverage denominator. These source-only repairs preserve that package history and do not claim a new image build or runtime smoke execution. No real product requirement or risk denominator is established by helper test counts.
