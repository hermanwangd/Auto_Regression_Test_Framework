#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
scratch=$(mktemp -d)
trap 'rm -rf -- "$scratch"' EXIT
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
python3 -B "$root/scripts/ci/mailpit_flow.py" "$scratch/mailpit" "${FRAMEWORK_JAR:-$root/target/spec-driven-auto-regression-$version.jar}"
