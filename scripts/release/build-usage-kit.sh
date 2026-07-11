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

render_doc() {
  local src="$1"
  local dst="$2"
  mkdir -p "$(dirname "$dst")"
  python3 - "$VERSION" "$src" "$dst" <<'PY'
from pathlib import Path
import sys

version, src, dst = sys.argv[1:4]
text = Path(src).read_text(encoding="utf-8").replace("{{VERSION}}", version)
Path(dst).write_text(text, encoding="utf-8")
PY
}

copy_dir docs/09-operations "${KIT_ROOT}/docs/09-operations"
copy_dir docs/08-release/framework "${KIT_ROOT}/docs/08-release/framework"
copy_dir docs/02-architecture/contracts "${KIT_ROOT}/docs/02-architecture/contracts"
mkdir -p "${KIT_ROOT}/docs/v0.3"
copy_dir docs/01-specs/05_dsl_v0_3_no_provider_instance_spec.md "${KIT_ROOT}/docs/v0.3/05_dsl_v0_3_no_provider_instance_spec.md"
copy_dir docs/02-architecture/08_dsl_v0_3_no_provider_instance_architecture.md "${KIT_ROOT}/docs/v0.3/08_dsl_v0_3_no_provider_instance_architecture.md"
copy_dir docs/03-acceptance/05_dsl_v0_3_acceptance_criteria.md "${KIT_ROOT}/docs/v0.3/05_dsl_v0_3_acceptance_criteria.md"
copy_dir docs/07-validation-evidence/11_dsl_v0_3_test_plan.md "${KIT_ROOT}/docs/v0.3/11_dsl_v0_3_test_plan.md"
copy_dir schemas "${KIT_ROOT}/schemas"
copy_dir samples/README.md "${KIT_ROOT}/samples/README.md"
copy_dir samples/00-getting-started "${KIT_ROOT}/samples/00-getting-started"
copy_dir samples/10-contract-baseline "${KIT_ROOT}/samples/10-contract-baseline"
copy_dir samples/20-provider-capability-p0 "${KIT_ROOT}/samples/20-provider-capability-p0"
copy_dir samples/30-cross-provider-groups "${KIT_ROOT}/samples/30-cross-provider-groups"
copy_dir samples/40-evidence-reporting "${KIT_ROOT}/samples/40-evidence-reporting"
copy_dir samples/80-negative "${KIT_ROOT}/samples/80-negative"
copy_dir samples/90-compatibility "${KIT_ROOT}/samples/90-compatibility"
rm -rf "${KIT_ROOT}/samples/90-compatibility/legacy-v0.2"

cp CHANGELOG.md "${KIT_ROOT}/CHANGELOG.md"
render_doc docs/09-operations/quickstart.md "${KIT_ROOT}/QUICKSTART.md"
render_doc docs/09-operations/troubleshooting.md "${KIT_ROOT}/TROUBLESHOOTING.md"
render_doc docs/09-operations/driver_setup.md "${KIT_ROOT}/DRIVER_SETUP.md"
render_doc docs/09-operations/external_runtime_setup.md "${KIT_ROOT}/EXTERNAL_RUNTIME_SETUP.md"
render_doc docs/09-operations/quickstart.md "${KIT_ROOT}/docs/09-operations/quickstart.md"
render_doc docs/09-operations/troubleshooting.md "${KIT_ROOT}/docs/09-operations/troubleshooting.md"
render_doc docs/09-operations/driver_setup.md "${KIT_ROOT}/docs/09-operations/driver_setup.md"
render_doc docs/09-operations/external_runtime_setup.md "${KIT_ROOT}/docs/09-operations/external_runtime_setup.md"

cat > "${KIT_ROOT}/README.md" <<EOF_README
# Spec Driven Auto Regression ${VERSION} Usage Kit

This kit contains the user-facing v0.3 DSL interface, Provider Contract catalog,
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
java -jar ../spec-driven-auto-regression-${VERSION}.jar validate --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml --profile local_v03
java -jar ../spec-driven-auto-regression-${VERSION}.jar run --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml --profile local_v03
java -jar ../spec-driven-auto-regression-${VERSION}.jar report --result <generated_result_json>
\`\`\`

For provider capability examples, start with:

\`\`\`bash
java -jar ../spec-driven-auto-regression-${VERSION}.jar validate --suite samples/20-provider-capability-p0/suite_manifest.yaml
\`\`\`

## Provider Contract Catalog

Use \`docs/02-architecture/contracts/provider-contracts/README.md\` to map
\`provider_contract\` ids such as \`jdbc.v0.3\` or \`rest_client.v0.3\` to the
contract YAML files, required Env_Profile bindings, allowed operations, output
refs, evidence rules, and sample suites.

For HTTP mock/client capability examples, start with:

\`\`\`bash
java -jar ../spec-driven-auto-regression-${VERSION}.jar validate --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml --profile local_v03
java -jar ../spec-driven-auto-regression-${VERSION}.jar run --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml --profile local_v03
java -jar ../spec-driven-auto-regression-${VERSION}.jar validate --suite samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest.yaml --profile local_v03
java -jar ../spec-driven-auto-regression-${VERSION}.jar run --suite samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest.yaml --profile local_v03 --dry-run
\`\`\`

Mock or sample-only framework evidence must not be treated as downstream
SIT/preprod release evidence. See \`docs/09-operations/provider_support_matrix.md\`.
EOF_README

mkdir -p "${KIT_ROOT}/drivers/oracle" "${KIT_ROOT}/drivers/db2"
cat > "${KIT_ROOT}/drivers/README.md" <<EOF_DRIVERS
# JDBC Driver Directory

Oracle and DB2 vendor JDBC drivers are not bundled with this release.

Place approved internal driver jars here when running external JDBC suites from
the usage kit, or pass them explicitly with \`--driver-path\`, \`--driver-dir\`,
or \`REGRESS_DRIVER_PATH\`.

Expected examples:

- \`drivers/oracle/ojdbc11.jar\`
- \`drivers/db2/jcc.jar\`
EOF_DRIVERS

cat > "${KIT_ROOT}/drivers/oracle/put-ojdbc-here.txt" <<EOF_ORACLE
Place the approved Oracle JDBC driver jar here, for example ojdbc11.jar.
Do not commit or redistribute vendor driver binaries in this public repository.
EOF_ORACLE

cat > "${KIT_ROOT}/drivers/db2/put-db2-jcc-here.txt" <<EOF_DB2
Place the approved IBM DB2 JDBC driver jar here, for example jcc.jar.
Do not commit or redistribute vendor driver binaries in this public repository.
EOF_DB2

cat > "${KIT_ROOT}/usage-kit-manifest.yaml" <<EOF_MANIFEST
artifact: spec-driven-auto-regression-usage-kit
version: ${VERSION}
usage_kit_version: ${VERSION}
framework_artifact: spec-driven-auto-regression-${VERSION}.jar
git_sha: ${GIT_SHA}
contract_version: v0.2
contract_versions: [v0.2, v0.3]
sample_layout_version: v3
sample_public_interface: v0.3
default_quickstart_suite: samples/00-getting-started/golden_e2e/suite_manifest.yaml
default_provider_capability_suite: samples/20-provider-capability-p0/suite_manifest.yaml
included:
  root_docs:
    - QUICKSTART.md
    - TROUBLESHOOTING.md
    - DRIVER_SETUP.md
    - EXTERNAL_RUNTIME_SETUP.md
  docs:
    - docs/09-operations
    - docs/08-release/framework
    - docs/02-architecture/contracts
    - docs/v0.3
  schemas:
    - schemas
  samples:
    - samples/00-getting-started
    - samples/10-contract-baseline
    - samples/20-provider-capability-p0
    - samples/30-cross-provider-groups
    - samples/40-evidence-reporting
    - samples/80-negative
    - samples/90-compatibility
canonical_sample_roots:
  - samples/00-getting-started
  - samples/10-contract-baseline
  - samples/20-provider-capability-p0
  - samples/30-cross-provider-groups
  - samples/40-evidence-reporting
  - samples/80-negative
  - samples/90-compatibility
default_sample_roots:
  - samples/00-getting-started
  - samples/10-contract-baseline
  - samples/20-provider-capability-p0
  - samples/30-cross-provider-groups
  - samples/40-evidence-reporting
  - samples/80-negative
  - samples/90-compatibility
canonical_samples:
  - id: golden_e2e
    root_dir: samples/00-getting-started/golden_e2e
    entrypoint_manifest: suite_manifest.yaml
    kind: leaf
    default_profile: local_v03
    suite_id: GOLDEN-E2E-v0.3
    ships_in_usage_kit: true
    public_contract: true
  - id: mixed_wiremock_jdbc_nats
    root_dir: samples/10-contract-baseline/mixed_wiremock_jdbc_nats
    entrypoint_manifest: suite_manifest.yaml
    kind: leaf
    default_profile: local_v03
    suite_id: MIXED-CONTRACT-BASELINE-v0.3
    ships_in_usage_kit: true
    public_contract: true
  - id: provider_capability_p0
    root_dir: samples/20-provider-capability-p0
    entrypoint_manifest: suite_manifest.yaml
    kind: suite_group
    default_profile: null
    suite_id: PROVIDER-CAPABILITY-P0-v0.3
    ships_in_usage_kit: true
    public_contract: true
  - id: rest_client_with_wiremock
    root_dir: samples/20-provider-capability-p0/http/rest_client_with_wiremock
    entrypoint_manifest: suite_manifest.yaml
    kind: leaf
    default_profile: local_v03
    suite_id: HTTP-MOCK-REST-CLIENT-v0.3
    ships_in_usage_kit: true
    public_contract: true
  - id: jdbc
    root_dir: samples/20-provider-capability-p0/data/jdbc
    entrypoint_manifest: suite_manifest.yaml
    kind: leaf
    default_profile: local_v03
    suite_id: JDBC-v0.3
    ships_in_usage_kit: true
    public_contract: true
  - id: nats
    root_dir: samples/20-provider-capability-p0/messaging/nats
    entrypoint_manifest: suite_manifest.yaml
    kind: leaf
    default_profile: local_v03
    suite_id: NATS-v0.3
    ships_in_usage_kit: true
    public_contract: true
  - id: kafka
    root_dir: samples/20-provider-capability-p0/messaging/kafka
    entrypoint_manifest: suite_manifest.yaml
    kind: leaf
    default_profile: local_v03
    suite_id: KAFKA-v0.3
    ships_in_usage_kit: true
    public_contract: true
  - id: ibm_mq
    root_dir: samples/20-provider-capability-p0/messaging/ibm_mq
    entrypoint_manifest: suite_manifest.yaml
    kind: leaf
    default_profile: local_v03
    suite_id: IBM-MQ-v0.3
    ships_in_usage_kit: true
    public_contract: true
  - id: kafka_ibm_mq_mixed
    root_dir: samples/20-provider-capability-p0/messaging/kafka_ibm_mq_mixed
    entrypoint_manifest: suite_manifest.yaml
    kind: leaf
    default_profile: local_v03
    suite_id: KAFKA-IBM-MQ-MIXED-v0.3
    ships_in_usage_kit: true
    public_contract: true
  - id: soap_mock
    root_dir: samples/20-provider-capability-p0/rpc/soap_mock
    entrypoint_manifest: suite_manifest.yaml
    kind: leaf
    default_profile: local_v03
    suite_id: SOAP-MOCK-REST-CLIENT-v0.3
    ships_in_usage_kit: true
    public_contract: true
  - id: grpc_mock
    root_dir: samples/20-provider-capability-p0/rpc/grpc_mock
    entrypoint_manifest: suite_manifest.yaml
    kind: leaf
    default_profile: local_v03
    suite_id: GRPC-MOCK-GRPC-CLIENT-v0.3
    ships_in_usage_kit: true
    public_contract: true
  - id: common_verify
    root_dir: samples/20-provider-capability-p0/verification/common_verify
    entrypoint_manifest: suite_manifest.yaml
    kind: leaf
    default_profile: local_v03
    suite_id: COMMON-VERIFY-v0.3
    ships_in_usage_kit: true
    public_contract: true
  - id: artifact_compare
    root_dir: samples/20-provider-capability-p0/verification/artifact_compare
    entrypoint_manifest: suite_manifest.yaml
    kind: leaf
    default_profile: local_v03
    suite_id: ARTIFACT-COMPARE-v0.3
    ships_in_usage_kit: true
    public_contract: true
  - id: polling_observer
    root_dir: samples/20-provider-capability-p0/verification/polling_observer
    entrypoint_manifest: suite_manifest.yaml
    kind: leaf
    default_profile: local_v03
    suite_id: POLLING-OBSERVER-v0.3
    ships_in_usage_kit: true
    public_contract: true
  - id: multi_test_shared_env
    root_dir: samples/20-provider-capability-p0/verification/multi_test_shared_env
    entrypoint_manifest: suite_manifest.yaml
    kind: leaf
    default_profile: local_v03
    suite_id: MULTI-TEST-v0.3
    ships_in_usage_kit: true
    public_contract: true
  - id: mock_server_cross_verify
    root_dir: samples/30-cross-provider-groups/mock_server_cross_verify
    entrypoint_manifest: suite_manifest.yaml
    kind: suite_group
    default_profile: null
    suite_id: MOCK-SERVER-CROSS-VERIFY-v0.3
    ships_in_usage_kit: true
    public_contract: true
  - id: evidence_hardening
    root_dir: samples/40-evidence-reporting/evidence_hardening
    entrypoint_manifest: null
    result_fixture: valid_result.json
    kind: evidence_fixture
    default_profile: null
    suite_id: null
    ships_in_usage_kit: true
    public_contract: true
  - id: negative
    root_dir: samples/80-negative
    entrypoint_manifest: suite_manifest.yaml
    kind: suite_group
    default_profile: null
    suite_id: NEGATIVE-v0.3
    ships_in_usage_kit: true
    public_contract: true
  - id: compatibility
    root_dir: samples/90-compatibility
    entrypoint_manifest: null
    kind: documentation_group
    default_profile: null
    suite_id: null
    ships_in_usage_kit: true
    public_contract: true
compatibility_aliases: []
path_rewrites:
  - old_path: samples/v0_3_dsl/golden
    new_path: samples/00-getting-started/golden_e2e
    scope: directory
  - old_path: samples/v0_3_dsl/http_mock_rest_client
    new_path: samples/20-provider-capability-p0/http/rest_client_with_wiremock
    scope: directory
  - old_path: samples/v0_3_dsl/data/jdbc
    new_path: samples/20-provider-capability-p0/data/jdbc
    scope: directory
  - old_path: samples/v0_3_dsl/messaging/nats
    new_path: samples/20-provider-capability-p0/messaging/nats
    scope: directory
  - old_path: samples/v0_3_dsl/messaging/kafka
    new_path: samples/20-provider-capability-p0/messaging/kafka
    scope: directory
  - old_path: samples/v0_3_dsl/messaging/ibm_mq
    new_path: samples/20-provider-capability-p0/messaging/ibm_mq
    scope: directory
  - old_path: samples/v0_3_dsl/messaging/kafka_ibm_mq_mixed
    new_path: samples/20-provider-capability-p0/messaging/kafka_ibm_mq_mixed
    scope: directory
  - old_path: samples/v0_3_dsl/rpc/soap_mock_rest_client
    new_path: samples/20-provider-capability-p0/rpc/soap_mock
    scope: directory
  - old_path: samples/v0_3_dsl/rpc/grpc_mock_grpc_client
    new_path: samples/20-provider-capability-p0/rpc/grpc_mock
    scope: directory
  - old_path: samples/v0_3_dsl/verification/common_verify
    new_path: samples/20-provider-capability-p0/verification/common_verify
    scope: directory
  - old_path: samples/v0_3_dsl/verification/artifact_compare
    new_path: samples/20-provider-capability-p0/verification/artifact_compare
    scope: directory
  - old_path: samples/v0_3_dsl/verification/polling_observer
    new_path: samples/20-provider-capability-p0/verification/polling_observer
    scope: directory
  - old_path: samples/v0_3_dsl/multi_test
    new_path: samples/20-provider-capability-p0/verification/multi_test_shared_env
    scope: directory
  - old_path: samples/v0_3_dsl/negative/bindings/invalid_runtime_mode
    new_path: samples/80-negative/bindings/invalid_runtime_mode
    scope: directory
  - old_path: samples/v0_3_dsl/negative/bindings/missing_required_binding
    new_path: samples/80-negative/bindings/missing_required_binding
    scope: directory
  - old_path: samples/v0_3_dsl/negative/bindings/unknown_binding_key
    new_path: samples/80-negative/bindings/unknown_binding_key
    scope: directory
  - old_path: samples/v0_3_dsl/negative/legacy_fields/data_binding
    new_path: samples/80-negative/legacy-fields/data_binding
    scope: directory
  - old_path: samples/v0_3_dsl/negative/operations/unsupported_input
    new_path: samples/80-negative/operations/unsupported_input
    scope: directory
  - old_path: samples/v0_3_dsl/negative/operations/unsupported_operation
    new_path: samples/80-negative/operations/unsupported_operation
    scope: directory
  - old_path: samples/v0_3_dsl/negative/refs/forward_step_ref
    new_path: samples/80-negative/refs/forward_step_ref
    scope: directory
  - old_path: samples/v0_3_dsl/negative/refs/invalid_artifact_ref
    new_path: samples/80-negative/refs/invalid_artifact_ref
    scope: directory
  - old_path: samples/v0_3_dsl/negative/refs/symlink_escape
    new_path: samples/80-negative/refs/symlink_escape
    scope: directory
  - old_path: samples/v0_3_dsl/negative/runtime/cleanup_failure_preservation
    new_path: samples/80-negative/runtime/cleanup_failure_preservation
    scope: directory
  - old_path: samples/v0_3_dsl/negative/secrets/raw_secret_dsl
    new_path: samples/80-negative/secrets/raw_secret_dsl
    scope: directory
  - old_path: samples/v0_3_dsl/negative/secrets/raw_secret_env
    new_path: samples/80-negative/secrets/raw_secret_env
    scope: directory
  - old_path: samples/v0_3_dsl/negative/target_resolution/missing_env_profile_target
    new_path: samples/80-negative/target-resolution/missing_env_profile_target
    scope: directory
  - old_path: samples/v0_3_dsl/negative/target_resolution/missing_provider_contract
    new_path: samples/80-negative/target-resolution/missing_provider_contract
    scope: directory
  - old_path: samples/v0_3_dsl/negative/target_resolution/unknown_target
    new_path: samples/80-negative/target-resolution/unknown_target
    scope: directory
  - old_path: samples/golden_e2e
    new_path: samples/00-getting-started/golden_e2e
    scope: directory
  - old_path: samples/contract_baseline
    new_path: samples/10-contract-baseline/mixed_wiremock_jdbc_nats
    scope: directory
  - old_path: samples/provider_capability
    new_path: samples/20-provider-capability-p0
    scope: directory
  - old_path: samples/evidence_hardening
    new_path: samples/40-evidence-reporting/evidence_hardening
    scope: directory
deprecated_paths:
  - old_path: samples/v0_3_dsl
    applies_to_descendants: true
    status: removed_from_usage_kit
    shipped_in_usage_kit: false
    warning_artifact: samples/90-compatibility/DEPRECATED_PATHS.md
  - old_path: samples/provider_capability
    applies_to_descendants: true
    status: removed_from_usage_kit
    shipped_in_usage_kit: false
    warning_artifact: samples/90-compatibility/DEPRECATED_PATHS.md
legacy_v0_2_backups:
  shipped_in_usage_kit: false
  source_tree_only: true
  source_tree_location: samples/90-compatibility/legacy-v0.2
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
java -jar spec-driven-auto-regression-${VERSION}.jar run --suite usage-kit/samples/00-getting-started/golden_e2e/suite_manifest.yaml --profile local_v03
java -jar spec-driven-auto-regression-${VERSION}.jar validate --suite usage-kit/samples/20-provider-capability-p0/suite_manifest.yaml
java -jar spec-driven-auto-regression-${VERSION}.jar validate --suite usage-kit/samples/20-provider-capability-p0/messaging/nats/suite_manifest.yaml --profile external_nats
java -jar spec-driven-auto-regression-${VERSION}.jar validate --suite usage-kit/samples/20-provider-capability-p0/messaging/kafka/suite_manifest.yaml --profile external_kafka
java -jar spec-driven-auto-regression-${VERSION}.jar validate --suite usage-kit/samples/20-provider-capability-p0/messaging/ibm_mq/suite_manifest.yaml --profile external_ibm_mq
java -jar spec-driven-auto-regression-${VERSION}.jar validate --suite usage-kit/samples/00-getting-started/golden_e2e/suite_manifest.yaml --profile local_v03
java -jar spec-driven-auto-regression-${VERSION}.jar run --suite usage-kit/samples/00-getting-started/golden_e2e/suite_manifest.yaml --profile local_v03
java -jar spec-driven-auto-regression-${VERSION}.jar validate --suite usage-kit/samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest.yaml --profile local_v03
java -jar spec-driven-auto-regression-${VERSION}.jar run --suite usage-kit/samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest.yaml --profile local_v03 --dry-run
java -jar spec-driven-auto-regression-${VERSION}.jar run --suite usage-kit/samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest.yaml --profile local_v03
java -jar spec-driven-auto-regression-${VERSION}.jar validate --suite usage-kit/samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml --profile local_v03
java -jar spec-driven-auto-regression-${VERSION}.jar run --suite usage-kit/samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml --profile local_v03
java -jar spec-driven-auto-regression-${VERSION}.jar validate --suite usage-kit/samples/20-provider-capability-p0/rpc/grpc_mock/suite_manifest.yaml --profile local_v03
java -jar spec-driven-auto-regression-${VERSION}.jar run --suite usage-kit/samples/20-provider-capability-p0/rpc/grpc_mock/suite_manifest.yaml --profile local_v03
\`\`\`

Official release verification also runs:

\`\`\`bash
scripts/release/verify-supported-provider-samples.sh ${VERSION}
scripts/release/verify-v0-3-runtime-samples.sh ${VERSION}
\`\`\`

NATS, Kafka, and IBM MQ native runtime verification requires external broker or
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
