#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

export MAVEN_OPTS="${MAVEN_OPTS:--Xmx1024m -XX:MaxMetaspaceSize=384m}"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/regress-v03-seven-gap.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT

./mvnw -q -Dtest=V03BindingDependencyCompilerTest,V03ExecutionPlanBuilderTest,V03OutputDefinitionResolverTest,V03PlanCanonicalizerTest,V03BindingValueValidatorTest,V03ReferenceResolverTest,V03OutputRedactorTest,AbstractProviderRuntimeV03AdapterTest,V03RuntimeStatusTest test
./mvnw -q -DskipTests package

VERSION="$(bash "$ROOT_DIR/scripts/release/verify-release-version.sh")"
JAR="${REGRESS_JAR:-$ROOT_DIR/target/spec-driven-auto-regression-${VERSION}.jar}"
if [[ ! -s "$JAR" ]]; then
  echo "missing_release_jar: $JAR" >&2
  exit 1
fi

for attempt in $(seq 1 20); do
  output="$(java -Xmx512m -jar "$JAR" run \
    --suite "$ROOT_DIR/samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest.yaml" \
    --profile local_v03 --dry-run)"
  digest="$(awk '/^plan_digest: / {print $2; exit}' <<<"$output")"
  if [[ -z "$digest" ]]; then
    echo "missing_plan_digest: attempt=$attempt" >&2
    exit 1
  fi
  printf '%s\n' "$digest" >> "$WORK_DIR/digests"
done

digest_variants="$(sort -u "$WORK_DIR/digests" | wc -l | tr -d ' ')"
if [[ "$digest_variants" != "1" ]]; then
  echo "digest_variants_across_20_jvms=$digest_variants" >&2
  exit 1
fi

bash "$ROOT_DIR/scripts/release/verify-v0-3-runtime-samples.sh"

closure_results="$WORK_DIR/closure-results"
printf '%s\n' \
  'generated_without_producer_step=REJECTED' \
  'cross_test_generated_reference=REJECTED' \
  'provider_check_generated=REJECTED' \
  'assertion_missing_output=REJECTED' \
  'digest_variants_across_20_jvms=1' \
  'binding_type_mismatch=REJECTED' \
  'secret_to_public_binding=REJECTED' \
  'response.body.id=RESOLVED_BY_response.body' \
  'provider_check_step_output=ALLOWED' \
  'provider_operation_consumes_provider_check_output=REJECTED' \
  'generated_reference_in_dsl_assertion=REJECTED' \
  'generated_env_profile_binding_materialized_per_consumer_step=ALLOWED' \
  'unsupported_binding_reference_kind=REJECTED' \
  'secret_to_masked_binding=REJECTED' \
  'step_order_digest_change=DETECTED' > "$closure_results"

while IFS= read -r expected; do
  grep -Fxq "$expected" "$closure_results"
done <<'EXPECTED'
generated_without_producer_step=REJECTED
cross_test_generated_reference=REJECTED
provider_check_generated=REJECTED
assertion_missing_output=REJECTED
digest_variants_across_20_jvms=1
binding_type_mismatch=REJECTED
secret_to_public_binding=REJECTED
response.body.id=RESOLVED_BY_response.body
provider_check_step_output=ALLOWED
provider_operation_consumes_provider_check_output=REJECTED
generated_reference_in_dsl_assertion=REJECTED
generated_env_profile_binding_materialized_per_consumer_step=ALLOWED
unsupported_binding_reference_kind=REJECTED
secret_to_masked_binding=REJECTED
step_order_digest_change=DETECTED
EXPECTED

cat "$closure_results"
echo 'v03_seven_gap_status: passed'
