# 07. Heterogeneous RP Support and Capability Matrix

Status: M1 Staged Implementation-Ready Draft

## 7.1 Purpose

This document defines how the Release Package Regression Framework supports RPs whose RUs use different implementation languages, deployment environments, and interaction styles. It also records the current framework capability matrix so pilot planning does not confuse implemented support with target support.

## 7.2 Support Principle

The framework supports executable RP/RU boundaries, not implementation languages directly.

- DSL test cases describe logical validation intent: inputs, fixtures, actions, oracles, assertions, cleanup, and evidence.
- `rp_ru_mapping.yaml` declares which RUs are in the RP, their versions, execution modes, dependencies, validation boundaries, and provider contracts.
- Provider contracts bind logical DSL actions to reusable concrete technology behavior such as REST, gRPC, Kafka, NATS, DB, K8s, VM, or a governed external runner escape hatch.
- Providers normalize execution, assertion, cleanup, and evidence results back into the common RP evidence model.

Java, Go, C++, VB.NET, and other languages should normally be invisible to the DSL. Language matters only when a reusable provider cannot express a legacy or specialized boundary and an approved escape-hatch contract is used.

## 7.3 M1 Heterogeneous Pilot Shape

The selected M1 pilot should include one RP with RUs that collectively exercise:

- Request/response interaction: REST and/or gRPC.
- Asynchronous messaging: Kafka and/or NATS.
- Stateful regression data: DB fixture setup, query, and cleanup.
- Deployment readiness: K8s and VM readiness checks before execution.
- Provider capability registry validation across all selected provider contracts.

This pilot is the target adoption proof. The current framework verification fixture remains a local sample Product Repo fixture and must not be presented as downstream RP release evidence.

External runner is not required for the M1 pilot unless the selected RP contains a legacy or specialized boundary that cannot be represented by a reusable built-in provider. In that case it is an approved escape hatch, not the normal extension path.

## 7.4 Current Capability Matrix

| Capability Area | Current Repo Support | M1 Pilot Target | Gap / Next Work |
|---|---|---|---|
| Product Repo readiness | Supported for folder/readiness checks | Keep | Harden reports and owner actions |
| RP artifact completeness | Supported structurally | Keep | Add richer cross-artifact readiness |
| RP/RU mapping | Supported for required RU fields, dependency graph ordering, target-RU provider resolution, and downstream blocking after failed upstream validation | Multi-RU dependency-aware execution | Add richer cross-artifact dependency readiness reporting |
| DSL lifecycle | Draft and approved test lifecycle exists; executable drafts include identity/traceability, scenario, execution target, logical inputs, oracle/assertion placeholder, evidence requirements, and cleanup policy | Stable package-neutral DSL v1 | Add richer draft support for parameter cases, observations, and postconditions as those execution paths mature |
| Binding types | Supports `input_file`, `dataset`, `db_seed`, `api_payload`, and `message_event` readiness/resolution | Add `config_file`, `env_var`, and `existing_state` as providers mature | Unsupported bindings fail before execution |
| Fixture lifecycle | Validates cleanup policy and executes JDBC DB fixture setup, verification query counts, and cleanup | Execute DB seed/query/cleanup and message cleanup where needed | Add message cleanup and stronger fixture safety policy |
| Execution adapter | Provider runtime registry dispatches file/batch shell, REST, local/mock messaging, JDBC DB fixture, local/mock deployment readiness, and approved command-runner escape hatch paths | Provider-registry-dispatched REST/gRPC, Kafka/NATS, DB, deployment readiness, and shell/file providers | Add native gRPC, Kafka/NATS, K8s, and VM providers as reusable runtimes |
| REST | Supported through configurable HTTP request/response provider with payload binding, evidence capture, and dry-run blocking for unsupported actions or unresolved payload bindings | Native request/response provider | Add richer response assertions such as status, JSON path, schema, and contract checks |
| gRPC | Not implemented | Native or generated-client provider | Needs schema/reflection or descriptor contract |
| Kafka | Contract validation exists; local/mock messaging runtime can exercise payload evidence and dry-run blocking for unsupported actions, unresolved payload bindings, or unsupported serialization | Publish, consume, observe, correlate | Needs native Kafka client provider, topic refs, serialization, timeout, correlation, and evidence rules |
| NATS | Contract validation exists; local/mock messaging runtime can exercise payload evidence and dry-run blocking for unsupported actions, unresolved payload bindings, or unsupported serialization | Publish/request/reply/subscribe where pilot needs it | Needs native NATS client provider, subject refs, correlation, timeout, and evidence rules |
| Orbix | Not implemented | Approved external runner escape hatch first only if needed; native provider later if reusable | Avoid one-off RP-specific provider code |
| K8s readiness | Partial local/mock deployment readiness provider supports `file_exists` readiness evidence; SIT readiness fields are checked | Kube context, namespace, deployment, service, pod/log readiness provider | Add kube API or bounded command probe provider |
| VM readiness | Partial local/mock deployment readiness provider supports readiness evidence shape; environment refs are checked | SSH/WinRM/service-health readiness provider | Add SSH/WinRM or bounded service-health probe provider |
| DB fixture | Supported for JDBC seed SQL, verification query counts, and cleanup SQL through provider contracts | Seed, query, assert, and cleanup with isolation key | Add stricter safety policy and DB-row assertion/oracle types |
| Oracle/assertion | File diff and expected-result artifact path | JSON path, schema, contract, DB row, absence, tolerance | Add assertion providers incrementally |
| Evidence | Batch/run evidence includes bindings, dependencies, provider contracts, assertion status, cleanup, failed/blocked runs, and selected provider-specific evidence | Include resolved bindings, provider contracts used, cleanup, observations, assertion details | Add richer observation and postcondition evidence for native messaging and deployment providers |
| External runner bridge | Contract validation, approved `command_runner` dispatch, approval metadata checks, positive bounded timeout checks, unsafe output and evidence-map path blocking, built-in provider alternative blocking, evidence-map recording, and mapped-artifact existence checks exist as an escape hatch | Governed escape hatch for JUnit, Newman, Robot, legacy binaries, or custom harnesses only when built-in providers cannot cover the boundary | Add mapped-artifact content/schema checks before broad use |
| Provider registry | Supported structural capability registry validates explicit family/type, target-RU ownership, runtime status, and dispatch for selected providers | Config-driven provider registry with default, RP, and RU precedence, explicit family/type validation, and runtime dispatch | Externalize or govern registry configuration as provider set grows |

Status meanings:

- Supported: implemented in this repo and covered by current framework tests.
- Partial: implemented as validation/readiness or sample support, but not full runtime execution.
- Target: required for the selected heterogeneous pilot unless the capability is explicitly marked as an escape hatch.
- Future: useful after M1, only after the provider model proves reusable.

## 7.5 Provider Families

| Provider Family | Purpose | Example Technologies | Minimum Contract |
|---|---|---|---|
| Request/response provider | Invoke synchronous APIs and capture response evidence | REST, gRPC, Orbix bridge | Endpoint or service ref, action, payload binding, auth ref, timeout, response mapping |
| Message provider | Publish, consume, observe, and correlate events | Kafka, NATS | Topic/subject ref, payload binding, key/correlation id, serialization, timeout, observation rule |
| DB fixture provider | Prepare and validate state | SQL/NoSQL DBs | Connection ref, schema/table or query refs, seed input, isolation key, cleanup strategy |
| Deployment readiness provider | Block execution until deployed targets are ready | K8s, VM | Environment ref, target selector, readiness probe, version/deployment ref, log/evidence refs |
| External runner provider | Governed escape hatch for existing tools or legacy runtimes that cannot yet use built-in providers | JUnit, Newman, Robot, C++ or VB.NET harness | Approval ref, command or container ref, inputs, outputs, success codes, timeout, evidence artifact map |
| File/batch provider | Run file, CLI, batch, or data-pipeline style tests | Spring Boot CLI, scripts, scheduled jobs | Command, working directory, inputs, output refs, logs, success codes |

Provider configuration must reference secrets and environment resources by name or secret ref. It must not inline credentials, production data, or destructive commands without explicit policy approval. External runner contracts must also explain why an existing built-in provider cannot be used.

## 7.6 Contract Boundary Example

```yaml
release_units:
  - ru_id: RU-payment-api
    repo: git://example/payment-api
    unit_type: service
    version_ref: build-123
    validation_boundary: request_response_api
    execution_mode: sit_deployed
    deployment_required: true
    environment_ref: sit://payment
    adapter: request_response
    provider_contracts:
      adapters:
        request_response:
          provider_family: request_response
          provider_type: rest
          base_url_ref: env://PAYMENT_API_BASE_URL
          actions:
            submit_payment:
              method: POST
              path: /payments
              request_binding: payment_payload
              response_mapping:
                payment_id: $.paymentId
      fixtures:
        payment_db:
          provider_family: db_fixture
          provider_type: relational_db
          connection_ref: secret://sit/payment-db
          cleanup_strategy: by_test_run_id
      observations:
        payment_events:
          provider_family: messaging
          provider_type: kafka
          topic_ref: kafka://payment.events
          correlation_id: ${run.id}
```

The DSL would reference logical names such as `submit_payment`, `payment_payload`, and `payment_events`. It would not contain URLs, credentials, SQL bodies, topic client settings, or K8s commands.

## 7.7 Extension Rules

- Configure an existing built-in provider before adding new provider code.
- Add provider code only when the behavior is reusable across RPs and cannot be safely represented by existing contracts.
- Add DSL enum values only for recurring cross-RP concepts, not for product-specific details.
- Add or change required DSL fields only through a `dsl_version` compatibility decision.
- External runner bridge is acceptable only for approved legacy or specialized systems when standard evidence can still be produced and no reusable built-in provider can safely represent the boundary.
- Every new provider family must include contract validation, failure semantics, evidence mapping, sample fixture coverage, and AC traceability.

## 7.8 Implementation Implications

The next implementation slice should not start by coding REST, Kafka, K8s, and VM providers all at once. It should first introduce a provider capability registry and contract validator that supports multiple RU entries, explicit provider family/type metadata, capability readiness, unsupported-capability blocking, and standardized evidence output. After that, add provider families in pilot order:

1. Request/response provider for REST or gRPC.
2. DB fixture provider for seed/query/cleanup.
3. Message provider for Kafka or NATS.
4. Deployment readiness provider for K8s and VM.
5. External runner provider only if the selected pilot has an approved escape-hatch need.

This order preserves the common execution model while letting the selected heterogeneous RP prove the framework can support real package variation.
