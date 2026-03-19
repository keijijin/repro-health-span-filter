#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MESSAGE_COUNT="${MESSAGE_COUNT:-10}"

echo "[start] prove community camel-opentelemetry2 starter difference with/without exclude-patterns"
echo "[info] message count: ${MESSAGE_COUNT}"
cd "${ROOT_DIR}"

mvn -q \
  -DskipTests \
  test-compile \
  org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.example.communityspring.CommunityOpenTelemetry2SpringBootComparisonMain \
  -Dexec.args="${MESSAGE_COUNT}"
