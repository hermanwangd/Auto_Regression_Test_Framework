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
    assertion-results/
    cleanup-results/
    masking_report.yaml
  review/
    report.yaml
    report.txt
```

Required batch evidence:

- `batch.yaml`: batch ID, profile, status, selected tests, included run IDs, source refs, timing, failure classification.
- `coverage_summary.yaml`: covered AC, total automatable AC, coverage percentage, exclusions as evidence metadata only.
- `evidence_index.yaml`: durable list of evidence refs and masking status.

Required run evidence:

- `run.yaml`: run ID, batch ID, test case ID, profile, provider context, status, timing, failure classification.
- `execution_plan.yaml`: dry-run/resolved plan used for execution.
- `provider-evidence/`: provider-specific request/response, command, DB, event, mock, or readiness evidence.
- `query-evidence/`: DB query evidence, masked params, row counts, and final observed rows when allowed.
- `event-evidence/`: event observation evidence, consume position, attempts, timeout, and last observed event.
- `assertion-results/`: assertion result records and diffs.
- `cleanup-results/`: cleanup actions, strategy, status, and failure evidence.
- `masking_report.yaml`: secret guardrail and redaction status.

Rules:

- Evidence paths must stay under the RP evidence root.
- Raw secrets are prohibited.
- Mock, stub, fake-topic, embedded-broker, and ephemeral evidence must be marked as non-release evidence.
- `regress run --dry-run` may write a resolved execution plan only when explicitly configured; it must not write provider execution evidence.
