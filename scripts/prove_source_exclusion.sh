#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="${ROOT_DIR}/logs"
mkdir -p "${LOG_DIR}"

compose() {
  podman compose -f "${ROOT_DIR}/podman-compose.yml" "$@"
}

cleanup() {
  (cd "${ROOT_DIR}" && compose down -v >/dev/null 2>&1 || true)
}

wait_for_app() {
  echo "[wait] app ready check..."
  for _ in $(seq 1 90); do
    if curl -fsS "http://localhost:8080/actuator/health" >/dev/null 2>&1; then
      echo "[wait] app ready."
      return 0
    fi
    sleep 2
  done
  echo "[error] app did not become ready in time." >&2
  return 1
}

generate_traffic() {
  local health_count="${1:-40}"
  local work_count="${2:-10}"
  echo "[traffic] /actuator/health x ${health_count}, /api/work x ${work_count}"
  for _ in $(seq 1 "${health_count}"); do
    curl -fsS "http://localhost:8080/actuator/health" >/dev/null
  done
  for _ in $(seq 1 "${work_count}"); do
    curl -fsS "http://localhost:8080/api/work" >/dev/null
  done
}

count_pattern() {
  local pattern="$1"
  local file="$2"
  rg -c "$pattern" "$file" || true
}

run_case() {
  local case_name="$1"
  local extra_java_opts="$2"
  local collector_log="${LOG_DIR}/${case_name}.collector.log"
  local app_log="${LOG_DIR}/${case_name}.app.log"

  echo "=== case: ${case_name} ==="
  trap cleanup EXIT
  (cd "${ROOT_DIR}" && EXTRA_JAVA_TOOL_OPTIONS="${extra_java_opts}" COLLECTOR_CONFIG="collector-baseline.yaml" compose up -d --build)
  wait_for_app
  generate_traffic 50 10
  sleep 5
  (cd "${ROOT_DIR}" && compose logs otel-collector > "${collector_log}" 2>&1 || true)
  (cd "${ROOT_DIR}" && compose logs app > "${app_log}" 2>&1 || true)
  (cd "${ROOT_DIR}" && compose down -v)
  trap - EXIT

  local health_count
  local work_count
  health_count="$(count_pattern "/actuator/health" "${collector_log}")"
  work_count="$(count_pattern "/api/work" "${collector_log}")"
  echo "[result] ${case_name} health mentions: ${health_count}"
  echo "[result] ${case_name} work mentions  : ${work_count}"
}

echo "[start] compare baseline vs source exclusion(extension)"
run_case "baseline_source_off" ""
run_case "source_exclusion_on" "-Dotel.javaagent.extensions=/app/actuator-drop-extension.jar"

baseline_health="$(count_pattern "/actuator/health" "${LOG_DIR}/baseline_source_off.collector.log")"
source_health="$(count_pattern "/actuator/health" "${LOG_DIR}/source_exclusion_on.collector.log")"
baseline_work="$(count_pattern "/api/work" "${LOG_DIR}/baseline_source_off.collector.log")"
source_work="$(count_pattern "/api/work" "${LOG_DIR}/source_exclusion_on.collector.log")"

echo "=== summary ==="
echo "baseline health mentions      : ${baseline_health}"
echo "source-exclusion health count : ${source_health}"
echo "baseline work mentions        : ${baseline_work}"
echo "source-exclusion work count   : ${source_work}"

if [[ "${source_health}" -lt "${baseline_health}" ]]; then
  echo "[ok] source-side exclusion works for actuator health/info."
else
  echo "[warn] health spans were not reduced. Check extension loading and attributes."
fi
