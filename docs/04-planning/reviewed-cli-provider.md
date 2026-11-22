# Reviewed CLI provider implementation plan

Baseline: `e593976780f6dc21c1b7ecdffb135a245096fbbb`. Requested capability: test CLI products through the existing framework and Swarm product-test module. The legacy v0.2 shell/command runner remains compatible.

## Scope and acceptance

Add `cli_process.v0.3` / `cli_process` / `run_command` using the existing provider runtime, v0.3 adapter registry, assertions, finalizer and canonical evidence. Do not add a second test runner. The reviewed local integration slice permits one explicitly reviewed native operation, selecting HTTP or CLI from the exact pinned provider contract. Fake component tests remain unchanged.

The CLI binding contains an absolute executable and its SHA-256. Launch directly with an argv list, never by adding a shell. Inputs are bounded literal `process.argv`, optional UTF-8 `process.stdin` (default empty/EOF), a relative `process.cwd` inside a new task-owned working directory (default `.`), integer `process.timeout_ms` (1..10000, default 5000), and integer `process.max_output_bytes` (1..1048576 combined stdout/stderr, default 262144). Limit argv to 64 strings, each at most 4096 characters, and stdin to 65536 UTF-8 bytes. No inherited environment or credentials; use fixed `LANG=C` and `LC_ALL=C`. Do not permit dynamic reference/template expansion in these literal inputs.

Stream-hash and validate the executable immediately before launch, reject symlink/special/unexecutable files and mismatched hashes without starting a process. The trusted executable and reviewed arguments are authorization inputs, not a security sandbox. OS libraries, interpreters, plugins, network activity and externally referenced files are not transitively pinned or contained. Reviewers must use standalone trusted binaries and public fixture input for this slice; do not claim shared-SIT ownership or global read-only behavior. Add an explicit local-process effect that is forbidden with shared-state policy.

Drain stdout and stderr concurrently with a shared byte budget. Feed stdin concurrently and close it even when empty. One monotonic deadline covers input delivery, process exit and pipe completion. Timeout, cancellation, overflow, invalid UTF-8 and launch/hash failure cannot produce a successful provider operation. Terminate and wait for tracked descendants on failed or incomplete runs; record cleanup uncertainty and fail closed. Process-tree handling is best effort, not OS isolation. Do not silently treat truncated output as complete.

A completed process with any exit code yields observed `exit_code`, `stdout`, `stderr`, `duration_ms`; existing framework assertions determine the verdict. Expected nonzero exits may pass. Publish a bounded `cli_process` evidence artifact with executable identity, argv/input hashes, working-directory/environment policy, execution outcome and cleanup status. Mask secret-like output before persistent evidence; masking must not turn a raw-output mismatch into a passing assertion. Preserve original observations only in memory. No user secrets are inputs to public validation.

## Work and evidence

1. Independently review this plan, then add failing provider/hand-off tests before implementation. Tests cover literal metacharacters, EOF/stdin, stdout/stderr, expected/unexpected nonzero exit, overflow, hanging input/output, timeout, invalid encoding, interrupted execution/recovery and executable/input tamper before launch.
2. Implement the additive provider/contract and register it; generalize reviewed selection and evidence checks using exact contract/type/operation bindings, not a broad HTTP-or-CLI acceptance. Keep provenance and release-evidence restrictions.
3. Exercise real public GitHub CLI products: the already pinned PocketBase binary (`--version`) and a pinned official jq release with public JSON on stdin, deterministic filtering, expected nonzero behavior, wrong oracle and recovery. Verify official checksums/licenses and budget downloads; no global install or containers. Freeze expected output before execution.
4. Run affected tests, module tests and one complete framework verification after integration. Independently review implementation and evidence; assess Kimi feedback and rerun justified changes. Push the authorized candidate branch and verify the exact remote SHA so Kimi can fetch it.
5. Retain source, receipts, checksums and failure evidence. Stop only owned processes and remove only enumerated task-created caches/downloads after their last test. Restrict PocketBase raw startup log locally and create a credential-free derivative; share no raw installer credentials. PocketBase invariance claims cover fixture items, not internal authentication metadata.

## Responsibilities and limits

Swarm proposes and reviews tests; the framework executes and determines verdicts; providers translate operations; Cognee supplies traceable knowledge when requested. These public cases use `knowledge_mode: not_used`. gTAA adaptation/execution separation and CT-GenAI independent oracle/provenance practices apply as documented in the module's existing ISTQB mapping. They do not establish certification or complete syllabus coverage. Native Swarm-agent behavior, live Cognee and company/shared/cloud SIT are outside these local provider checks.
