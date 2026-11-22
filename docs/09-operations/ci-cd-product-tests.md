# Local tests in CI and SIT tests after deployment

**Azure DevOps Services is the primary pipeline target.** Use the [Azure CI/CD setup guide](azure-devops-pipelines.md), [root CI YAML](../../azure-pipelines.yml) and [post-deployment stage template](../../pipelines/azure/sit-postdeploy.yml). The GitHub workflow wiring below remains a secondary public verification example. The shared gate contract and limitations apply to both integrations.

## Secondary GitHub CI example

[`local-product-tests.yml`](../../.github/workflows/local-product-tests.yml) verifies the framework and the separate Swarm module, then invokes the committed public SQLite C and NATS native harnesses. It runs on pull requests to main/release branches, pushes to main or `codex/sit-readiness`, and manual dispatch. Jobs remain serial with bounded JVM memory. Existing CI/security/release workflows retain their behavior.

The public cases use the existing packaged module, providers, assertions, canonical evidence validation and report commands. The harnesses own disposable application lifecycle and deterministic fixture generation. Their reviewed committed `local-ci-policy` pins the harness, official asset lock, fixtures, contracts and independent expected values. It permits only declared runtime bindings and current build digests. Its actor is explicitly `local-ci-policy`; CI does not invent a fresh human review. Native public-product observations remain `framework_verification_only`, release-ineligible and local. See [SQLite reproduction](../../integrations/swarm-product-test/public-projects/sqlite/README.md) and [the module reproduction guide](product-testing-reproduction.md).

## Shared protected CD entrypoint and GitHub example

[`sit-postdeploy.yml`](../../.github/workflows/sit-postdeploy.yml) is a reusable, same-repository workflow. A deployment owner calls it **after** its own protected deployment and independently reviewed handoff. It has no push trigger, provisioning, seeding, deployment, cloud access configuration or `local-ci-policy` authority. The existing Release workflow publishes artifacts; it is not a deployment pipeline and does not automatically call this gate.

```yaml
# In an owner-maintained deployment workflow in this repository:
jobs:
  # deploy_sit supplies the approved bundle artifact and independently protected pin.
  sit_tests:
    needs: deploy_sit
    uses: ./.github/workflows/sit-postdeploy.yml
    with:
      deployment_artifact: approved-sit-bundle
      handoff_sha256: ${{ needs.deploy_sit.outputs.independently_approved_handoff_sha256 }}
```

The caller must obtain that digest from protected approval configuration, not rehash the downloaded handoff and call the result approval. Deployment timestamps and observed revisions must come from its governed deployment evidence. Hashes bind bytes; they do not authenticate a deployment or establish that an oracle is correct. The caller remains responsible for environment ownership, reservation, permissions and deployment provenance.

[`run-sit-postdeploy.py`](../../scripts/cd/run-sit-postdeploy.py) checks the handoff and complete catalog before launching any child. It copies verified bytes to a fresh private directory, places the pinned REST contract at the framework's explicit invocation-CWD contract location, and calls the existing framework using fixed argument arrays:

1. `validate`: compile the selected snapshot and compare the plan digest with the protected expected digest.
2. `run`: the existing framework invokes the approved readiness SPI before any provider phase.
3. `validate-evidence` and `report`: the existing framework validates the canonical result and evidence.

Only a passed canonical verdict with exact identity, plan and fresh non-simulated READY observations passes this CD hook. Missing handoff, profile, input pin, readiness configuration or plugin returns nonzero `BLOCKED` before any child launch. Missing/unavailable readiness at runtime returns nonzero; the framework must report blocked and zero provider, step and cleanup operations. Assertion failures remain non-success with their canonical framework verdict retained in `gate-result.json`. An exit code alone cannot pass the gate.

## Deliberately finite initial contract

The input bundle contains `deployment.json`, `artifacts/framework.jar`, at least one independently approved real readiness plugin JAR, and an exact `suite/` file catalog. Use `suite/suite_manifest.yaml`, `suite/env_profiles/<profile>.yaml`, `suite/test_cases/*.yaml` and `suite/contracts/rest_client_v0_3.yaml`; conventional `.yaml` profile names are required by existing framework discovery. All authoring documents contain strict JSON, which is also valid YAML for the framework. Duplicate keys and non-finite JSON values are rejected. Artifact paths are relative, regular, contained and without symlinks. Text files are limited to 4 MiB; all pinned input bytes together are limited to 96 MiB.

`deployment.json` has these exact fields:

| Field | Required content |
| --- | --- |
| `contract_version`, `environment_stage` | `cd-sit-postdeploy.v1`, `sit` |
| `deployment` | `ref`, `expected_revision`, `observed_revision`, `completed_at`; revisions must match exactly and be immutable owner-supplied identities |
| `suite` | `manifest`, `profile`, `profile_ref`, `suite_id`, `deployment_check_id`, `provider_contract_ref` |
| `checks` | Exact `id`, `kind`, `adapter`, `adapter_version`, `expected_version` for every readiness check |
| `framework` | `{ "path": "artifacts/framework.jar", "sha256": "<independent SHA-256>" }` |
| `readiness_plugins` | 1..8 similarly pinned JAR objects under `artifacts/` |
| `files` | Exact `{ "path": "suite/...", "sha256": "..." }` entries covering every consumed manifest/profile/test/contract |
| `expected_plan_digest` | Independently reviewed digest printed by the existing framework `validate` command against these exact inputs and contract |
| `max_age_seconds` | Integer 1..3600, bounding deployment/readiness freshness |

The leaf manifest has only `manifest_version`, `suite_id`, `default_profile`, `targets`, `env_profiles`, `tests`; it selects exactly the pinned profile. The profile declares `execution_mode: sit_deployed`, native REST targets with literal HTTP(S) origins, and required `sit_readiness`. Every check must match the protected identities/versions. The `deployment_check_id` names a dependency check whose expected version equals the deployed revision. Adapter options are literal non-secret identifiers. The real plugin must verify current state and exact versions as described in [SIT readiness](sit-readiness.md).

This first hook accepts 1..32 tests, each with exactly one literal GET/HEAD, an optional existing ISO duration timeout `PT0.001S`..`PT10S`, and inline `equals` assertions on that operation's response status/body. Body assertions compare exact literal strings. `json_match` requires an artifact oracle in the existing framework and is outside this finite subset. The hook excludes setup/cleanup, headers/bodies, external oracle references, dynamic/env/generated/local bindings, mocks, fake providers and `product_test`. All selected provider-contract bytes are catalogued. The private CWD contract directory prevents ambient ancestor/CWD contract selection; protected compiled-plan equality binds the contract actually selected by the framework. There is no alternate test runner or assertion implementation in this script.

The total child-command deadline is 300 seconds, JVM heap 512 MiB, metaspace 256 MiB and combined command output 1 MiB. Children receive a clean locale-only environment, closed stdin and no inherited secrets. Authentication requiring forwarded credentials is outside this initial hook. Timeout/output overflow terminates the owned process group and fails closed. In-process plugins remain trusted code with cooperative cancellation, as in the framework's existing SPI. Output directories must be fresh. The workflow uploads the gate metadata and the explicit canonical `runtime/target/regression/` subtree; it does not upload the input bundle, JAR snapshot or raw process logs.

## Current validation boundary

The committed tests use synthetic contract documents and mocked framework command order. Missing-configuration CLI execution and framework validation can verify the gate's wiring without executing providers. They do not prove live SIT health. No real readiness adapter, shared SIT reservation, protected deployment handoff or live CD run is supplied by this change. Selecting the bundled fixture cannot establish SIT readiness. The Swarm module still rejects SIT tuples; this hook composes the existing framework directly and does not add module SIT support.

The [existing ISTQB gTAA / CT-GenAI mapping](product-testing-module.md) remains applicable: reviewed DSL and independent oracles supply test definition, existing providers adapt to the SUT, and framework execution/assertions/reporting produce the verdict. CI/CD orchestrates those interfaces. Agents can analyze the linked facts and missing evidence; neither AI interpretation nor a policy-generated handoff replaces oracle review, canonical validation or SIT observation.
