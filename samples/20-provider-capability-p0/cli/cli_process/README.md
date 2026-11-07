# CLI provider usage kit

This complete reviewed local suite tests jq's `-n -e false`: exit 1, stdout `false\n`, empty stderr. It uses the framework's real `cli_process.v0.3` provider and ordinary assertions. The checked-in executable path and zero digest are deliberately unconfigured; no product executes until a trusted binary is supplied. The review-input file is synthetic example metadata, not approval.

Obtain a trusted official jq release and independently verify its SHA-256 (the public validation uses jq 1.8.2). From the repository or extracted usage-kit root, set `CLI_EXE`, `CLI_SHA256` and a new `CLI_SUITE` directory, then configure a fresh copy:

```sh
python3 - "$CLI_EXE" "$CLI_SHA256" "$CLI_SUITE" <<'PY'
import hashlib, json, re, shutil, sys
from pathlib import Path
binary, digest, destination = sys.argv[1:]
binary = Path(binary).resolve(strict=True)
assert re.fullmatch(r'[a-f0-9]{64}', digest)
assert hashlib.sha256(binary.read_bytes()).hexdigest() == digest
destination = Path(destination)
assert not destination.exists()
shutil.copytree('samples/20-provider-capability-p0/cli/cli_process', destination)
(destination / 'contracts').mkdir()
shutil.copy2('docs/02-architecture/contracts/provider-contracts/cli_process_v0_3.yaml', destination / 'contracts/cli_process_v0_3.yaml')
profile = destination / 'env_profiles/local_cli.yaml'
value = json.loads(profile.read_text())
value['targets']['cli']['bindings'] = {'executable': str(binary), 'executable_sha256': digest}
profile.write_text(json.dumps(value, indent=2) + '\n')
PY
```

Review the concrete binary, test, oracle, source revisions and review facts. Replace the synthetic reviewer/date/scope fields before preparing a handoff. Use the existing `prepare-product-test-handoff --suite "$CLI_SUITE/suite_manifest.yaml" --profile local_cli --review-input "$CLI_SUITE/review-input.json" --out "$CLI_SUITE/handoff/reviewed-tests.json"` command. Preparation hashes facts and is not approval. Supply the approved digest independently through the framework's `REGRESS_REVIEWED_HANDOFF_SHA256` invocation setting or the Swarm module's protected configuration.

The existing CLI commands `validate`, `run`, `report` and `validate-evidence` use this same suite/profile and the generated canonical result path. A correct negative exit produces a **passed test verdict**; changing the expected exit to zero produces `ASSERTION_FAILED`. The Swarm module can execute this suite with the existing `integration/regression/local`, `knowledge_mode: not_used` request. It imports the framework verdict and evidence; it does not implement a separate runner or oracle.

See [CLI bounds and process limitations](../../../../docs/09-operations/reviewed_cli_process.md). This sample supplies local framework evidence, not company SIT or release acceptance. `shell_command` and `external_runner` keep their existing support status.
