#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

CYCLONEDX_VERSION="${CYCLONEDX_VERSION:-2.9.2}"
MAVEN_OPTS="${MAVEN_OPTS:-"-Xmx1024m"}" ./mvnw -B -ntp -DskipTests \
  "org.cyclonedx:cyclonedx-maven-plugin:${CYCLONEDX_VERSION}:makeAggregateBom" \
  -DoutputFormat=all

test -s target/bom.json
test -s target/bom.xml

echo "sbom_status: generated"
echo "sbom_json: target/bom.json"
echo "sbom_xml: target/bom.xml"
