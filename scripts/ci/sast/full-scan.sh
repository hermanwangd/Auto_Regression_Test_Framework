#!/usr/bin/env bash
set -euo pipefail
rules=$(cd "${1:?expected prepared rule directory}" && pwd)
output=$(cd "${2:?expected output directory containing rendered Jobs}" && pwd)
commit=${3:?expected full commit SHA}
policy_dir=$(cd "$(dirname "$0")" && pwd)
[[ "$(git rev-parse HEAD)" == "$commit" ]]

scratch=$(mktemp -d)
trap 'rm -rf -- "$scratch"' EXIT
git ls-files -z > "$scratch/tracked"
targets=()
while IFS= read -r -d '' path; do
  if [[ -f "$path" && ! -L "$path" ]]; then targets+=("$path"); fi
done < "$scratch/tracked"
[[ ${#targets[@]} -gt 0 ]]
printf '%s\0' "${targets[@]}" > "$scratch/targets"
for phase in produce collect; do
  grep -Fxq 'kind: Job' "$output/rendered/$phase.yaml"
done

scan() {
  local family=$1 scope=$2
  shift 2
  local report="$scratch/$family-$scope.json"
  # Publish only complete reports. The gate fails a missing report even if the
  # other family succeeds. A scan without a baseline includes historical findings.
  if semgrep scan --config "$rules/$family" --jobs 1 --max-memory 900 --timeout 60 \
      --metrics off --disable-version-check --no-rewrite-rule-ids --disable-nosem \
      --quiet --json --output "$report.partial" -- "$@"; then
    mv -- "$report.partial" "$report"
  else
    echo "$family-$scope: scanner failed" >&2
  fi
}
for family in gitlab community; do
  scan "$family" repository "${targets[@]}"
  (cd "$output/rendered" && scan "$family" rendered produce.yaml collect.yaml)
done
python3 "$policy_dir/full_gate.py" "$scratch" "$output" "$commit"
