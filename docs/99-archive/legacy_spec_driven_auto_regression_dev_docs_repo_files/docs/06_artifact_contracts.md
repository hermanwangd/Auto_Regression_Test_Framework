# 06. API / Data Model / Artifact Contracts

## 6.1 CLI Commands

```bash
regress validate tests/REG_F001_S001.yaml
regress run --suite smoke
regress run --suite core
regress run --suite full
regress run --feature F001
regress run --test REG_F001_S001
regress report --format html
regress explain --failed REG_F001_S001
```

## 6.2 Agent Commands

```bash
agent check-spec-readiness --spec docs/specs/F001.md
agent generate-regression --spec docs/specs/F001.md
agent update-regression-matrix --spec docs/specs/F001.md
agent triage-failure --report reports/latest.json
```

## 6.3 Test Case DSL Example

```yaml
test_id: REG_F001_S001
feature_id: F001
scenario_id: S001
scenario: Valid object can be created
priority: P1
suite: core
tags:
  - regression
  - api
  - release-gate

data_binding:
  dataset: F001.valid_object

fixture:
  setup:
    - active_user
  cleanup:
    - delete_created_object

steps:
  - name: create_object
    provider: api
    action: POST
    target: /objects
    request_body: ${input.request_body}
    capture:
      object_id: $.body.id

  - name: query_object
    provider: api
    action: GET
    target: /objects/${context.object_id}

assertions:
  - type: status_code
    actual: ${steps.create_object.response.status}
    expected: ${expected.create_status}

  - type: json_path_exists
    actual: ${steps.create_object.response.body.id}

  - type: json_path_equals
    actual: ${steps.query_object.response.body.status}
    expected: ${expected.object.status}
```

## 6.4 Data Catalog Example

```yaml
datasets:
  F001.valid_object:
    input: data/F001/valid_object/input.yaml
    expected: data/F001/valid_object/expected.yaml
    fixture: data/F001/valid_object/fixture.yaml

  F001.missing_required_name:
    input: data/F001/missing_name/input.yaml
    expected: data/F001/missing_name/expected.yaml
```

## 6.5 Dataset Metadata

```yaml
dataset_id: F001.valid_object
owner: project_team_a
lifecycle: reusable
cleanup_required: true
approved_by: qa_lead
data_sensitivity: non_sensitive
```

## 6.6 Variable Namespaces

| Namespace | Meaning |
|---|---|
| `${env.xxx}` | Environment config |
| `${secret.xxx}` | Secret value |
| `${input.xxx}` | Input test data |
| `${expected.xxx}` | Expected result |
| `${param.xxx}` | Parameterized data row |
| `${fixture.xxx}` | Fixture output |
| `${context.xxx}` | Runtime captured value |
| `${steps.xxx}` | Previous step result |

## 6.7 Provider Interface

```text
execute(action, input, context) → actual_result
```

## 6.8 Assertion Types

```text
equals, not_equals, contains, regex_match, json_path_equals, json_path_exists,
schema_match, db_record_exists, db_field_equals, event_published, file_exists,
file_diff, status_transition, unordered_list_compare, numeric_tolerance,
timestamp_tolerance, ignore_field_diff, custom_comparator
```

## 6.9 Oracle Types

| Oracle Type | Description |
|---|---|
| Explicit Expected | Fixed expected value |
| Golden File | Compare with approved baseline |
| Snapshot | Compare with approved snapshot |
| Schema Oracle | Validate structure only |
| State Oracle | Verify DB or system state |
| Event Oracle | Verify event/message |
| Invariant Oracle | Verify rule that should always hold |
| Compatibility Oracle | Verify old data/API/config compatibility |
| Custom Oracle | Project-specific expected logic |
