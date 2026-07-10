#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

CANONICAL_DIR="docs/02-architecture/contracts"
RUNTIME_DIR="schemas"

required=(
  env_profile.v0.2.schema.yaml
  environment_binding.v0.2.schema.yaml
  evidence_index.v0.2.schema.yaml
  execution_profile.v0.2.schema.yaml
  provider_contract.v0.2.schema.yaml
  provider_instance.v0.2.schema.yaml
  result.v0.2.schema.yaml
  suite_manifest.v0.2.schema.yaml
  test_case_dsl.v0.2.schema.yaml
)

legacy_docs=(
  evidence.v0.2.schema.yaml
  run_profile.v0.2.schema.yaml
)

contains() {
  local needle="$1"
  shift
  local item
  for item in "$@"; do
    [[ "$item" == "$needle" ]] && return 0
  done
  return 1
}

status=0
missing=()
drift=()
unexpected_docs=()
unexpected_runtime=()

for file in "${required[@]}"; do
  if [[ ! -f "${CANONICAL_DIR}/${file}" ]]; then
    missing+=("${CANONICAL_DIR}/${file}")
    status=1
    continue
  fi
  if [[ ! -f "${RUNTIME_DIR}/${file}" ]]; then
    missing+=("${RUNTIME_DIR}/${file}")
    status=1
    continue
  fi
  if ! cmp -s "${CANONICAL_DIR}/${file}" "${RUNTIME_DIR}/${file}"; then
    drift+=("${file}")
    status=1
  fi
done

while IFS= read -r file; do
  [[ -z "$file" ]] && continue
  if ! contains "$file" "${required[@]}" && ! contains "$file" "${legacy_docs[@]}"; then
    unexpected_docs+=("$file")
    status=1
  fi
done < <(find "$CANONICAL_DIR" -maxdepth 1 -type f -name '*.schema.yaml' -exec basename {} \; | sort)

while IFS= read -r file; do
  [[ -z "$file" ]] && continue
  if ! contains "$file" "${required[@]}"; then
    unexpected_runtime+=("$file")
    status=1
  fi
done < <(find "$RUNTIME_DIR" -maxdepth 1 -type f -name '*.schema.yaml' -exec basename {} \; | sort)

if [[ "$status" -ne 0 ]]; then
  echo "schema_drift_status: failed"
  if [[ "${#missing[@]}" -gt 0 ]]; then
    echo "missing_schema_files:"
    printf '  - %s\n' "${missing[@]}"
  fi
  if [[ "${#drift[@]}" -gt 0 ]]; then
    echo "drifted_schema_files:"
    printf '  - %s\n' "${drift[@]}"
    echo "owner_action: Copy canonical docs/02-architecture/contracts/*.schema.yaml to schemas/ or update both intentionally."
  fi
  if [[ "${#unexpected_docs[@]}" -gt 0 ]]; then
    echo "unexpected_canonical_schema_files:"
    printf '  - %s\n' "${unexpected_docs[@]}"
  fi
  if [[ "${#unexpected_runtime[@]}" -gt 0 ]]; then
    echo "unexpected_runtime_schema_files:"
    printf '  - %s\n' "${unexpected_runtime[@]}"
  fi
  echo "legacy_schema_allowlist:"
  printf '  - %s\n' "${legacy_docs[@]}"
  exit 1
fi

echo "schema_drift_status: passed"
echo "canonical_schema_dir: ${CANONICAL_DIR}"
echo "runtime_schema_dir: ${RUNTIME_DIR}"
echo "required_schema_count: ${#required[@]}"
