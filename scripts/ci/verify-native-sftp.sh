#!/usr/bin/env bash
# Run the packaged client over native SFTP against an isolated loopback sshd.
set -euo pipefail
umask 077
root=$(cd "$(dirname "$0")/../.." && pwd)
version=$("$root/scripts/release/verify-release-version.sh")
jar="$root/target/spec-driven-auto-regression-${version}.jar"
test -s "$jar"
scratch=$(mktemp -d)
cd "$scratch"
scratch=$(pwd -P)
server_pid=""
fixture_pid=""
sshd_owner=()
finish() {
  if [[ -n "$fixture_pid" ]]; then kill "$fixture_pid" 2>/dev/null || true; wait "$fixture_pid" 2>/dev/null || true; fi
  if [[ -n "$server_pid" ]]; then "${sshd_owner[@]}" kill "$server_pid" 2>/dev/null || true; fi
  rm -rf -- "$scratch"
}
trap finish EXIT
cp -R "$root/samples/20-provider-capability-p0/storage/sftp" suite
mkdir -p exchange/unrelated-run
printf 'preserve this batch\n' > exchange/unrelated-run/control
ssh-keygen -q -t ed25519 -N '' -f host-key
ssh-keygen -q -t ed25519 -N '' -f user-key
user=$(id -un)
port=$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')
# GitHub-hosted Ubuntu provides its system OpenSSH. Each invocation uses new keys/config/listener.
if [[ ${RUNNER_OS:-} == Linux && $(id -u) != 0 ]]; then
  sshd_owner=(sudo -n)
  if [[ ! -d /run/sshd ]]; then sudo -n install -d -m 0755 /run/sshd; fi
fi
cat > sshd_config <<CONF
ListenAddress 127.0.0.1
Port $port
HostKey "$scratch/host-key"
PidFile "$scratch/sshd.pid"
AuthorizedKeysFile "$scratch/user-key.pub"
AllowUsers $user
StrictModes no
PubkeyAuthentication yes
AuthenticationMethods publickey
PasswordAuthentication no
KbdInteractiveAuthentication no
UsePAM yes
PermitRootLogin prohibit-password
DisableForwarding yes
PermitTTY no
ForceCommand internal-sftp
Subsystem sftp internal-sftp
CONF
# Daemon mode writes the actual sshd pid even when the launcher is sudo.
"${sshd_owner[@]}" /usr/sbin/sshd -f "$scratch/sshd_config" -E "$scratch/sshd.log"
server_pid=$("${sshd_owner[@]}" cat "$scratch/sshd.pid")
export REGRESS_SFTP_ENDPOINT="sftp://127.0.0.1:$port" REGRESS_SFTP_USERNAME="$user" REGRESS_SFTP_ROOT="$scratch/exchange"
REGRESS_SFTP_PRIVATE_KEY=$(cat user-key)
REGRESS_SFTP_KNOWN_HOSTS="[127.0.0.1]:$port $(cut -d ' ' -f 1-2 host-key.pub)"
export REGRESS_SFTP_PRIVATE_KEY REGRESS_SFTP_KNOWN_HOSTS
cli() { java -Xmx384m -jar "$jar" "$@"; }
run_case() {
  local label=$1 expected_code=$2 expected_failure=$3 start_fixture=$4 result status=0
  if [[ $start_fixture == yes ]]; then
    python3 "$root/scripts/ci/sftp-reconciliation-fixture.py" "$REGRESS_SFTP_ROOT" > "$label.fixture" &
    fixture_pid=$!
  fi
  cli run --suite suite/suite_manifest.yaml --profile external_sftp > "$label.log" || status=$?
  cat "$label.log"
  [[ $status == "$expected_code" ]]
  result=$(sed -n 's/^result_json: //p' "$label.log")
  cli validate-evidence --result "$result"
  jq -e --arg failure "$expected_failure" '
    (.cleanup_failures == []) and
    any(.step_results[]; .operation == "sftp_upload_batch" and .status == "passed" and .outputs.filename == "orders.csv") and
    any(.step_results[]; .operation == "sftp_cleanup_batch" and .status == "passed" and .outputs.deleted == true) and
    (if $failure == "" then
       .status == "passed" and any(.step_results[]; .operation == "sftp_verify_return" and
       .outputs.filename == "reconciliation.csv" and .outputs.file_count == 1 and .outputs.records == 2 and
       .outputs.sha256 == "18e61d3380062980b9e888a1189ab907eebcd4fda73e297de0d634af942dbf86")
     else .status == "failed" and .failure.code == $failure end)' "$result" >/dev/null
  if [[ $start_fixture == yes ]]; then
    wait "$fixture_pid"
    fixture_pid=""
    local batch
    batch=$(jq -er '.step_results[] | select(.operation == "sftp_upload_batch") | .outputs.batch' "$result")
    grep -Fx "processed $batch records=2" "$label.fixture"
  fi
  python3 - <<'PY'
from pathlib import Path
root = Path("exchange")
assert list(root.iterdir()) == [root / "unrelated-run"]
assert (root / "unrelated-run/control").read_bytes() == b"preserve this batch\n"
PY
}

run_case success 0 '' yes
case_file=suite/test_cases/order_reconciliation.yaml
sed 's/expected_sha256: 18e61d/expected_sha256: 28e61d/' "$case_file" > wrong-case.yaml
mv wrong-case.yaml "$case_file"
run_case wrong-digest 1 SFTP_CHECKSUM_MISMATCH yes
sed 's/timeout_ms: 5000/timeout_ms: 200/' "$case_file" > short-case.yaml
mv short-case.yaml "$case_file"
run_case absent-return 1 SFTP_RETURN_TIMEOUT no

# Present another, independently generated public key as the authorized host identity.
REGRESS_SFTP_KNOWN_HOSTS="[127.0.0.1]:$port $(cut -d ' ' -f 1-2 user-key.pub)"
status=0
cli run --suite suite/suite_manifest.yaml --profile external_sftp > wrong-host.log || status=$?
[[ $status == 1 ]]
result=$(sed -n 's/^result_json: //p' wrong-host.log)
cli validate-evidence --result "$result"
jq -e '.failure.code == "SFTP_REQUEST_FAILED" and .cleanup_failures == [] and
  any(.step_results[]; .operation == "sftp_cleanup_batch" and .outputs.deleted == false)' "$result" >/dev/null
[[ "$(ls -A exchange)" == unrelated-run ]]
echo 'OpenSSH batch delivery, reconciliation, mismatch, timeout, host pin and cleanup controls passed.'
