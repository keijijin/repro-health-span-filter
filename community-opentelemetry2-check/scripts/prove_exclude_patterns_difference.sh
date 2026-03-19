#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MESSAGE_COUNT="${MESSAGE_COUNT:-200}"

echo "[start] prove community camel-opentelemetry2 difference with/without exclude-patterns"
echo "[info] message count: ${MESSAGE_COUNT}"
cd "${ROOT_DIR}"

mvn -q \
  -DskipTests \
  test-compile \
  org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.example.community.CommunityOpenTelemetry2ComparisonMain \
  -Dexec.args="${MESSAGE_COUNT}"
