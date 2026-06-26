---
name: product-repo-readiness
description: Use when explaining a Product Repo readiness report for Spec Driven Auto Regression, especially after `regress check-readiness` or before creating Release Package artifacts.
---

# Product Repo Readiness

## Overview

Explain a machine-readable Product Repo readiness report in owner-actionable language. The skill turns deterministic CLI findings into review guidance; it does not create or change repo artifacts.

## Inputs

Read the readiness report path supplied by the user, usually:

```bash
docs/08-release/release-packages/<rp_id>/evidence/readiness/readiness.yaml
```

If the user only provides pasted report content, use that content as the source of truth. If no report is available, ask them to run:

```bash
regress check-readiness --root <product-repo> --format yaml
```

## Required Output

Return a concise explanation with these sections:

```yaml
readiness_status: pass|fail|unknown
missing_item_summary:
  - <path or artifact and why it matters>
owner_actions:
  - <specific owner action>
next_command_or_document: <one next command or document to update>
scope_guardrails:
  - <what was not inferred>
```

Use Chinese when the user asks in Chinese; otherwise use the user's language.

## Rules

- Read only the readiness report and directly referenced Product/RP paths.
- Preserve CLI facts such as `status`, `ready`, `gaps`, `path`, `owner_action`, and `next_required_step`.
- Prioritize owner actions in dependency order: Product Repo folders, RP record, RP artifacts, RP/RU mapping, RP AC.
- Do not mutate files, create folders, generate tests, draft expected results, or approve exclusions.
- Do not invent Product scope, RP scope, RP AC, or RP/RU membership.
- If the report is incomplete or ambiguous, say what evidence is missing and which command should refresh it.

## Explanation Pattern

For a failing report, explain:

- What is currently blocked.
- Which missing item blocks the next lifecycle step.
- Who should act, using the report's owner action.
- The next command or document that moves the repo toward RP-level regression readiness.

For a passing report, explain:

- The Product Repo structure is ready.
- The next owner action is to create or check the first RP.
- The skill has not verified RP AC, expected results, provider contracts, or execution readiness unless those facts are present in the report.
