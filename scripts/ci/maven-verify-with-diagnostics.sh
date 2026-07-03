#!/usr/bin/env bash
set -uo pipefail

set +e
./mvnw -B -ntp verify
status=$?
set -e

if [[ "$status" -eq 0 ]]; then
  exit 0
fi

echo "::group::Maven verification failure diagnostics"
echo "maven_verify_status: failed"
echo "exit_code: $status"

emit_annotation() {
  local title="$1"
  local message="$2"
  message="${message//'%'/'%25'}"
  message="${message//$'\n'/'%0A'}"
  message="${message//$'\r'/'%0D'}"
  echo "::error title=${title}::${message}"
}

found_report=false
for report_dir in target/surefire-reports target/failsafe-reports; do
  if [[ ! -d "$report_dir" ]]; then
    continue
  fi
  while IFS= read -r report; do
    if grep -Eq '<<< FAILURE|<<< ERROR|Failures: [1-9]|Errors: [1-9]' "$report"; then
      found_report=true
      echo "failed_report: $report"
      sed -n '1,180p' "$report"
      emit_annotation "Maven test failure: $(basename "$report")" "$(sed -n '1,80p' "$report")"
    fi
  done < <(find "$report_dir" -maxdepth 1 -type f -name '*.txt' | sort)
done

if [[ "$found_report" == false ]]; then
  echo "failed_report: none"
  emit_annotation "Maven verify failed without test report" \
    "No failing surefire/failsafe report was found. Inspect the compile/package output in the job logs."
fi

echo "::endgroup::"
exit "$status"
