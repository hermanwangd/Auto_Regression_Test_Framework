# Track A Framework Contract Spec Patch

Status: v0.2 Track A contract baseline draft.

## Purpose

Track A makes the Auto Regression Test Framework contract-complete before runtime expansion. It defines the public framework-readable artifacts, CLI behavior, validation taxonomy, secret guardrails, P0 provider/verify catalog, sample artifacts, and result/evidence shape needed for later Golden E2E and provider-runtime tracks.

Track A is not runtime-complete. It must not claim full WireMock, JDBC, NATS, K8s, VM, external-runner, heterogeneous provider runtime, Phase 2 Agent Skill, RP/RU topology interpretation, release gate, waiver workflow, or Go/No-Go automation.

## Public Interface Model

The public runtime model is:

```text
DSL Test Case
  -> Provider Instance
  -> Provider Contract
  -> Environment Binding
```

Resolution flow:

```text
DSL target
  -> provider_id
  -> Provider Instance
  -> provider_type
  -> Provider Contract
  -> profile
  -> Environment Binding
```

The DSL references `provider_id` and `profile` only. It must not contain endpoint URLs, topics, DB credentials, namespaces, host values, or raw secrets.

## Track A Deliverables

Track A delivers:

- Contract schemas for DSL Test Case, Provider Contract, Provider Instance, Execution Profile, Environment Binding, Suite Manifest, result, and evidence.
- Evidence folder structure, validation error taxonomy, secret guardrails, and P0 provider/verify catalog.
- CLI contract for `regress validate`, `regress run --dry-run`, and `regress report`.
- Sample artifacts under `samples/` that validate the contract graph without full provider execution.
- Documentation alignment across feature/spec, architecture, AC, test plan, user guide, and ADRs.

## Contract Rules

- Provider Contract defines `provider_type`, allowed operations, allowed `bind_as`, output refs, evidence outputs, failure codes, required binding keys, defaults, safety rules, and valid Provider Instance shape.
- Provider Instance defines one RP logical runtime target and may use only fields, operations, output refs, evidence outputs, and failure codes allowed by its Provider Contract.
- Environment Binding supplies profile-specific actual values and selected `runtime_mode`.
- Execution Profile defines what may run in `local`, `ci`, `sit`, or `preprod`, including dependency substitution and provisioning rules.
- `local` and `ci` may use explicit mock, stub, ephemeral, fake-topic, embedded-broker, disposable-schema, generated-data, or Testcontainers-backed dependencies.
- `sit` and `preprod` default to native dependencies and cannot use mock evidence as downstream release evidence.

## Validation Scope

`regress validate` and `regress run --dry-run` must fail before provider dispatch when any of these are missing or invalid:

- DSL required fields, target `provider_id`, or target `profile`.
- Provider Instance for the DSL target.
- Provider Contract for the Provider Instance `provider_type`.
- Provider Instance shape against Provider Contract.
- Environment Binding for the selected profile and provider.
- Required binding keys.
- Operation allowed by Provider Contract.
- `parameters[].bind_as` allowed by Provider Contract.
- Output refs declared by Provider Contract.
- Secret guardrail and masking rules.

## Acceptance

Track A is accepted when the contract files, public-interface docs, AC, test plan, implementation plan, and sample artifacts are mutually consistent and pass syntax checks. Dry-run planning must be specified, but full provider runtime execution is deferred.
