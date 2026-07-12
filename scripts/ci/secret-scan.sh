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

SECRET_FIELD_PATTERN='(?i)\b(password|token|api_key|authorization|credential)\b[[:space:]]*[:=][[:space:]]*["'\'']?(?!\*\*\*MASKED\*\*\*|masked|secret://|vault://|generated://|env://|\$\{|true|false|null|[A-Za-z0-9_.-]*ref\b|refs\b|reference\b|required\b|optional\b|allowed\b|policy\b|guardrail\b|field\b|key\b|value\b|values\b)[A-Za-z0-9_./+=:@-]{8,}'
SECRET_URI_PATTERN='(?i)(jdbc:(oracle|postgresql|mysql|mariadb|db2|sqlserver):[^[:space:]"'\'']+|mongodb(\+srv)?://[^[:space:]"'\'']+|nats://[^[:space:]/@:]+:[^[:space:]/@]+@[^[:space:]"'\'']+|-----BEGIN [A-Z ]*PRIVATE KEY-----)'

status=0
if command -v rg >/dev/null 2>&1; then
  if rg "${COMMON_ARGS[@]}" "${CONFIG_GLOBS[@]}" "${ALLOWLIST_GLOBS[@]}" "$SECRET_FIELD_PATTERN" .; then
    status=1
  fi
  if rg "${COMMON_ARGS[@]}" "${ALLOWLIST_GLOBS[@]}" "$SECRET_URI_PATTERN" .; then
    status=1
  fi
else
  if ! python3 - <<'PY'
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

secret_field_pattern = re.compile(
    r"""\b(password|token|api_key|authorization|credential)\b\s*[:=]\s*["']?"""
    r"""(?!(\*\*\*MASKED\*\*\*|masked|secret://|vault://|generated://|env://|\$\{|true|false|null|[A-Za-z0-9_.-]*ref\b|refs\b|reference\b|required\b|optional\b|allowed\b|policy\b|guardrail\b|field\b|key\b|value\b|values\b))"""
    r"""[A-Za-z0-9_./+=:@-]{8,}""",
    re.IGNORECASE,
)
secret_uri_pattern = re.compile(
    r"""jdbc:(oracle|postgresql|mysql|mariadb|db2|sqlserver):[^\s"']+"""
    r"""|mongodb(\+srv)?://[^\s"']+"""
    r"""|nats://[^\s/@:]+:[^\s/@]+@[^\s"']+"""
    r"""|-----BEGIN [A-Z ]*PRIVATE KEY-----""",
    re.IGNORECASE,
)


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
    for line_no, line in enumerate(text.splitlines(), start=1):
        if scan_field and secret_field_pattern.search(line):
            print(f"{path}:{line_no}:{line}")
            status = 1
        if secret_uri_pattern.search(line):
            print(f"{path}:{line_no}:{line}")
            status = 1
sys.exit(status)
PY
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
