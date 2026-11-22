#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

COMMON_ARGS=(
  -n
  -I
  --pcre2
  --with-filename
  --hidden
  -g '!target/**'
  -g '!.git/**'
)
CONFIG_GLOBS=(
  -g '*.yaml'
  -g '*.yml'
  -g '*.json'
  -g '*.properties'
  -g '*.md'
  -g '*.txt'
  -g '*.env'
)
ALLOWLIST_GLOBS=(
  -g '!scripts/ci/secret-scan.sh'
  -g '!src/test/**'
  -g '!samples/40-evidence-reporting/evidence_hardening/invalid_secret_leak_result.json'
  -g '!samples/90-compatibility/legacy-v0.2/40-evidence-reporting/evidence_hardening/invalid_secret_leak_result.json'
  -g '!samples/80-negative/secrets/**'
)

# Both engines consume the same expressions. Only the two contract copies may
# declare this exact public authorization rule as a complete scalar.
SCHEMAS=(schemas/coverage_authority.v1.schema.yaml docs/02-architecture/contracts/coverage_authority.v1.schema.yaml)
RULE='exact_raw_artifact_sha_and_attestor_authorized_by_external_pinned_authority'
FIELD='\b(password|token|api_key|authorization|credential)\b\s*[:=]\s*["'\'']?(?!\*\*\*MASKED\*\*\*|masked|secret://|vault://|generated://|env://|\$\{|true|false|null|[A-Za-z0-9_.-]*ref\b|refs\b|reference\b|required\b|optional\b|allowed\b|policy\b|guardrail\b|field\b|key\b|value\b|values\b)[A-Za-z0-9_./+=:@-]{8,}'
SECRET_FIELD_PATTERN="(?i)$FIELD"
SCHEMA_FIELD_PATTERN="(?i)(?!authorization\\s*:\\s*(?-i:$RULE)[ \t]*$)$FIELD"
# A bare JDBC scheme carries no connection address. Scan every nonempty
# address, including Oracle's non-URL JDBC syntax.
SECRET_URI_PATTERN='(?i)(jdbc:(oracle|postgresql|mysql|mariadb|db2|sqlserver):(?!//(?:[\s"'\'']|$))[^\s"'\'']+|mongodb(\+srv)?://[^\s"'\'']+|nats://[^\s/@:]+:[^\s/@]+@[^\s"'\'']+|-----BEGIN [A-Z ]*PRIVATE KEY-----)'

status=0
if command -v rg >/dev/null 2>&1; then
  scan_rg() {
    local code
    if rg "${COMMON_ARGS[@]}" "$@"; then
      status=1
    else
      code=$?
      if [[ "$code" != 1 ]]; then exit "$code"; fi
    fi
  }
  schema_excludes=()
  for schema in "${SCHEMAS[@]}"; do
    schema_excludes+=(-g "!$schema")
    if [[ -f "$schema" ]]; then scan_rg "$SCHEMA_FIELD_PATTERN" "$schema"; fi
  done
  scan_rg "${CONFIG_GLOBS[@]}" "${ALLOWLIST_GLOBS[@]}" "${schema_excludes[@]}" "$SECRET_FIELD_PATTERN" .
  scan_rg "${ALLOWLIST_GLOBS[@]}" "$SECRET_URI_PATTERN" .
else
  if ! python3 - "$SECRET_FIELD_PATTERN" "$SCHEMA_FIELD_PATTERN" "$SECRET_URI_PATTERN" "${SCHEMAS[@]}" <<'PYTHON_SCAN'
from pathlib import Path
import re
import sys

root = Path(".")
config_suffixes = {".yaml", ".yml", ".json", ".properties", ".md", ".txt", ".env"}
excluded_exact = {
    Path("scripts/ci/secret-scan.sh"),
    Path("samples/40-evidence-reporting/evidence_hardening/invalid_secret_leak_result.json"),
    Path("samples/90-compatibility/legacy-v0.2/40-evidence-reporting/evidence_hardening/invalid_secret_leak_result.json"),
}
excluded_prefixes = (Path(".git"), Path("target"), Path("src/test"), Path("samples/80-negative/secrets"))

secret_field_pattern, schema_field_pattern, secret_uri_pattern = map(re.compile, sys.argv[1:4])
schemas = {Path(name) for name in sys.argv[4:]}


def excluded(path: Path) -> bool:
    if path in excluded_exact:
        return True
    return any(path == prefix or prefix in path.parents for prefix in excluded_prefixes)


def read_text(path):
    try:
        data = path.read_bytes()
    except OSError:
        return None
    if b"\0" in data:
        return None
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return data.decode("utf-8", errors="ignore")


status = 0
for path in root.rglob("*"):
    if not path.is_file() or excluded(path):
        continue
    text = read_text(path)
    if text is None:
        continue
    scan_field = path.suffix in config_suffixes or path.name.endswith(".env")
    field_pattern = schema_field_pattern if path in schemas else secret_field_pattern
    for line_no, line in enumerate(text.splitlines(), start=1):
        if scan_field and field_pattern.search(line):
            print(f"{path}:{line_no}:{line}")
            status = 1
        if secret_uri_pattern.search(line):
            print(f"{path}:{line_no}:{line}")
            status = 1
sys.exit(status)
PYTHON_SCAN
  then
    status=1
  fi
fi

if [[ "$status" -ne 0 ]]; then
  echo "secret_scan_status: failed" >&2
  echo "Owner action: replace raw secret-like values with secret_ref, vault://, generated://, env://, or masked placeholders." >&2
  exit "$status"
fi

echo "secret_scan_status: passed"
