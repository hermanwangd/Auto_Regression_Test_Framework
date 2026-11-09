# Fixed b13 build-only CI

This candidate packages two binaries from the exact public commit
`b13c503cc9fadd930e11fb8adc6496308697a966`, tree
`a912fc45e68cf1dd31f7e4b16a795338eaec054f`. It does not run product tests,
claim coverage, deploy, import images, or publish a release.

## Isolated trigger and outputs

The new workflow listens only to pushes to `codex/b13-build-only`. It leaves the
existing CI, local-product, release and SIT workflows unchanged. The inspected
existing branch/tag filters do not match this branch; this is not a guarantee
about every repository-level automation. No default-branch change is required.
Publishing or updating the branch may start a workflow run.

The workflow checks out its own commit into `control` and the fixed b13 source
into `source`. Credentials are not persisted. Permissions are `contents: read`;
no secrets, OIDC, production environment, services, user-selected ref or token
setup is requested. The workflow commit and build-source commit are distinct.

Exactly three delivery members are allowed:

- `spec-driven-auto-regression-0.3.2.jar`
- `swarm-product-test-1.0.0-cli.jar`
- `build-manifest.json`

The manifest records source pre/post identity, actual workflow/run identity,
tool archive checksums, resolved dependency sizes/hashes, process outcomes,
resource observations, both JAR hashes and the embedded no-MQ variant.
Its own hash is reported separately, avoiding a self-referential manifest.
No settings, dependency cache, raw logs, earlier evidence or credentials are
included in the delivery artifact. A failed job does not run the upload step.

## Tool and build bindings

`build-contract.json` identifies the immutable checkout/upload action commits.
The driver downloads Eclipse Temurin JDK 17.0.21+9 Linux x64 and Maven 3.9.11
from official sources, verifies their frozen SHA256/SHA512 before extraction,
and checks their reported versions. Maven uses a fresh job-owned repository
and explicit settings mirroring all repositories to official Maven Central.
No existing cache is presumed compatible. Resolved hashes are recorded after
this build; this is not a claim of dependency-level reproducibility.

The root and Module POMs both target Java 17. The serial commands are:

```
mvn -B -ntp -s <isolated-settings> -Dmaven.repo.local=<fresh-m2> \
  -Dmaven.test.skip=true -DskipITs -Djacoco.skip=true -Pno-ibm-mq package
mvn -B -ntp -s <isolated-settings> -Dmaven.repo.local=<fresh-m2> \
  -Dmaven.test.skip=true -DskipITs -Djacoco.skip=true \
  -f integrations/swarm-product-test/pom.xml package
```

The no-IBM-MQ profile excludes IBM's library from the Framework JAR and embeds
`variant=no-ibm-mq` / `ibm_mq=unsupported`. A provided IBM compile dependency may
still be acquired. The driver checks the packaging metadata and rejects a JAR
containing `com.ibm.mq.allclient`. It never executes the JARs.

## Bounded execution

The GitHub job has a 900-second timeout. The driver has an 840-second total
budget including a 30-second cleanup reserve. The remaining job time is for
checkout and artifact upload; it is not an automatic extension if those steps
are slow. Every subprocess shares the same deadline. Failure, timeout,
nonzero exit, stale output, source mismatch or any observed limit violation
stops the job. No automatic retry or cap increase is implemented.

Maven heap is 1024 MiB, metaspace 384 MiB and reserved code cache 128 MiB. The
driver samples aggregate process-group RSS and rejects observations over
2048 MiB. This is not a cgroup hard limit or protection against a hostile
program deliberately escaping its process group. The official, pinned build
tools and reviewed public source are the trust boundary. Normal descendants
are terminated and boundedly reaped; surviving group members fail cleanup.

The two fresh owned roots (source checkout and build work) have a 2048 MiB
logical storage guard and 50,000-entry bound. The fresh dependency cache has
its own 1024 MiB guard. Process output is capped at 1 MiB; delivery at 256 MiB;
the manifest at 1 MiB. Storage and RSS are sampled during commands, so transient
usage between observations is not described as a proven absolute bound.
Download/extraction limits and checksum checks precede tool execution.

This independent build-only resource profile does not revise limits or move
historical data from any existing test execution. Build success permits no
subsequent product test by itself.

## Local control tests and current state

The stdlib test suite runs without Maven, Java, a network or another agent:

```
python3 -B -m unittest discover -s scripts/ci/b13-build -p 'test_*.py' -v
```

It covers successful fake-process completion, nonzero exit, timeout/descendant
cleanup, output and RSS rejection, storage/cache limits, stale outputs, source
mismatch, wrong digest, unsafe archive paths and manifest publication limits.
These are driver controls, not Framework/Module or native-agent behavior tests.

Current local result: 13 controls passed on Linux/Python 3.12.14 in 0.580 s.
No Maven/Java packaging or GitHub workflow has run. No P04/SIT result or formal
coverage credit is claimed. The initial missing-driver baseline and the first
mock-related test failure remain in local development records, not this
publication subset.

The technical candidate and its precise activation change have been reviewed.
The disabled-job and failing-step guards have been removed; the workflow is
ready for authorized publication but remains NOT_RUN. Tool pins and the
resource driver are implemented. Before any push, recheck branch ownership
and current ref and use normal non-force publication. The proposed branch was absent and repository rulesets returned
an empty list at preparation time; neither observation grants extra rights or
predicts the result of a later write.

## Sources

- [Exact build source](https://github.com/hermanwangd/Auto_Regression_Test_Framework/tree/b13c503cc9fadd930e11fb8adc6496308697a966)
- [Pinned checkout action](https://github.com/actions/checkout/tree/11d5960a326750d5838078e36cf38b85af677262)
- [Pinned artifact action](https://github.com/actions/upload-artifact/tree/ea165f8d65b6e75b540449e92b4886f43607fa02)
- [Temurin release](https://github.com/adoptium/temurin17-binaries/releases/tag/jdk-17.0.21%2B9)
- [Official Maven checksum](https://archive.apache.org/dist/maven/maven-3/3.9.11/binaries/apache-maven-3.9.11-bin.tar.gz.sha512)
