#!/usr/bin/env bash
set -euo pipefail
base=${1:?expected PR base commit}
rules=${2:?expected directory containing gitlab and community rules}
policy_dir=$(cd "$(dirname "$0")" && pwd)
git merge-base --is-ancestor "$base" HEAD

scratch=$(mktemp -d)
trap 'rm -rf -- "$scratch"' EXIT
# Pass explicit tracked targets so tests are checked too. With explicit paths,
# Semgrep can otherwise parse unchanged files and report old parser diagnostics.
git diff --name-only --diff-filter=ACMRT -z "$base" HEAD > "$scratch/changed"
targets=()
while IFS= read -r -d '' path; do
  if [[ -f "$path" && ! -L "$path" ]]; then targets+=("$path"); fi
done < "$scratch/changed"
if [[ ${#targets[@]} == 0 ]]; then
  echo 'No added or changed regular files to scan.'
  exit 0
fi

for family in gitlab community; do
  semgrep scan --baseline-commit "$base" --config "$rules/$family" \
    --jobs 1 --max-memory 1500 --timeout 40 --metrics off --disable-version-check \
    --no-rewrite-rule-ids --disable-nosem --quiet --json --output "$scratch/$family.json" \
    -- "${targets[@]}"
done
python3 "$policy_dir/policy.py" gate "$scratch/gitlab.json" "$scratch/community.json"
