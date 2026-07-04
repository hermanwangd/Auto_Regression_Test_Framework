# Evidence Folder Structure v0.2

Status: framework-owned current-stage contract.

The framework writes evidence under the selected RP package root. Evidence is append-only for a completed batch/run and must be safe to inspect without exposing raw secrets.

```text
docs/08-release/release-packages/<rp_id>/evidence/
  batches/<batch_id>/
    batch.yaml
    coverage_summary.yaml
    evidence_index.yaml
    readiness_summary.yaml
  runs/<run_id>/
    run.yaml
    execution_plan.yaml
    actual/
    logs/
    provider-evidence/
    query-evidence/
    event-evidence/
    assertions/
    cleanup-results/
    masking_report.yaml
  review/
    report.yaml
    report.txt
```

Required batch evidence:

- `batch.yaml`: batch ID, profile, status, selected tests, included run IDs, source refs, timing, failure classification.
- `coverage_summary.yaml`: covered AC, total automatable AC, coverage percentage, exclusions as evidence metadata only.
- `evidence_index.yaml`: durable standard evidence index with one entry per retained evidence artifact.

Standard evidence index entries:

```yaml
entries:
  - evidence_id: jdbc-query-001
    evidence_type: jdbc_query
    produced_by: provider
    provider_type: jdbc
    provider_id: oracle-like-db
    test_case_id: JDBC-CAPABILITY-TC-001
    run_id: RUN-JDBC-001
    batch_id: BATCH-JDBC-001
    file_path: provider-evidence/jdbc/query_query_order_oracle.yaml
    content_type: application/yaml
    status: passed
    created_at: "2026-06-29T00:00:02Z"
    masking_applied: true
    linked_result_field: provider_results.resolved_operation_result
masking:
  raw_secret_found: false
```

Legacy compact entries using `ref:` plus `masked:` are not valid for current public samples. `provider_evidence_refs[]` in result JSON must contain provider-produced evidence only; framework logs, batch summaries, assertion diffs, and expected artifacts remain in `evidence_refs[]`.

Required run evidence:

- `run.yaml`: run ID, batch ID, test case ID, profile, provider context, status, timing, failure classification.
- `execution_plan.yaml`: dry-run/resolved plan used for execution.
- `provider-evidence/`: provider-specific request/response, command, DB, event, mock, or readiness evidence.
- `query-evidence/`: DB query evidence, masked params, row counts, and final observed rows when allowed.
- `event-evidence/`: event observation evidence, consume position, attempts, timeout, and last observed event.
- `assertions/`: assertion result records and diffs.
- `cleanup-results/`: cleanup actions, strategy, status, and failure evidence.
- `masking_report.yaml`: secret guardrail and redaction status.

Rules:

- Evidence paths must stay under the RP evidence root.
- Raw secrets are prohibited.
- Mock, stub, fake-topic, embedded-broker, and ephemeral evidence must be marked as non-release evidence.
- `regress run --dry-run` may write a resolved execution plan only when explicitly configured; it must not write provider execution evidence.
