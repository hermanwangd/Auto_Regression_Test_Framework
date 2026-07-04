#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

ZIP_PATH="${1:-}"
VERSION="$("${ROOT_DIR}/scripts/release/verify-release-version.sh" "${2:-}")"
JAR="${ROOT_DIR}/target/spec-driven-auto-regression-${VERSION}.jar"

if [[ -z "$ZIP_PATH" ]]; then
  echo "Usage: scripts/release/verify-usage-kit.sh <usage-kit-zip> [version]" >&2
  exit 2
fi

if [[ "$ZIP_PATH" != /* ]]; then
  ZIP_PATH="${ROOT_DIR}/${ZIP_PATH}"
fi

if [[ ! -s "$ZIP_PATH" ]]; then
  echo "Missing usage kit: $ZIP_PATH" >&2
  exit 1
fi

if [[ ! -s "$JAR" ]]; then
  echo "Missing release jar for usage kit verification: $JAR" >&2
  exit 1
fi

WORK_DIR="$(mktemp -d "${ROOT_DIR}/target/verify-usage-kit.XXXXXX")"
cleanup() {
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

(
  cd "$WORK_DIR"
  jar --extract --file "$ZIP_PATH"
)

required_paths=(
  "usage-kit/README.md"
  "usage-kit/CHANGELOG.md"
  "usage-kit/usage-kit-manifest.yaml"
  "usage-kit/release/verification_commands.md"
  "usage-kit/docs/09-operations/test_framework_user_guide.md"
  "usage-kit/docs/09-operations/provider_support_matrix.md"
  "usage-kit/docs/08-release/framework/framework_release_readiness.md"
  "usage-kit/docs/02-architecture/contracts/provider_capability_registry.v0.2.yaml"
  "usage-kit/docs/02-architecture/contracts/provider-contracts/wiremock_http_mock.yaml"
  "usage-kit/docs/02-architecture/contracts/provider-contracts/jdbc.yaml"
  "usage-kit/docs/02-architecture/contracts/provider-contracts/nats.yaml"
  "usage-kit/schemas/test_case_dsl.v0.2.schema.yaml"
  "usage-kit/schemas/suite_manifest.v0.2.schema.yaml"
  "usage-kit/samples/golden_e2e/suite_manifest.yaml"
  "usage-kit/samples/contract_baseline/suite_manifest.yaml"
  "usage-kit/samples/provider_capability/suite_manifest.yaml"
  "usage-kit/samples/evidence_hardening/valid_result.json"
)

for path in "${required_paths[@]}"; do
  if [[ ! -e "${WORK_DIR}/${path}" ]]; then
    echo "Usage kit missing required path: ${path}" >&2
    exit 1
  fi
done

for forbidden in \
  '.DS_Store' \
  'target' \
  '.dependency-check-data' \
  'docs/idea' \
  'docs/template' \
  'docs/99-archive'
do
  if (cd "${WORK_DIR}/usage-kit" && find . -path "*${forbidden}*" -print -quit) | grep -q .; then
    echo "Usage kit contains forbidden path matching: ${forbidden}" >&2
    exit 1
  fi
done

if ! grep -q "^usage_kit_version: ${VERSION}$" "${WORK_DIR}/usage-kit/usage-kit-manifest.yaml"; then
  echo "Usage kit manifest version does not match ${VERSION}." >&2
  exit 1
fi

if ! grep -q "^version: ${VERSION}$" "${WORK_DIR}/usage-kit/usage-kit-manifest.yaml"; then
  echo "Usage kit manifest public version does not match ${VERSION}." >&2
  exit 1
fi

run_cli() {
  java -Xmx512m -jar "$JAR" "$@"
}

(
  cd "$WORK_DIR/usage-kit"
  run_cli validate --suite samples/golden_e2e/suite_manifest.yaml
  run_cli run --suite samples/golden_e2e/suite_manifest.yaml --dry-run
  run_cli validate --suite samples/provider_capability/suite_manifest.yaml
  run_cli validate-evidence --result samples/evidence_hardening/valid_result.json
)

echo "usage_kit_verification_status: passed"
echo "usage_kit_file: ${ZIP_PATH}"
