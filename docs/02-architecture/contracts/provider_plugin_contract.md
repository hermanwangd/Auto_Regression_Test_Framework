# Provider Plugin Contract v0.2

Status: framework-owned current-stage contract.

Provider plugin metadata declares reusable provider runtime capabilities behind Provider Contracts, Provider Instances, DSL operations, expected-result readers, verify rules, and evidence collectors.

Required metadata:

- `contract_version`
- `provider_id`
- `provider_type`
- `provider_contract_ref`
- `valid_provider_instance_shape`
- `supported_runtime_modes`
- `supported_actions`
- `allowed_bind_as`
- `required_binding_keys`
- `output_refs`
- `required_safety_fields`
- `supported_profiles`
- `runtime_status`
- `evidence_outputs`
- `cleanup_capabilities`
- `failure_classification`

Validation rules:

- Unsupported `contract_version` blocks before provider dispatch.
- `provider_type` is explicit and resolves to exactly one Provider Contract; heuristic inference is diagnostic only.
- Unsupported, ambiguous, unsafe, or disabled providers block before execution.
- Provider Contracts define allowed runtime modes, allowed operations, allowed `bind_as` values, output refs, evidence outputs, failure codes, and the valid Provider Instance shape.
- Provider Instances reference secrets, endpoints, SQL, payloads, commands, and environment resources by binding key only; Environment Bindings supply profile-specific runtime modes and values.
- Local and CI mock/stub/ephemeral runtime modes must be declared capability, not implicit fallback behavior.
- Fixture providers that mutate state declare compatible cleanup capabilities.
- Dry-run evidence names provider ID, provider type, profile, runtime mode, registry status, contract path, AP gate, affected target, resolved binding keys, and owner action.
- Standard failure classifications are `target_resolution_error`, `environment_error`, `secret_resolution_error`, `fixture_setup_error`, `cleanup_error`, and `execution_error`.
