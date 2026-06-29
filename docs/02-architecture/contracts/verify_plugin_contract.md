# Verify Plugin Contract v0.2

Status: framework-owned current-stage contract.

Verify plugin metadata declares reusable assertion and observation checks. It must not approve business truth or infer product defect classification.

Required metadata:

- `contract_version`
- `verify_id`
- `verify_type`
- `supported_actual_sources`
- `required_fields`
- `optional_fields`
- `supported_target_types`
- `polling_support`
- `comparison_rule`
- `evidence_outputs`
- `failure_classification`

Validation rules:

- Unsupported `contract_version` blocks before assertion evaluation.
- Missing required `actual`, `expected`, `selector`, `target`, `query`, or `event` fields block before assertion evaluation when required by the verify type.
- Provider metadata checks, such as HTTP status, may omit `actual` only when the selected provider writes the required metadata.
- Polling checks must declare bounded timeout and poll interval.
- Verify failures produce `verification_failed`; contract or field gaps produce schema, binding, or provider-contract errors before execution.
- Dry-run evidence names verify type, required fields, actual/expected source, polling support, AP gate, and owner action.
