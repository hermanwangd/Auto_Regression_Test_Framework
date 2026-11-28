#!/usr/bin/env bash
# Collect completed main-push attempts for one revision, even if a producer failed.
set -euo pipefail
sha=$1
root=$2
[[ "$sha" =~ ^[0-9a-f]{40}$ ]]
mkdir -p "$root"
[[ -z "$(ls -A "$root")" ]] || { echo "A fresh input directory is required" >&2; exit 2; }
touch "$root/artifacts.jsonl"
deadline=$((SECONDS + 3000))
for workflow in ci local-product-tests pr-sast; do
  while true; do
    run=$(gh run list --workflow "$workflow.yml" --branch main --event push --commit "$sha" --limit 1 \
      --json databaseId,status --jq '.[0] | select(.status == "completed") | .databaseId // empty')
    if [[ "$run" =~ ^[0-9]+$ ]]; then break; fi
    if (( SECONDS >= deadline )); then break; fi
    sleep 30
  done
  [[ "$run" =~ ^[0-9]+$ ]] || continue
  gh run view "$run" --json databaseId,attempt,headSha,headBranch,event,status,conclusion,url,jobs > "$root/$workflow.json"
  jq -e --arg sha "$sha" '.headSha == $sha and .headBranch == "main" and .event == "push" and .status == "completed"' \
    "$root/$workflow.json" >/dev/null
  gh api "repos/{owner}/{repo}/actions/runs/$run/jobs?filter=latest&per_page=100" > "$root/$workflow-jobs.json"
  gh api "repos/{owner}/{repo}/actions/runs/$run/artifacts?per_page=100" > "$root/$workflow-artifacts.json"
  case "$workflow" in
    ci) artifacts=("framework-verification-$run|framework|Framework Gates" "dependency-security-$run|dependency|Dependency Security Scan" "windows-junit-$run|windows|Windows native CLI"
                   "native-jdbc-oracle-$run|oracle|Native JDBC / oracle" "native-jdbc-db2-$run|db2|Native JDBC / db2");;
    local-product-tests) artifacts=("local-product-tests-$run|products|Framework, module and public local products");;
    pr-sast) artifacts=("full-sast-$sha|sast|SAST / full repository");;
  esac
  for spec in "${artifacts[@]}"; do
    IFS='|' read -r name group job <<< "$spec"
    provenance=$(jq -cn --slurpfile jobs "$root/$workflow-jobs.json" --slurpfile artifacts "$root/$workflow-artifacts.json" \
      --arg job "$job" --arg name "$name" --arg group "$group" --arg sha "$sha" --argjson run "$run" '
      $jobs[0].jobs[] | select(.name == $job) as $j |
      $artifacts[0].artifacts[] | select(.name == $name and (.expired | not) and
        .workflow_run.head_sha == $sha and .workflow_run.id == $run and
        .created_at >= $j.started_at and .updated_at <= $j.completed_at) |
      {group:$group, artifact_id:.id, job_id:$j.id, run_id:$run, attempt:$j.run_attempt}')
    [[ -n "$provenance" ]] || { echo "Missing $name for the producing job attempt" >&2; continue; }
    # A failed producer may only have partial evidence. Never consume a partial download.
    id=$(jq -er '.artifact_id' <<< "$provenance")
    if gh run download "$run" --name "$name" --dir "$root/$group.partial" &&
        gh api "repos/{owner}/{repo}/actions/artifacts/$id" --jq '.expired' | grep -qx false; then
      mv "$root/$group.partial" "$root/$group"
      echo "$provenance" >> "$root/artifacts.jsonl"
    else
      echo "Unavailable artifact $name from run $run" >&2
    fi
  done
done
