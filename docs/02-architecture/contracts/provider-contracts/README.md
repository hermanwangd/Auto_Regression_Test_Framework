# Provider Contract Catalog

This directory is the authoritative built-in Provider Contract catalog for
Framework v0.3. Suite authors normally reference these contracts by id from
`suite_manifest.yaml`; they should not copy built-in contracts into suite repos.

## How To Find A Contract

1. Start with `docs/09-operations/provider_support_matrix.md` to confirm support
   status and the release-verifiable sample path.
2. In the suite manifest, read `targets.<target>.provider_contract`.
3. Map the contract id to the YAML file in this directory:
   `jdbc.v0.3` maps to `jdbc_v0_3.yaml`, and `rest_client.v0.3` maps to
   `rest_client_v0_3.yaml`.
4. Read the contract sections used by the DSL and Env_Profile:
   `binding_keys`, `operations.<op>.allowed_inputs`,
   `operations.<op>.required_inputs`, `operations.<op>.output_refs`, typed
   `inputs` / `outputs` when present,
   `evidence`, and `failure_mapping`.

v0.2 compatibility contracts remain in this directory with legacy filenames
such as `wiremock_http_mock.yaml`. New v0.3 suites should use the v0.3 contract
ids and YAML files listed below.

## v0.3 Built-In Contracts

| Provider Contract | YAML | Main Sample | Support Status |
| --- | --- | --- | --- |
| `artifact_compare.v0.3` | `artifact_compare_v0_3.yaml` | `samples/20-provider-capability-p0/verification/artifact_compare/` | `supported` |
| `common_verify.v0.3` | `common_verify_v0_3.yaml` | `samples/20-provider-capability-p0/verification/common_verify/` | `supported` |
| `grpc_client.v0.3` | `grpc_client_v0_3.yaml` | `samples/20-provider-capability-p0/rpc/grpc_mock/` | `supported` |
| `grpc_mock.v0.3` | `grpc_mock_v0_3.yaml` | `samples/20-provider-capability-p0/rpc/grpc_mock/` | `supported` |
| `http_mock.v0.3` | `http_mock_v0_3.yaml` | `samples/20-provider-capability-p0/http/rest_client_with_wiremock/` | `supported` |
| `ibm_mq.v0.3` | `ibm_mq_v0_3.yaml` | `samples/20-provider-capability-p0/messaging/ibm_mq/` | `supported` |
| `jdbc.v0.3` | `jdbc_v0_3.yaml` | `samples/20-provider-capability-p0/data/jdbc/` | `supported` |
| `kafka.v0.3` | `kafka_v0_3.yaml` | `samples/20-provider-capability-p0/messaging/kafka/` | `supported` |
| `nats.v0.3` | `nats_v0_3.yaml` | `samples/20-provider-capability-p0/messaging/nats/` | `supported` |
| `polling_observer.v0.3` | `polling_observer_v0_3.yaml` | `samples/20-provider-capability-p0/verification/polling_observer/` | `supported` |
| `rest_client.v0.3` | `rest_client_v0_3.yaml` | `samples/20-provider-capability-p0/http/rest_client_with_wiremock/` | `supported` |
| `sample_fake_provider.v0.3` | `sample_fake_provider_v0_3.yaml` | `samples/00-getting-started/golden_e2e/` | `unsupported` |
| `soap_mock.v0.3` | `soap_mock_v0_3.yaml` | `samples/20-provider-capability-p0/rpc/soap_mock/` | `supported` |

## What The Contract Controls

The Provider Contract is the runtime public interface. It controls:

- which `op` values a test case may call;
- which `with` keys each operation may use;
- which `binding_keys` an Env_Profile must supply;
- which `runtime_mode` values are allowed;
- which operation output refs and generated refs are valid;
- the output type, sensitivity, bindability, evidence behavior, runtime modes,
  and permitted lifecycle phases when the contract defines them;
- which evidence types and failure codes the provider may emit.

If a test case uses an unsupported `op`, unsupported `with` key, invalid
generated ref, missing binding key, or a `step://` output from another test
case, validation must fail before provider runtime dispatch. Provider runtime
outputs must also be declared by the executed operation; undeclared outputs
are rejected before persistence.

## Custom Or Snapshot Contracts

Use suite-local contracts only for approved custom providers or explicit
contract snapshot pinning. Declare that intent with `provider_contract_resolution`
in the suite manifest and keep custom files under the configured
`custom_provider_contracts/` directory.

Future CLI discoverability may add commands such as `regress providers` and
`regress describe-provider --provider-contract jdbc.v0.3`; those commands are
not part of the current v0.3 release contract.
# v0.3 Typed Contract Rule

For `contract_version: v0.3`, each operation's `inputs` and `outputs` maps are the authoritative public interface. `required`, `value_type`, `reference_kinds`, `sensitivity`, `bindable`, `evidence_included`, `phases`, and `runtime_modes` are validated before runtime invocation. Compatibility `allowed_inputs`, `required_inputs`, and `output_refs` are generated views for the v0.2 execution path; the v0.3 compiler does not use them as its source of truth.
