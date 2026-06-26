# Data Model / Schema Spec

Version: <version>
Status: DRAFT / IN_REVIEW / APPROVED
Owner: <Data Owner>
Related Feature: <F#>
Related Spec: <path>
Last Updated: <YYYY-MM-DD>

---

## 1. Purpose

```text
<why this model/schema exists>
```

---

## 2. Logical Model

| Entity | Description | Relationships |
|---|---|---|

---

## 3. Physical Schema

### Table / Collection: `<name>`

| Column | Type | Nullable | Default | Description |
|---|---|---|---|---|

---

## 4. Keys and Constraints

```text
Primary key:
Foreign keys:
Check constraints:
Required uniqueness:
```

---

## 5. Index Strategy

| Index | Columns | Purpose | Risk |
|---|---|---|---|

---

## 6. Idempotency / Uniqueness

```text
Idempotency key:
Logical unique key:
Duplicate behavior:
Conflict behavior:
```

---

## 7. Partition / Retention Readiness

```text
Partition-ready date columns:
Retention policy:
Cleanup strategy:
High-volume warning:
```

---

## 8. Migration Plan

```text
Forward migration:
Backfill:
Validation:
Deployment order:
```

---

## 9. Rollback / Recovery Plan

```text
Rollback SQL:
Recovery strategy:
Data compatibility:
```

---

## 10. Data Quality Rules

| Rule | Description | Failure Handling |
|---|---|---|

---

## 11. Test Plan

```text
DDL test:
Repository test:
Migration test:
Rollback test:
```

---

## 12. Change Log

| Date | Change | Owner |
|---|---|---|
