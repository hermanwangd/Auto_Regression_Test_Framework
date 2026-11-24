#!/usr/bin/env bash
set -uo pipefail

# Hosted caches can have public write permissions. Keep the test JVM and its libraries
# together under a private directory when the original executable fails that check.
if [[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_OS:-} == Linux ]]; then
  executable=$(realpath -e "$JAVA_HOME/bin/java") || exit 1
  item_is_file=1
  item=$executable
  while :; do
    mode=$(stat -c '%a' "$item") || exit 1
    if (( (8#$mode & 2) && (item_is_file || !(8#$mode & 01000)) )); then
      echo "Staging test JDK because $item has mode $mode"
      private_jdk=$(mktemp -d /tmp/framework-test-jdk.XXXXXXXX) || exit 1
      trap 'rm -rf -- "$private_jdk"' EXIT
      cp -a --reflink=auto "$JAVA_HOME/." "$private_jdk/" || exit 1
      chmod -R go-w "$private_jdk" || exit 1
      export JAVA_HOME="$private_jdk"
      export PATH="$JAVA_HOME/bin:$PATH"
      "$JAVA_HOME/bin/java" -version || exit 1
      break
    fi
    if [[ $item == / ]]; then break; fi
    item=$(dirname -- "$item")
    item_is_file=0
  done
fi

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
