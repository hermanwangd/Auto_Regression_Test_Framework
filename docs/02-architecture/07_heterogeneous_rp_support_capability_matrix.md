# 07. Heterogeneous RP Support and Capability Matrix

Status: v0.2 Full Pre-release Scope Draft

## 7.1 Purpose

This document defines how the Release Package Regression Framework supports heterogeneous execution after Product/RP/RU topology has been translated by Agent Skills into framework-readable artifacts. It also records the current framework capability matrix so pilot planning does not confuse implemented support with target support.

## 7.2 Support Principle

The framework supports logical targets, Provider Contracts, Provider Instances, Environment Bindings, Execution Profiles, fixtures, verify rules, and evidence. It does not understand Product/RP/RU topology or implementation languages directly.

- DSL test cases describe logical validation intent: targets, setup fixtures, execute operations, expected results, verify rules, cleanup needs, runtime policy, and evidence.
- Product-owned `rp_ru_mapping.yaml` declares which RUs are in the RP. The Agent Skill translates that topology into `suite_manifest.yaml`, `run_plan.yaml`, Execution Profiles, Provider Instances, Environment Bindings, Provider Contracts, and `traceability_map.yaml`.
- Provider Contracts define allowed operations, allowed `bind_as` values, binding keys, output refs, evidence outputs, and failure codes for reusable concrete technology behavior such as REST, gRPC, Kafka, NATS, DB, K8s, VM, or a governed external runner escape hatch.
- Providers normalize execution, verify, cleanup, and evidence results back into the common suite/run evidence model with optional RP/RU trace labels.

Java, Go, C++, VB.NET, and other languages should normally be invisible to the DSL. Language matters only when a reusable provider cannot express a legacy or specialized boundary and an approved escape-hatch contract is used.

## 7.3 v0.2 Heterogeneous Pilot Shape

The selected v0.2 pilot should include one RP with RUs that collectively exercise:

- Request/response interaction: REST and/or gRPC.
- Asynchronous messaging: Kafka and/or NATS.
- Stateful regression data: DB fixture setup, query, and cleanup.
- Deployment readiness: K8s and VM readiness checks before execution.
- Provider capability registry validation across all selected Provider Contracts and Provider Instances.

This pilot is the target adoption proof. The current framework verification fixture remains a local sample Product Repo fixture and must not be presented as downstream RP release evidence.

External runner is not required for the v0.2 pilot unless the selected RP contains a legacy or specialized boundary that cannot be represented by a reusable built-in provider. In that case it is an approved escape hatch, not the normal extension path.

## 7.4 Contract Capability Matrix

This matrix records target contract coverage and partial repository evidence. It is not v0.2 delivery acceptance and must not be read as provider runtime completion. Track A only requires contract definitions, sample artifacts, validation rules, and dry-run planning boundaries.

| Capability Area | Current Contract Evidence | v0.2 Pre-release Target | Gap / Next Work |
|---|---|---|---|
| Product Repo readiness | Support-command contract exists for folder/readiness checks | Keep | Harden reports and owner actions in Phase 2 |
| RP artifact completeness | Structural support-command contract exists | Keep | Add richer cross-artifact readiness in Phase 2 |
| Generated execution artifacts | Contract exists for suite/run/Provider Instance/Environment Binding/Execution Profile/Provider Contract inputs; product mapping is product-side input | Logical target dependency-aware execution from generated run plans | Add richer Agent Skill translation checks and generated artifact readiness reporting |
| DSL lifecycle | Draft and approved test lifecycle exists; execution-focused DSL validation, CLI run/report consumption, and `dsl_runtime` run evidence cover identity, source refs, optional labels, targets, scenario, setup, execute, expected results, verify, evidence, and runtime | Package-neutral DSL v0.2 consumed by validation, run, result, evidence, and report | Complete v0.2 parameter, suite/profile, result schema, and secret guardrail verification |
| Binding types | Supports `input_file`, `dataset`, `db_seed`, `api_payload`, and `message_event` readiness/resolution | Add `config_file`, `env_var`, and `existing_state` as providers mature | Unsupported bindings fail before execution |
| Fixture lifecycle | Validates cleanup policy and executes JDBC DB fixture setup, verification query counts, cleanup, and bounded messaging cleanup drain where configured | Execute DB seed/query/cleanup and test-owned message drain cleanup where needed | Add stronger fixture safety policy and persistent broker purge only if a pilot RP requires it |
| Provider runtime | Provider runtime registry dispatches file/batch shell, REST, native descriptor-driven gRPC unary request/response, local/mock messaging, native Kafka/NATS publish, NATS request/reply, consume/observe, and cleanup messaging, JDBC DB fixture, local/mock plus native K8s/VM deployment readiness, bounded K8s direct API readiness, bounded K8s pod log capture, bounded VM SSH/WinRM command probes, and approved external runner escape-hatch paths | Provider-registry-dispatched REST/gRPC, Kafka/NATS, DB, deployment readiness, and shell/file providers | Add real pilot environment validation |
| REST | Contract target plus partial framework evidence for configurable HTTP request/response behavior | Native request/response provider | Add pilot endpoint evidence when owner environment exists |
| gRPC | Native descriptor-driven unary runtime is supported through `request_response/grpc` with `service_ref`, `descriptor_ref`, action `service`/`method`, payload binding, timeout, output ref, CLI preflight blocking, request-response evidence, and reusable response assertion helpers over captured output. | Native request/response provider | Add reflection support, streaming modes, metadata/auth handling, and pilot endpoint evidence if selected RP requires them. |
| Kafka | Native `messaging/kafka` publish, consume/observe, and cleanup runtime is supported through Kafka client dispatch with `bootstrap_servers_ref`, `topic_ref`, mode, payload binding for publish, optional min/expected count for observe, `cleanup_strategy: drain`, positive `max_count`, JSON serialization gate, timeout, correlation, output ref, and evidence. | Publish, consume, observe, correlate, bounded drain cleanup | Add pilot evidence against owner-provided Kafka environment and persistent purge only if required. |
| NATS | Native `messaging/nats` publish, request/reply, consume/observe, and cleanup runtime is supported through NATS core protocol dispatch with `server_ref`, `subject_ref`, mode, payload binding for publish/request, optional min/expected count for observe, `cleanup_strategy: drain`, positive `max_count`, JSON serialization gate, timeout, correlation, output ref, and evidence. | Publish/request/reply/subscribe where pilot needs it plus bounded drain cleanup | Add pilot evidence against owner-provided NATS environment. |
| Orbix | Not implemented | Approved external runner escape hatch first only if needed; native provider later if reusable | Avoid one-off RP-specific provider code |
| K8s readiness | Native `deployment_readiness/k8s` runtime supports bounded `kubectl` rollout/pod readiness probes, direct API deployment availability probes, and bounded pod log capture with kube context or API server ref, namespace, deployment or selector, version ref, timeout, output ref, log tail bound, and evidence. Local/mock `file_exists` readiness remains supported. | Kube context or API server, namespace, deployment, service, pod/log readiness provider | Add real pilot cluster evidence when owner environment exists. |
| VM readiness | Native `deployment_readiness/vm` runtime supports bounded TCP, HTTP service-health, SSH command, and WinRM command probes with host or health URL, command refs where required, optional executable wrapper refs, version ref, timeout, output ref, and evidence. Local/mock readiness remains supported. | SSH/WinRM/service-health readiness provider | Add real pilot VM evidence when owner environment exists. |
| DB fixture | Contract target plus partial framework evidence for JDBC seed/query/cleanup boundaries | Seed, query, assert, and cleanup with isolation key | Add stronger destructive-operation policy |
| Expected-result / verify | File diff, expected-result artifact path, HTTP/status field checks, JSON path equality and absence checks, numeric tolerance checks, schema/contract checks, multi-check aggregation, and DB row count checks | JSON path, schema, contract, DB row, absence, tolerance | Add invariant or custom comparator providers only when a selected pilot requires them |
| Evidence | Batch/run evidence includes bindings, dependencies, provider contracts, verify status, cleanup, failed/blocked runs, and selected provider-specific evidence | Include resolved bindings, provider contracts used, cleanup, observations, verify details | Add richer observation and postcondition evidence for native messaging and deployment providers |
| External runner bridge | Contract validation, approved `command_runner` dispatch, approval metadata checks, positive bounded timeout checks, unsafe output and evidence-map path blocking, built-in provider alternative blocking, evidence-map recording, and mapped-artifact existence checks exist as an escape hatch | Governed escape hatch for JUnit, Newman, Robot, legacy binaries, or custom harnesses only when built-in providers cannot cover the boundary | Add mapped-artifact content/schema checks before broad use |
| Provider registry | Supported structural capability registry validates explicit provider_type, Provider Instance shape, logical target binding, runtime status, and dispatch for selected providers | Config-driven provider registry with framework defaults, generated Provider Contracts, Provider Instances, Environment Binding target values, selected Execution Profile constraints, explicit provider_type validation, and runtime dispatch | Externalize or govern registry configuration as provider set grows |

Status meanings:

- Contract target: required public-interface behavior that may still need implementation and verification.
- Partial: implemented as validation/readiness or sample support, but not full runtime execution.
- Target: required for the selected heterogeneous pilot unless the capability is explicitly marked as an escape hatch.
- Future: useful after v0.2, only after the provider model proves reusable.

## 7.5 Provider Types

| Provider Type | Purpose | Example Technologies | Minimum Contract |
|---|---|---|---|
| `rest_client` / `grpc_client` | Invoke synchronous APIs and capture response evidence | REST, gRPC, Orbix bridge through external runner if needed | Service binding key, operation, allowed request `bind_as`, auth secret ref, timeout, output refs |
| `kafka_messaging` / `nats_messaging` | Publish, consume, observe, cleanup, and correlate events | Kafka, NATS | Topic/subject binding key, payload `bind_as`, key/correlation id, serialization, timeout, output refs, observation rule, cleanup strategy, max cleanup count |
| `jdbc_database` | Prepare and validate state | SQL/NoSQL DBs through JDBC where supported | Connection binding key, query refs, setup/cleanup SQL refs, isolation key, cleanup strategy |
| `kubernetes_runtime` / `vm_runtime` | Block execution until deployed targets are ready | K8s, VM | Namespace/host binding key, target selector, readiness probe, version/deployment ref, timeout, output refs, log/evidence refs |
| `external_runner` | Governed escape hatch for existing tools or legacy runtimes that cannot yet use built-in providers | JUnit, Newman, Robot, C++ or VB.NET harness | Approval ref, command or container ref, inputs, outputs, success codes, timeout, evidence artifact map |
| `shell_command` | Run file, CLI, batch, or data-pipeline style tests | Spring Boot CLI, scripts, scheduled jobs | Command binding key, inputs, output refs, logs, success codes, bounded timeout |

Provider configuration must reference secrets and environment resources by name or secret ref. It must not inline credentials, production data, or destructive commands without explicit policy approval. `external_runner` Provider Contracts must also explain why an existing built-in provider cannot be used.

## 7.6 Contract Boundary Example

This example is a target-state generated framework contract sketch because it includes native Kafka execution in a real environment. Current framework verification supports native Kafka publish, consume/observe, and bounded cleanup drain dispatch and blocks incomplete Kafka contracts with owner-actionable missing-field gaps. Real pilot evidence still requires owner-provided product context, Agent-generated artifacts, and broker environment evidence.

```yaml
targets:
  payment_api:
    provider_id: payment-api
    profile: sit
  payment_db:
    provider_id: payment-db
    profile: sit
  payment_events:
    provider_id: payment-events
    profile: sit
---
provider_instances:
  payment-api:
    provider_instance_version: v0.2
    provider_id: payment-api
    provider_type: rest_client
    runtime_modes: [native]
    binding_keys:
      base_url:
        required: true
  payment-db:
    provider_instance_version: v0.2
    provider_id: payment-db
    provider_type: jdbc_database
    runtime_modes: [native]
    binding_keys:
      jdbc_url:
        required: true
      username:
        required: true
      password:
        required: true
  payment-events:
    provider_instance_version: v0.2
    provider_id: payment-events
    provider_type: kafka_messaging
    runtime_modes: [native]
    binding_keys:
      bootstrap_servers:
        required: true
---
environment_id: sit_payment
profile: sit
provider_bindings:
  - provider_id: payment-api
    runtime_mode: native
    binding_values:
      base_url: env.PAYMENT_API_BASE_URL
  - provider_id: payment-db
    runtime_mode: native
    binding_values:
      jdbc_url: env.PAYMENT_DB_JDBC_URL
      username: env.PAYMENT_DB_USER
      password:
        secret_ref: vault://sit/payment/db-password
  - provider_id: payment-events
    runtime_mode: native
    binding_values:
      bootstrap_servers: env.PAYMENT_KAFKA_BOOTSTRAP_SERVERS
```

The DSL would reference logical names such as `submit_payment`, `payment_payload`, and `payment_events`. It would not contain URLs, credentials, SQL bodies, topic client settings, or K8s commands.

## 7.7 Extension Rules

- Configure an existing built-in provider before adding new provider code.
- Add provider code only when the behavior is reusable across RPs and cannot be safely represented by existing contracts.
- Add DSL enum values only for recurring cross-RP concepts, not for product-specific details.
- Add or change required DSL fields only through a `dsl_version` compatibility decision.
- External runner bridge is acceptable only for approved legacy or specialized systems when standard evidence can still be produced and no reusable built-in provider can safely represent the boundary.
- Every new provider type must include contract validation, Provider Instance validation, Environment Binding validation, failure semantics, evidence mapping, sample fixture coverage, and AC traceability.

## 7.8 Implementation Implications

The next implementation slice should not start by coding REST, Kafka, K8s, and VM providers all at once. It should first introduce or harden the provider capability registry, generated artifact validator, Provider Contract validator, Provider Instance validator, Environment Binding validator, and DSL target resolver that support multiple logical targets, explicit provider_type metadata, capability readiness, unsupported-capability blocking, and standardized evidence output. After that, add provider types in pilot order:

1. Request/response provider for REST or gRPC.
2. DB fixture provider for seed/query/cleanup.
3. Message provider for Kafka or NATS.
4. Deployment readiness provider for K8s and VM.
5. External runner provider only if the selected pilot has an approved escape-hatch need.

This order preserves the common execution model while letting the selected heterogeneous RP prove the framework can support real package variation.
