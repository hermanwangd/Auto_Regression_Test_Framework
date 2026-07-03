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
  -g '!samples/evidence_hardening/invalid_secret_leak_result.json'
)

SECRET_FIELD_PATTERN='(?i)\b(password|token|api_key|authorization|credential)\b[[:space:]]*[:=][[:space:]]*["'\'']?(?!\*\*\*MASKED\*\*\*|masked|secret://|vault://|generated://|env://|\$\{|true|false|null|[A-Za-z0-9_.-]*ref\b|refs\b|reference\b|required\b|optional\b|allowed\b|policy\b|guardrail\b|field\b|key\b|value\b|values\b)[A-Za-z0-9_./+=:@-]{8,}'
SECRET_URI_PATTERN='(?i)(jdbc:(oracle|postgresql|mysql|mariadb|db2|sqlserver):[^[:space:]"'\'']+|mongodb(\+srv)?://[^[:space:]"'\'']+|nats://[^[:space:]/@:]+:[^[:space:]/@]+@[^[:space:]"'\'']+|-----BEGIN [A-Z ]*PRIVATE KEY-----)'

status=0
if rg "${COMMON_ARGS[@]}" "${CONFIG_GLOBS[@]}" "${ALLOWLIST_GLOBS[@]}" "$SECRET_FIELD_PATTERN" .; then
  status=1
fi
if rg "${COMMON_ARGS[@]}" "${ALLOWLIST_GLOBS[@]}" "$SECRET_URI_PATTERN" .; then
  status=1
fi

if [[ "$status" -ne 0 ]]; then
  echo "secret_scan_status: failed" >&2
  echo "Owner action: replace raw secret-like values with secret_ref, vault://, generated://, env://, or masked placeholders." >&2
  exit "$status"
fi

echo "secret_scan_status: passed"
