#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

VERSION="$("${ROOT_DIR}/scripts/release/verify-release-version.sh" "${1:-}")"
GIT_SHA="$(git rev-parse HEAD)"
KIT_NAME="spec-driven-auto-regression-${VERSION}-usage-kit.zip"
OUT="target/${KIT_NAME}"
mkdir -p target
WORK_DIR="$(mktemp -d "${ROOT_DIR}/target/usage-kit.XXXXXX")"

cleanup() {
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

KIT_ROOT="${WORK_DIR}/usage-kit"
mkdir -p \
  "${KIT_ROOT}/docs" \
  "${KIT_ROOT}/release" \
  "${KIT_ROOT}/samples"

copy_dir() {
  local src="$1"
  local dst="$2"
  mkdir -p "$(dirname "$dst")"
  cp -R "$src" "$dst"
}

copy_dir docs/09-operations "${KIT_ROOT}/docs/09-operations"
copy_dir docs/08-release/framework "${KIT_ROOT}/docs/08-release/framework"
copy_dir docs/02-architecture/contracts "${KIT_ROOT}/docs/02-architecture/contracts"
copy_dir schemas "${KIT_ROOT}/schemas"
copy_dir samples/golden_e2e "${KIT_ROOT}/samples/golden_e2e"
copy_dir samples/contract_baseline "${KIT_ROOT}/samples/contract_baseline"
copy_dir samples/provider_capability "${KIT_ROOT}/samples/provider_capability"
copy_dir samples/evidence_hardening "${KIT_ROOT}/samples/evidence_hardening"

cp CHANGELOG.md "${KIT_ROOT}/CHANGELOG.md"

cat > "${KIT_ROOT}/README.md" <<EOF_README
# Spec Driven Auto Regression ${VERSION} Usage Kit

This kit contains the user-facing v0.2 documentation, Provider Contract catalog,
schemas, and checked-in sample suites for the Auto Regression Test Framework
release ${VERSION}.

The executable jar is published as a separate release asset:

\`\`\`text
spec-driven-auto-regression-${VERSION}.jar
\`\`\`

## First Commands

From the extracted \`usage-kit/\` directory, run these commands with the matching
release jar:

\`\`\`bash
java -jar ../spec-driven-auto-regression-${VERSION}.jar validate --suite samples/golden_e2e/suite_manifest.yaml
java -jar ../spec-driven-auto-regression-${VERSION}.jar run --suite samples/golden_e2e/suite_manifest.yaml
java -jar ../spec-driven-auto-regression-${VERSION}.jar report --result <generated_result_json>
\`\`\`

For provider capability examples, start with:

\`\`\`bash
java -jar ../spec-driven-auto-regression-${VERSION}.jar validate --suite samples/provider_capability/suite_manifest.yaml
\`\`\`

Mock or sample-only framework evidence must not be treated as downstream
SIT/preprod release evidence. See \`docs/09-operations/provider_support_matrix.md\`.
EOF_README

cat > "${KIT_ROOT}/usage-kit-manifest.yaml" <<EOF_MANIFEST
artifact: spec-driven-auto-regression-usage-kit
version: ${VERSION}
usage_kit_version: ${VERSION}
framework_artifact: spec-driven-auto-regression-${VERSION}.jar
git_sha: ${GIT_SHA}
contract_version: v0.2
included:
  docs:
    - docs/09-operations
    - docs/08-release/framework
    - docs/02-architecture/contracts
  schemas:
    - schemas
  samples:
    - samples/golden_e2e
    - samples/contract_baseline
    - samples/provider_capability
    - samples/evidence_hardening
excluded:
  - .DS_Store
  - target
  - .dependency-check-data
  - docs/idea
  - docs/template
  - docs/99-archive
EOF_MANIFEST

cat > "${KIT_ROOT}/release/verification_commands.md" <<EOF_COMMANDS
# Verification Commands

\`\`\`bash
java -jar spec-driven-auto-regression-${VERSION}.jar validate --suite usage-kit/samples/golden_e2e/suite_manifest.yaml
java -jar spec-driven-auto-regression-${VERSION}.jar run --suite usage-kit/samples/golden_e2e/suite_manifest.yaml
java -jar spec-driven-auto-regression-${VERSION}.jar validate --suite usage-kit/samples/provider_capability/suite_manifest.yaml
java -jar spec-driven-auto-regression-${VERSION}.jar validate --suite usage-kit/samples/provider_capability/kafka/suite_manifest.yaml --profile ci_kafka_external
java -jar spec-driven-auto-regression-${VERSION}.jar validate --suite usage-kit/samples/provider_capability/ibm_mq/suite_manifest.yaml --profile ci_ibm_mq_external
\`\`\`

Official release verification also runs:

\`\`\`bash
scripts/release/verify-supported-provider-samples.sh ${VERSION}
\`\`\`

Kafka and IBM MQ native runtime verification requires external broker or
queue-manager bindings supplied before framework execution.
EOF_COMMANDS

(
  cd "$KIT_ROOT"
  find . \
    \( -name '.DS_Store' -o -path './target/*' -o -path './.dependency-check-data/*' \) \
    -exec rm -rf {} +
)

mkdir -p target
rm -f "$OUT"
if command -v zip >/dev/null 2>&1; then
  (
    cd "$WORK_DIR"
    zip -qr -X "$ROOT_DIR/$OUT" usage-kit
  )
else
  (
    cd "$WORK_DIR"
    python3 - "$ROOT_DIR/$OUT" <<'PY'
import sys
import zipfile
from pathlib import Path

out = Path(sys.argv[1])
root = Path("usage-kit")
with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(root.rglob("*")):
        if path.is_file():
            archive.write(path, path.as_posix())
PY
  )
fi

echo "usage_kit_status: generated"
echo "usage_kit_file: ${OUT}"
