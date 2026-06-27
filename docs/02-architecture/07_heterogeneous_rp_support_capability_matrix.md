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
| RP/RU mapping | Validates required RU fields | Multi-RU dependency-aware execution | Resolver and executor currently read primarily the first RU |
| DSL lifecycle | Draft and approved test lifecycle exists | Stable package-neutral DSL v1 | Draft generator is not yet complete for all required DSL sections |
| Binding types | `input_file`, `dataset`, `db_seed` readiness | Add `api_payload`, `message_event`, `config_file`, `env_var`, `existing_state` as providers mature | Unsupported bindings fail before execution |
| Fixture lifecycle | Validates cleanup policy for mutating fixtures | Execute DB seed/query/cleanup and message cleanup where needed | Current fixture service validates only; it does not perform DB or queue operations |
| Execution adapter | Local shell command adapter | Provider-registry-dispatched REST/gRPC, Kafka/NATS, DB, deployment readiness, and shell/file providers | Current execution engine still has hard-coded dispatch paths |
| REST | Reserved by DSL/design only | Native request/response provider | Needs contract schema, executor, assertions, evidence |
| gRPC | Not implemented | Native or generated-client provider | Needs schema/reflection or descriptor contract |
| Kafka | Not implemented | Publish, consume, observe, correlate | Needs topic refs, serialization, timeout, evidence rules |
| NATS | Not implemented | Publish/request/reply/subscribe where pilot needs it | Needs subject refs, correlation, timeout, evidence rules |
| Orbix | Not implemented | Approved external runner escape hatch first only if needed; native provider later if reusable | Avoid one-off RP-specific provider code |
| K8s readiness | SIT readiness fields are checked | Kube context, namespace, deployment, service, pod/log readiness provider | No kube API or command probe yet |
| VM readiness | Environment ref is checked | SSH/WinRM/service-health readiness provider | No VM probe provider yet |
| DB fixture | `db_seed` can be declared and validated | Seed, query, assert, and cleanup with isolation key | Needs DB provider and safety policy |
| Oracle/assertion | File diff and expected-result artifact path | JSON path, schema, contract, DB row, absence, tolerance | Add assertion providers incrementally |
| Evidence | Batch/run evidence exists | Include resolved bindings, provider contracts used, cleanup, observations, assertion details | Current evidence is incomplete for full AC-007 |
| External runner bridge | Not implemented | Governed escape hatch for JUnit, Newman, Robot, legacy binaries, or custom harnesses only when built-in providers cannot cover the boundary | Needs approval metadata, bounded execution policy, runner contract, and evidence mapping before use |
| Provider registry | Structural resolver exists | Config-driven provider registry with default, RP, and RU precedence, explicit family/type validation, and runtime dispatch | Current resolver is minimal, partially heuristic, and not multi-RU capable |

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
