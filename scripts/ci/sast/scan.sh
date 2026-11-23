#!/usr/bin/env bash
set -euo pipefail
base=${1:?expected PR base commit}
rules=${2:?expected directory containing gitlab and community rules}
policy_dir=$(cd "$(dirname "$0")" && pwd)
git merge-base --is-ancestor "$base" HEAD

scratch=$(mktemp -d)
trap 'rm -rf -- "$scratch"' EXIT
git ls-files -z > "$scratch/tracked"
targets=()
while IFS= read -r -d '' path; do
  if [[ -f "$path" && ! -L "$path" ]]; then targets+=("$path"); fi
done < "$scratch/tracked"
(( ${#targets[@]} > 0 )) || { echo 'No tracked files to scan' >&2; exit 2; }

for family in gitlab community; do
  semgrep scan --baseline-commit "$base" --config "$rules/$family" \
    --jobs 1 --max-memory 1500 --timeout 40 --metrics off --disable-version-check \
    --no-rewrite-rule-ids --disable-nosem --quiet --json --output "$scratch/$family.json" \
    -- "${targets[@]}"
done
python3 "$policy_dir/policy.py" gate "$scratch/gitlab.json" "$scratch/community.json"
