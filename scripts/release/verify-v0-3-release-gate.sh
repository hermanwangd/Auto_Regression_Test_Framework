#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

bash "$ROOT_DIR/scripts/ci/check-schema-drift.sh"
bash "$ROOT_DIR/scripts/ci/verify-contracts.sh"
bash "$ROOT_DIR/scripts/release/verify-v0-3-runtime-samples.sh"

VERSION="$(bash "$ROOT_DIR/scripts/release/verify-release-version.sh")"
JAR="${REGRESS_JAR:-$ROOT_DIR/target/spec-driven-auto-regression-${VERSION}.jar}"
if [[ ! -s "$JAR" ]]; then
  echo "Missing release jar for v0.3 release gate: $JAR" >&2
  exit 1
fi

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/regress-v03-outside-cwd.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT
outside_output="$(cd "$WORK_DIR" && java -Xmx512m -jar "$JAR" validate \
  --suite "$ROOT_DIR/samples/00-getting-started/golden_e2e/suite_manifest.yaml" \
  --profile local_v03)"
printf '%s\n' "$outside_output"
if ! grep -Fq 'validation_status: passed' <<<"$outside_output"; then
  echo 'Bundled Provider Contract registry did not resolve outside the repository cwd.' >&2
  exit 1
fi

echo 'v0_3_release_gate_status: passed'
