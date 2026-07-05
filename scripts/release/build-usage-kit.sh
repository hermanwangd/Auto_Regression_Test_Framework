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
copy_dir samples/README.md "${KIT_ROOT}/samples/README.md"
copy_dir samples/00-getting-started "${KIT_ROOT}/samples/00-getting-started"
copy_dir samples/10-contract-baseline "${KIT_ROOT}/samples/10-contract-baseline"
copy_dir samples/20-provider-capability-p0 "${KIT_ROOT}/samples/20-provider-capability-p0"
copy_dir samples/30-cross-provider-groups "${KIT_ROOT}/samples/30-cross-provider-groups"
copy_dir samples/40-evidence-reporting "${KIT_ROOT}/samples/40-evidence-reporting"
copy_dir samples/90-compatibility "${KIT_ROOT}/samples/90-compatibility"

generate_legacy_samples() {
  copy_dir "${KIT_ROOT}/samples/00-getting-started/golden_e2e" "${KIT_ROOT}/samples/golden_e2e"
  copy_dir "${KIT_ROOT}/samples/10-contract-baseline/mixed_wiremock_jdbc_nats" "${KIT_ROOT}/samples/contract_baseline"
  copy_dir "${KIT_ROOT}/samples/40-evidence-reporting/evidence_hardening" "${KIT_ROOT}/samples/evidence_hardening"

  mkdir -p "${KIT_ROOT}/samples/provider_capability"
  cp "${KIT_ROOT}/samples/20-provider-capability-p0/suite_manifest.yaml" \
    "${KIT_ROOT}/samples/provider_capability/suite_manifest.yaml"
  python3 - "${KIT_ROOT}/samples/provider_capability/suite_manifest.yaml" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
replacements = {
    "http/wiremock_http_mock/suite_manifest.yaml": "wiremock/suite_manifest.yaml",
    "http/rest_client_with_wiremock/suite_manifest.yaml": "wiremock_http_request/suite_manifest.yaml",
    "data/jdbc/suite_manifest.yaml": "jdbc/suite_manifest.yaml",
    "messaging/nats/suite_manifest.yaml": "nats/suite_manifest.yaml",
    "verification/common_verify/suite_manifest.yaml": "common_verify/suite_manifest.yaml",
    "verification/artifact_compare/suite_manifest.yaml": "compare/suite_manifest.yaml",
    "verification/polling_observer/suite_manifest.yaml": "polling/suite_manifest.yaml",
    "rpc/soap_mock/suite_manifest.yaml": "soap_mock/suite_manifest.yaml",
    "rpc/grpc_mock/suite_manifest.yaml": "grpc_mock/suite_manifest.yaml",
    "messaging/kafka/suite_manifest.yaml": "kafka/suite_manifest.yaml",
    "messaging/ibm_mq/suite_manifest.yaml": "ibm_mq/suite_manifest.yaml",
    "messaging/kafka_ibm_mq_mixed/suite_manifest.yaml": "messaging_mixed/suite_manifest.yaml",
}
for source, target in replacements.items():
    text = text.replace(source, target)
path.write_text(text, encoding="utf-8")
PY
  copy_dir "${KIT_ROOT}/samples/20-provider-capability-p0/http/wiremock_http_mock" "${KIT_ROOT}/samples/provider_capability/wiremock"
  copy_dir "${KIT_ROOT}/samples/20-provider-capability-p0/http/rest_client_with_wiremock" "${KIT_ROOT}/samples/provider_capability/wiremock_http_request"
  copy_dir "${KIT_ROOT}/samples/20-provider-capability-p0/rpc/soap_mock" "${KIT_ROOT}/samples/provider_capability/soap_mock"
  copy_dir "${KIT_ROOT}/samples/20-provider-capability-p0/rpc/grpc_mock" "${KIT_ROOT}/samples/provider_capability/grpc_mock"
  copy_dir "${KIT_ROOT}/samples/20-provider-capability-p0/data/jdbc" "${KIT_ROOT}/samples/provider_capability/jdbc"
  copy_dir "${KIT_ROOT}/samples/20-provider-capability-p0/messaging/nats" "${KIT_ROOT}/samples/provider_capability/nats"
  copy_dir "${KIT_ROOT}/samples/20-provider-capability-p0/messaging/kafka" "${KIT_ROOT}/samples/provider_capability/kafka"
  copy_dir "${KIT_ROOT}/samples/20-provider-capability-p0/messaging/ibm_mq" "${KIT_ROOT}/samples/provider_capability/ibm_mq"
  copy_dir "${KIT_ROOT}/samples/20-provider-capability-p0/messaging/kafka_ibm_mq_mixed" "${KIT_ROOT}/samples/provider_capability/messaging_mixed"
  copy_dir "${KIT_ROOT}/samples/20-provider-capability-p0/verification/common_verify" "${KIT_ROOT}/samples/provider_capability/common_verify"
  copy_dir "${KIT_ROOT}/samples/20-provider-capability-p0/verification/artifact_compare" "${KIT_ROOT}/samples/provider_capability/compare"
  copy_dir "${KIT_ROOT}/samples/20-provider-capability-p0/verification/polling_observer" "${KIT_ROOT}/samples/provider_capability/polling"
  copy_dir "${KIT_ROOT}/samples/30-cross-provider-groups/mock_server_cross_verify" "${KIT_ROOT}/samples/provider_capability/mock_server_cross_verify"
  copy_dir "${KIT_ROOT}/samples/90-compatibility/dummy_rest" "${KIT_ROOT}/samples/provider_capability/dummy_rest"
}

generate_legacy_samples

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
java -jar ../spec-driven-auto-regression-${VERSION}.jar validate --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml
java -jar ../spec-driven-auto-regression-${VERSION}.jar run --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml
java -jar ../spec-driven-auto-regression-${VERSION}.jar report --result <generated_result_json>
\`\`\`

For provider capability examples, start with:

\`\`\`bash
java -jar ../spec-driven-auto-regression-${VERSION}.jar validate --suite samples/20-provider-capability-p0/suite_manifest.yaml
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
sample_layout_version: v2
included:
  docs:
    - docs/09-operations
    - docs/08-release/framework
    - docs/02-architecture/contracts
  schemas:
    - schemas
  samples:
    - samples/00-getting-started
    - samples/10-contract-baseline
    - samples/20-provider-capability-p0
    - samples/30-cross-provider-groups
    - samples/40-evidence-reporting
    - samples/90-compatibility
canonical_sample_roots:
  - samples/00-getting-started
  - samples/10-contract-baseline
  - samples/20-provider-capability-p0
  - samples/30-cross-provider-groups
  - samples/40-evidence-reporting
  - samples/90-compatibility
generated_legacy_sample_roots:
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
java -jar spec-driven-auto-regression-${VERSION}.jar validate --suite usage-kit/samples/00-getting-started/golden_e2e/suite_manifest.yaml
java -jar spec-driven-auto-regression-${VERSION}.jar run --suite usage-kit/samples/00-getting-started/golden_e2e/suite_manifest.yaml
java -jar spec-driven-auto-regression-${VERSION}.jar validate --suite usage-kit/samples/20-provider-capability-p0/suite_manifest.yaml
java -jar spec-driven-auto-regression-${VERSION}.jar validate --suite usage-kit/samples/20-provider-capability-p0/messaging/kafka/suite_manifest.yaml --profile ci_kafka_external
java -jar spec-driven-auto-regression-${VERSION}.jar validate --suite usage-kit/samples/20-provider-capability-p0/messaging/ibm_mq/suite_manifest.yaml --profile ci_ibm_mq_external
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
