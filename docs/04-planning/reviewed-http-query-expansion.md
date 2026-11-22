# Reviewed HTTP query expansion

The public product module uses the existing framework REST provider. Real Django/Gunicorn verification exposed a report guardrail false positive on ordinary HTTP `Connection` headers. Static-only paths also prevent useful filter, sort and pagination cases. This increment fixes that defect and adds bounded literal query targets without introducing another runner or plugin API.

## Contract and responsibility

The existing `request.path` contains the entire reviewed raw path and optional query. The handoff pins these bytes; the provider sends those original bytes. Preparation, adapter validation and runtime reuse `ReviewedLoopbackHttpPolicy`. Swarm proposes and reviews cases and interprets linked evidence; framework assertions determine verdicts. Product fixtures and exact-owned-resource cleanup belong to the isolated harness. Cognee requirements and native Swarm host installation remain separate validation claims.

Keep the existing exact loopback origin, single GET/HEAD, no headers/body/write methods/redirects, 1 MiB response cap and 1 ms..10 s whole-response deadline. No origin query or fragment is accepted. The raw path retains `/[A-Za-z0-9/_.-]*`, no authority prefix and unchanged URI normalization.

The optional query is a finite form-style grammar:

- At most 2,048 ASCII characters in the whole target and 32 nonempty `key=value` pairs. Exactly one literal `=` separates each pair; no empty query or trailing/empty pair.
- Decode each key/value once with strict percent syntax and strict UTF-8. `+` means space for validation. Never rebuild or decode the transmitted target.
- A decoded key is 1..128 ASCII characters matching `[A-Za-z0-9_.:-]+`. Duplicate decoded keys are rejected case-sensitively, including encoded aliases such as `a` and `%61`.
- A decoded value is 0..512 ASCII characters from letters, digits, space, `_`, `.`, `,`, `:`, `+`, `~` and `-`. Empty values are valid. This does not provide general query-expression support; URLs, nested escapes, delimiters, control characters, templates and Unicode values are outside this increment.
- Compare sensitive names case-insensitively after removing `.`, `_`, `:`, `-`: reject names containing `password`, `passwd`, `token`, `secret`, `authorization`, `credential`, `apikey`, `session`, `cookie`, `connection`, or equal to `auth` or `pwd`. This is a conservative name policy, not proof that arbitrary reviewed literal content is non-sensitive. Reviewers must not put secrets in query values.

The report fix exempts only nonempty lists of HTTP `close`/`keep-alive` tokens at the three actual canonical response-header locations from creating a new `connection` secret context. It preserves inherited secret contexts and recursive raw-secret checks. Arbitrary header values, map-shaped values and non-header `connection` fields receive no exception; existing secret detection remains in force.

## Sequence and acceptance

1. Preserve the test-only RED for the Connection defect (25 tests, nine expected failures at `8b2809d`). Independently review the narrow source diff, run GREEN plus report/evidence guardrail regression, and publish the fix for Kimi's original Gunicorn reproduction.
2. Independently review this precise query contract. Add policy boundary tests and native GET/HEAD tests first; observe the expected query rejection before implementation. Cover exact and exceeded limits, encoded duplicate/sensitive names, malformed escapes/UTF-8, forbidden controls/templates/fragments, and preserved restrictions.
3. Extend the shared policy only. Confirm the server receives the exact raw target once, and changing query bytes after approval causes zero HTTP calls. Retain redirect/body/deadline and evidence validation regression.
4. Update the module's existing documented capability and reproduction procedure. No invented capability-discovery API or Swarm verdict generation.
5. Run the actual packaged module/framework on pinned public projects with independent expected data: JSON Server filter/sort/page, Datasette table filters/shape, and Kimi's distinct HTTP baseline where feasible. Include wrong oracle, unavailable dependency and recovery. A workaround is not evidence of the fixed Gunicorn behavior; retest the original server.
6. Independently review changes and evidence, run applicable framework/module checks, push the feature branch, then remove only task-owned regenerable dependencies, caches and stopped service data explicitly selected for cleanup. Preserve source, pins, canonical evidence and reproducers; record free space and remaining owned processes.

## Guidance and validation gaps

The [ISTQB gTAA/CT-GenAI mapping](../09-operations/product-testing-module.md) continues to apply: reusable adaptation/execution/reporting boundaries, traceable reviewed testware, independently specified oracles, explicit knowledge provenance and evidence-based verdicts. This is reference guidance, not certification or full syllabus coverage. No real shared SIT, company systems, cloud deployment, distributed ownership, live Cognee retrieval or native Swarm agent execution is established by these local product cases.
