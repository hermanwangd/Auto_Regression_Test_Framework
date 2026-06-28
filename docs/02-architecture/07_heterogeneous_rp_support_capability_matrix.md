# 07. Heterogeneous RP Support and Capability Matrix

Status: M1 Staged Implementation-Ready Draft

## 7.1 Purpose

This document defines how the Release Package Regression Framework supports heterogeneous execution after Product/RP/RU topology has been translated by Agent Skills into framework-readable artifacts. It also records the current framework capability matrix so pilot planning does not confuse implemented support with target support.

## 7.2 Support Principle

The framework supports logical targets, runner/provider contracts, fixtures, verify rules, and evidence. It does not understand Product/RP/RU topology or implementation languages directly.

- DSL test cases describe logical validation intent: targets, setup fixtures, execute operations, expected results, verify rules, cleanup needs, runtime policy, and evidence.
- Product-owned `rp_ru_mapping.yaml` declares which RUs are in the RP. The Agent Skill translates that topology into `suite_manifest.yaml`, `run_plan.yaml`, `environment_binding.yaml`, provider contracts, and `traceability_map.yaml`.
- Provider contracts bind logical DSL operations and verify rules to reusable concrete technology behavior such as REST, gRPC, Kafka, NATS, DB, K8s, VM, or a governed external runner escape hatch.
- Providers normalize execution, verify, cleanup, and evidence results back into the common suite/run evidence model with optional RP/RU trace labels.

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
| Generated execution artifacts | Supported structurally for suite/run/environment/provider inputs; product mapping is currently product-side input | Logical target dependency-aware execution from generated run plans | Add richer Agent Skill translation checks and generated artifact readiness reporting |
| DSL lifecycle | Draft and approved test lifecycle exists; execution-focused DSL v1 parser/generator validation, CLI run/report consumption, and `dsl_runtime` run evidence cover identity, traceability, targets, scenario, setup, execute, expected results, verify, evidence, and runtime | Stable package-neutral DSL v1 consumed by validation, run, evidence, and report | Add parameter cases, observations, and postconditions as those execution paths mature |
| Binding types | Supports `input_file`, `dataset`, `db_seed`, `api_payload`, and `message_event` readiness/resolution | Add `config_file`, `env_var`, and `existing_state` as providers mature | Unsupported bindings fail before execution |
| Fixture lifecycle | Validates cleanup policy and executes JDBC DB fixture setup, verification query counts, cleanup, and bounded messaging cleanup drain where configured | Execute DB seed/query/cleanup and test-owned message drain cleanup where needed | Add stronger fixture safety policy and persistent broker purge only if a pilot RP requires it |
| Execution adapter | Provider runtime registry dispatches file/batch shell, REST, native descriptor-driven gRPC unary request/response, local/mock messaging, native Kafka/NATS publish, NATS request/reply, consume/observe, and cleanup messaging, JDBC DB fixture, local/mock plus native K8s/VM deployment readiness, bounded K8s direct API readiness, bounded K8s pod log capture, bounded VM SSH/WinRM command probes, and approved command-runner escape hatch paths | Provider-registry-dispatched REST/gRPC, Kafka/NATS, DB, deployment readiness, and shell/file providers | Add real pilot environment validation |
| REST | Supported through configurable HTTP request/response provider with payload binding, evidence capture, HTTP/status field checks, JSON path equality and absence assertions, numeric tolerance assertions, schema/contract assertions over captured output, and dry-run blocking for unsupported actions or unresolved payload bindings | Native request/response provider | Add pilot endpoint evidence when owner environment exists |
| gRPC | Native descriptor-driven unary runtime is supported through `request_response/grpc` with `service_ref`, `descriptor_ref`, action `service`/`method`, payload binding, timeout, output ref, CLI preflight blocking, request-response evidence, and reusable response assertion helpers over captured output. | Native request/response provider | Add reflection support, streaming modes, metadata/auth handling, and pilot endpoint evidence if selected RP requires them. |
| Kafka | Native `messaging/kafka` publish, consume/observe, and cleanup runtime is supported through Kafka client dispatch with `bootstrap_servers_ref`, `topic_ref`, mode, payload binding for publish, optional min/expected count for observe, `cleanup_strategy: drain`, positive `max_count`, JSON serialization gate, timeout, correlation, output ref, and evidence. | Publish, consume, observe, correlate, bounded drain cleanup | Add pilot evidence against owner-provided Kafka environment and persistent purge only if required. |
| NATS | Native `messaging/nats` publish, request/reply, consume/observe, and cleanup runtime is supported through NATS core protocol dispatch with `server_ref`, `subject_ref`, mode, payload binding for publish/request, optional min/expected count for observe, `cleanup_strategy: drain`, positive `max_count`, JSON serialization gate, timeout, correlation, output ref, and evidence. | Publish/request/reply/subscribe where pilot needs it plus bounded drain cleanup | Add pilot evidence against owner-provided NATS environment. |
| Orbix | Not implemented | Approved external runner escape hatch first only if needed; native provider later if reusable | Avoid one-off RP-specific provider code |
| K8s readiness | Native `deployment_readiness/k8s` runtime supports bounded `kubectl` rollout/pod readiness probes, direct API deployment availability probes, and bounded pod log capture with kube context or API server ref, namespace, deployment or selector, version ref, timeout, output ref, log tail bound, and evidence. Local/mock `file_exists` readiness remains supported. | Kube context or API server, namespace, deployment, service, pod/log readiness provider | Add real pilot cluster evidence when owner environment exists. |
| VM readiness | Native `deployment_readiness/vm` runtime supports bounded TCP, HTTP service-health, SSH command, and WinRM command probes with host or health URL, command refs where required, optional executable wrapper refs, version ref, timeout, output ref, and evidence. Local/mock readiness remains supported. | SSH/WinRM/service-health readiness provider | Add real pilot VM evidence when owner environment exists. |
| DB fixture | Supported for JDBC seed SQL refs, verification query SQL refs, cleanup SQL refs, cleanup strategy, isolation key validation, setup/cleanup evidence, and DB row count assertions through reviewed query refs | Seed, query, assert, and cleanup with isolation key | Add stronger destructive-operation policy |
| Expected-result / verify | File diff, expected-result artifact path, HTTP/status field checks, JSON path equality and absence checks, numeric tolerance checks, schema/contract checks, multi-check aggregation, and DB row count checks | JSON path, schema, contract, DB row, absence, tolerance | Add invariant or custom comparator providers only when a selected pilot requires them |
| Evidence | Batch/run evidence includes bindings, dependencies, provider contracts, verify status, cleanup, failed/blocked runs, and selected provider-specific evidence | Include resolved bindings, provider contracts used, cleanup, observations, verify details | Add richer observation and postcondition evidence for native messaging and deployment providers |
| External runner bridge | Contract validation, approved `command_runner` dispatch, approval metadata checks, positive bounded timeout checks, unsafe output and evidence-map path blocking, built-in provider alternative blocking, evidence-map recording, and mapped-artifact existence checks exist as an escape hatch | Governed escape hatch for JUnit, Newman, Robot, legacy binaries, or custom harnesses only when built-in providers cannot cover the boundary | Add mapped-artifact content/schema checks before broad use |
| Provider registry | Supported structural capability registry validates explicit family/type, logical target binding, runtime status, and dispatch for selected providers | Config-driven provider registry with defaults plus generated suite/run/environment overrides, explicit family/type validation, and runtime dispatch | Externalize or govern registry configuration as provider set grows |

Status meanings:

- Supported: implemented in this repo and covered by current framework tests.
- Partial: implemented as validation/readiness or sample support, but not full runtime execution.
- Target: required for the selected heterogeneous pilot unless the capability is explicitly marked as an escape hatch.
- Future: useful after M1, only after the provider model proves reusable.

## 7.5 Provider Families

| Provider Family | Purpose | Example Technologies | Minimum Contract |
|---|---|---|---|
| Request/response provider | Invoke synchronous APIs and capture response evidence | REST, gRPC, Orbix bridge | Endpoint or service ref, action, payload binding, auth ref, timeout, response mapping |
| Message provider | Publish, consume, observe, cleanup, and correlate events | Kafka, NATS | Topic/subject ref, publish payload binding, key/correlation id, serialization, timeout, actual output ref, observation rule, cleanup strategy, max cleanup count |
| DB fixture provider | Prepare and validate state | SQL/NoSQL DBs | Connection ref, query refs, setup/cleanup SQL refs, isolation key, cleanup strategy |
| Deployment readiness provider | Block execution until deployed targets are ready | K8s, VM | Environment ref, target selector, readiness probe, version/deployment ref, timeout, actual output ref, log/evidence refs |
| External runner provider | Governed escape hatch for existing tools or legacy runtimes that cannot yet use built-in providers | JUnit, Newman, Robot, C++ or VB.NET harness | Approval ref, command or container ref, inputs, outputs, success codes, timeout, evidence artifact map |
| File/batch provider | Run file, CLI, batch, or data-pipeline style tests | Spring Boot CLI, scripts, scheduled jobs | Command, working directory, inputs, output refs, logs, success codes, bounded timeout |

Provider configuration must reference secrets and environment resources by name or secret ref. It must not inline credentials, production data, or destructive commands without explicit policy approval. External runner contracts must also explain why an existing built-in provider cannot be used.

## 7.6 Contract Boundary Example

This example is a target-state generated framework contract sketch because it includes native Kafka execution in a real environment. Current framework verification supports native Kafka publish, consume/observe, and bounded cleanup drain dispatch and blocks incomplete Kafka contracts with owner-actionable missing-field gaps. Real pilot evidence still requires owner-provided product context, Agent-generated artifacts, and broker environment evidence.

```yaml
environment_id: sit_payment
targets:
  payment_api:
    type: service
    runner: request_response
    provider_contract_ref: provider_contracts.yaml#adapters.request_response
  payment_db:
    type: database
    runner: jdbc
    provider_contract_ref: provider_contracts.yaml#fixtures.payment_db
  payment_events:
    type: event_bus
    runner: kafka
    provider_contract_ref: provider_contracts.yaml#observations.payment_events

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
      timeout_seconds: 30
      outputs:
        actual_output_ref: actual/payment-response.json
  fixtures:
    payment_db:
      provider_family: db_fixture
      provider_type: jdbc
      connection_ref: secret://sit/payment-db
      isolation_key: test_run_id
      cleanup_strategy: by_test_run_id
  observations:
    payment_events:
      provider_family: messaging
      provider_type: kafka
      topic_ref: kafka://payment.events
      correlation_id: ${run.id}
      timeout_seconds: 30
      outputs:
        actual_output_ref: actual/payment-events.json
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

The next implementation slice should not start by coding REST, Kafka, K8s, and VM providers all at once. It should first introduce or harden the provider capability registry, generated artifact validator, and contract validator that support multiple logical targets, explicit provider family/type metadata, capability readiness, unsupported-capability blocking, and standardized evidence output. After that, add provider families in pilot order:

1. Request/response provider for REST or gRPC.
2. DB fixture provider for seed/query/cleanup.
3. Message provider for Kafka or NATS.
4. Deployment readiness provider for K8s and VM.
5. External runner provider only if the selected pilot has an approved escape-hatch need.

This order preserves the common execution model while letting the selected heterogeneous RP prove the framework can support real package variation.
