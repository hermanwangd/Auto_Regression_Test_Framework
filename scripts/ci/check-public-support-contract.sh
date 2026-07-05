#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

public_files=(
  "docs/01-specs/03_feature_specs.md"
  "docs/02-architecture/contracts/framework_usage_interface.v0.2.md"
  "docs/02-architecture/contracts/provider_capability_registry.v0.2.yaml"
  "docs/03-acceptance/04_acceptance_criteria.md"
  "docs/07-validation-evidence/07_regression_test_plan.md"
  "docs/09-operations/test_framework_user_guide.md"
  "docs/09-operations/provider_support_matrix.md"
)

failures=0

check_absent() {
  local pattern="$1"
  local description="$2"
  if rg -n --ignore-case "$pattern" "${public_files[@]}" >/tmp/public-support-contract-matches.$$; then
    echo "public_support_contract_status: failed"
    echo "reason: ${description}"
    cat /tmp/public-support-contract-matches.$$
    failures=1
  fi
  rm -f /tmp/public-support-contract-matches.$$
}

check_present() {
  local pattern="$1"
  local file="$2"
  local description="$3"
  if ! rg -n "$pattern" "$file" >/dev/null; then
    echo "public_support_contract_status: failed"
    echo "reason: ${description}"
    echo "missing_pattern: ${pattern}"
    echo "file: ${file}"
    failures=1
  fi
}

check_absent 'runtime_status|runtime status' "public provider support must use support_status, not runtime_status"
check_absent 'production-ready|framework-verification-only|escape-hatch|contract-only' "legacy support matrix status vocabulary is prohibited"
check_absent 'contract_available|runtime_supported' "legacy boolean provider support vocabulary is prohibited"
check_absent '\| Provider Type \| Native \| Mock \| Ephemeral \|' "support matrix must be provider-level, not runtime-mode columns"

check_present 'support_status_catalog:' "docs/02-architecture/contracts/provider_capability_registry.v0.2.yaml" "registry must define support_status_catalog"
check_present '\| Provider Type \| support_status \|' "docs/09-operations/provider_support_matrix.md" "support matrix must expose support_status column"

if [[ "$failures" -ne 0 ]]; then
  exit 1
fi

echo "public_support_contract_status: passed"
