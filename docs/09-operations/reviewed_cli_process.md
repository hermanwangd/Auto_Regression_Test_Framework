# Reviewed local CLI execution

`cli_process.v0.3` uses the existing provider runtime and v0.3 execution pipeline. The native `run_command` operation starts the exact absolute executable from the environment binding with its literal `process.argv` list. It does not add a shell. A completed process produces `exit_code`, `stdout`, `stderr` and `duration_ms` for the framework's ordinary assertions. Nonzero exit codes are observations; an expected nonzero exit can pass.

Bind exactly `executable` and `executable_sha256`. The executable must be a regular executable file, not a symlink, and the SHA-256 must contain 64 lowercase hexadecimal characters. The runtime stream-hashes the executable immediately before launch and verifies its identity has not changed during hashing. A hash mismatch prevents launch. This pins only the executable file: OS libraries, interpreter dependencies, plugins, network access and external files are not transitively pinned or contained. The runtime is not an OS sandbox. Use a trusted standalone executable, reviewed arguments and public fixture inputs.

The operation accepts these finite literal inputs:

| Input | Bound and default |
| --- | --- |
| `process.argv` | Required list of at most 64 strings, each at most 4096 characters; no NUL |
| `process.stdin` | Optional UTF-8 string, at most 65536 bytes; defaults to empty input and immediate EOF |
| `process.cwd` | Optional normalized relative path inside a new owned directory under the run directory; defaults to `.` |
| `process.timeout_ms` | Optional integer 1..10000; defaults to 5000 |
| `process.max_output_bytes` | Optional combined stdout/stderr byte limit 1..1048576; defaults to 262144 |

Dynamic artifact, step, generated and environment references and template markers are rejected. Arguments such as `$HOME`, `$(...)`, pipes and semicolons remain literal argument data. The environment passed to the executable is cleared and contains only `LANG=C` and `LC_ALL=C`. Executable launchers may add their own environment entries; for example, the macOS Java launcher adds process identity and text-encoding entries. The child working directory starts empty and does not contain suite or oracle files.

Three concurrent workers feed stdin and drain stdout and stderr. One monotonic process deadline covers launch, input delivery, process exit and both pipe completions. Local filesystem preflight, including streamed executable hashing and working-directory creation, precedes that deadline; filesystem I/O is not advertised as preemptible. Overflow, malformed UTF-8, interruption, timeout and incomplete I/O fail the provider operation and return no assertion outputs. The runtime tracks descendants while the parent is running; a tracked live descendant after parent exit fails execution. Failure cleanup terminates the owned parent and tracked descendants with finite grace and forced-termination periods and waits for I/O workers. Cleanup uncertainty fails closed. Process-tree tracking is best effort; descendants can evade observation and this is not process isolation.

Each operation writes one `cli_process` artifact at `provider-evidence/cli_process/<owned-id>.json`. It records the executable and observed hash, SHA-256 of the ordered JSON argv list, SHA-256 of exact UTF-8 stdin, bounds, working directory/environment policy, timing, execution result, tracked descendant IDs and cleanup status. Raw argv and stdin are not persisted. Safe completed stdout/stderr are included. Secret-like stdout or stderr causes `SECRET_RESOLUTION_ERROR`, withheld assertion outputs and masked evidence; masking cannot turn a raw mismatch into a passing assertion. Other failed executions do not persist partial output.

The adapter declares the explicit `LOCAL_PROCESS` effect and refuses shared-state ownership scopes. Local CLI checks cannot establish global read-only behavior or shared-SIT ownership.

The contract file is packaged directly from `docs/02-architecture/contracts/provider-contracts/cli_process_v0_3.yaml` by the existing Maven resource configuration and generated provider-contract index; no second source copy is required.

## Authoring example

Declare the suite target as `provider_contract: cli_process.v0.3`. In its native environment binding, set `executable` to the absolute path of your trusted jq binary and `executable_sha256` to its independently checked digest. Review those concrete values before preparing the handoff. The following test expects jq's documented negative exit and exact output; the framework still supplies the verdict.

```yaml
dsl_version: v0.3
test_case_id: CLI-EXPECTED-NONZERO
title: jq false result has the expected exit status
execute:
  - id: invoke
    target: cli
    op: run_command
    with:
      process.argv: ["-n", "-e", "false"]
      process.timeout_ms: 5000
      process.max_output_bytes: 16384
verify:
  - id: expected_exit
    type: assertion
    assert:
      actual: step://invoke/exit_code
      operator: equals
      expected: 1
  - id: expected_stdout
    type: assertion
    assert:
      actual: step://invoke/stdout
      operator: equals
      expected: "false\n"
  - id: empty_stderr
    type: assertion
    assert:
      actual: step://invoke/stderr
      operator: equals
      expected: ""
```

For the reviewed Swarm module use the existing `integration/regression/local` profile with `knowledge_mode: not_used`, one real provider, and no shared-state/dependency policy. Ordinary v0.3 callers use the same provider and assertion engine. Empty and whitespace literals are valid assertion operands; a missing or null operand and a blank artifact reference remain invalid. [jq's official invocation documentation](https://jqlang.org/manual/#invoking-jq) defines `-e` and its exit status behavior.
