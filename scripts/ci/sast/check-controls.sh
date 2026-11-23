#!/usr/bin/env bash
# Exercise the real differential scanner with inert markers and a local Git history.
set -euo pipefail
scan=$(cd "$(dirname "$0")" && pwd)/scan.sh
tmp=$(mktemp -d)
trap 'rm -rf -- "$tmp"' EXIT
mkdir -p "$tmp/repo" "$tmp/rules/gitlab" "$tmp/rules/community"
for family in gitlab community; do
  cat > "$tmp/rules/$family/control.yml" <<YAML
rules:
  - id: control-$family
    languages: [python]
    pattern: print("bounded-marker")
    message: Synthetic scanner control
    severity: ERROR
    metadata:
      security-severity: Critical
YAML
done
cd "$tmp/repo"
git init -q
git config user.email fixture@example.invalid
git config user.name Fixture
printf '%s\n' 'print("bounded-marker")' > existing.py
git add existing.py
git commit -qm baseline
base=$(git rev-parse HEAD)
printf '\n%s\n' '# harmless change' >> existing.py
git add existing.py
git commit -qm unchanged-finding
"$scan" "$base" "$tmp/rules"
printf '%s\n' 'print("bounded-marker")' > new.py
git add new.py
git commit -qm introduced-finding
result=0
"$scan" "$base" "$tmp/rules" > "$tmp/new.log" || result=$?
[[ "$result" == 1 ]]
for family in gitlab community; do
  grep -F "$family.json: new Critical=1, High=0, other=0, diagnostics=0" "$tmp/new.log"
done
echo 'Existing finding passed; introduced finding blocked.'
