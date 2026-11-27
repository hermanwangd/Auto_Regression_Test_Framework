#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
scratch=$(mktemp -d)
mail_pid=""
app_pid=""
stop_services() {
  for pid in "$app_pid" "$mail_pid"; do
    if [[ -n "$pid" ]]; then kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; fi
  done
  app_pid=""
  mail_pid=""
}
trap 'stop_services; rm -rf -- "$scratch"' EXIT
case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) platform=linux-amd64; digest=af236b42f171b1c74f0ac5ff444f44d36f2f2ecc0134f5a2f1d4e307e56a0d2c ;;
  Darwin-arm64) platform=darwin-arm64; digest=32933e0bd17e9738f19ab2eb5ec4d50e183448c9f29ba97e762d418a0f72e854 ;;
  *) echo 'This fixture supports Linux x86_64 and macOS arm64.' >&2; exit 2 ;;
esac
archive=$scratch/mailpit.tgz
if [[ -n "${MAILPIT_ARCHIVE:-}" ]]; then
  cp "$MAILPIT_ARCHIVE" "$archive"
else
  curl -fsSL --retry 3 -o "$archive" "https://github.com/axllent/mailpit/releases/download/v1.34.0/mailpit-$platform.tar.gz"
fi
printf '%s  %s\n' "$digest" "$archive" | shasum -a 256 -c -
tar -xzOf "$archive" mailpit > "$scratch/mailpit"
chmod 700 "$scratch/mailpit"
version=$("$root/scripts/release/verify-release-version.sh")
jar=$(realpath "${FRAMEWORK_JAR:-$root/target/spec-driven-auto-regression-$version.jar}")
suite="$root/samples/20-provider-capability-p0/http/mailpit/suite_manifest.yaml"
cli() { java -Xmx256m -jar "$jar" "$@"; }
cd "$scratch"
for variant in valid recipient subject recovery_link; do
  mkdir "$variant"
  cd "$variant"
  # Hold both ephemeral reservations until distinct port numbers are selected.
  read -r smtp_port http_port < <(python3 -c 'import socket
with socket.socket() as smtp, socket.socket() as http:
    smtp.bind(("127.0.0.1", 0))
    http.bind(("127.0.0.1", 0))
    print(smtp.getsockname()[1], http.getsockname()[1])')
  ../mailpit --listen "127.0.0.1:$http_port" --smtp "127.0.0.1:$smtp_port" --database mail.db > mail.log 2>&1 &
  mail_pid=$!
  python3 -B "$root/scripts/ci/mailpit_flow.py" "$smtp_port" "$variant" > app.url &
  app_pid=$!
  export MAILPIT_BASE_URL="http://127.0.0.1:$http_port"
  for i in {1..100}; do
    if [[ -s app.url ]] && curl --noproxy '*' -fsS --max-time 1 "$MAILPIT_BASE_URL/readyz" >/dev/null 2>&1; then break; fi
    kill -0 "$mail_pid" "$app_pid"
    sleep 0.1
  done
  curl --noproxy '*' -fsS --max-time 1 "$MAILPIT_BASE_URL/readyz" >/dev/null
  test -s app.url
  REST_BASE_URL=$(cat app.url)
  export REST_BASE_URL REGRESS_REST_ALLOWED_ORIGINS="$REST_BASE_URL,$MAILPIT_BASE_URL"
  actual=0
  cli run --suite "$suite" --profile external_mailpit > run.log || actual=$?
  cat run.log
  result=$(sed -n 's/^result_json: //p' run.log)
  cli validate-evidence --result "$result"
  if [[ "$variant" == valid ]]; then
    [[ "$actual" == 0 ]]
    jq -e '.status == "passed" and .cleanup_failures == [] and
      ([.verify_results[] | select(.status == "passed") | .id] ==
       ["inbox_started_empty","accepted","one_message","recipient","subject","recovery_link"])' "$result" >/dev/null
  else
    [[ "$actual" == 1 ]]
    jq -e --arg rejected "$variant" '.status == "failed" and .failure.code == "ASSERTION_FAILED" and
      .cleanup_failures == [] and [.verify_results[] | select(.status == "failed") | .id] == [$rejected]' "$result" >/dev/null
  fi
  echo "Verified Mailpit case $variant"
  stop_services
  cd ..
done
