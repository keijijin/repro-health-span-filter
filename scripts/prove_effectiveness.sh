#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="${ROOT_DIR}/logs"
mkdir -p "${LOG_DIR}"
DEBUG_LOG_PATH="/Users/kjin/acom/otel/.cursor/debug.log"
RUN_ID="run-$(date +%s)"

debug_log() {
  local hypothesis_id="$1"
  local location="$2"
  local message="$3"
  local detail="${4:-}"
  python3 - "$hypothesis_id" "$location" "$message" "$detail" "$RUN_ID" "$DEBUG_LOG_PATH" <<'PY'
import json,sys,time,uuid
hyp,loc,msg,detail,run_id,path=sys.argv[1:]
entry={
  "id":f"log_{int(time.time()*1000)}_{uuid.uuid4().hex[:8]}",
  "timestamp":int(time.time()*1000),
  "runId":run_id,
  "hypothesisId":hyp,
  "location":loc,
  "message":msg,
  "data":{"detail":detail},
}
with open(path,"a",encoding="utf-8") as f:
  f.write(json.dumps(entry, ensure_ascii=False) + "\n")
PY
}

compose() {
  podman compose -f "${ROOT_DIR}/podman-compose.yml" "$@"
}

wait_for_app() {
  echo "[wait] app health endpoint ready check..."
  for _ in $(seq 1 60); do
    if curl -fsS "http://localhost:8080/actuator/health" >/dev/null 2>&1; then
      echo "[wait] app is ready."
      return 0
    fi
    sleep 2
  done
  echo "[error] app did not become ready in time." >&2
  return 1
}

generate_traffic() {
  local health_count="${1:-30}"
  local work_count="${2:-10}"
  echo "[traffic] /actuator/health x ${health_count}, /api/work x ${work_count}"
  for _ in $(seq 1 "${health_count}"); do
    curl -fsS "http://localhost:8080/actuator/health" >/dev/null
  done
  for _ in $(seq 1 "${work_count}"); do
    curl -fsS "http://localhost:8080/api/work" >/dev/null
  done
}

count_health_mentions() {
  local file="$1"
  rg -c "/actuator/health" "$file" || true
}

run_case() {
  local case_name="$1"
  local collector_config="$2"
  local log_file="${LOG_DIR}/${case_name}.collector.log"

  #region agent log
  debug_log "H2" "prove_effectiveness.sh:run_case:start" "case start" "case=${case_name},collector=${collector_config}"
  #endregion

  echo "=== case: ${case_name} (${collector_config}) ==="

  set +e
  (cd "${ROOT_DIR}" && COLLECTOR_CONFIG="${collector_config}" compose up -d --build)
  local compose_up_exit=$?
  set -e

  #region agent log
  debug_log "H4" "prove_effectiveness.sh:run_case:compose_up_exit" "compose up exit code" "exit=${compose_up_exit}"
  #endregion

  if [[ "${compose_up_exit}" -ne 0 ]]; then
    local listen_4317
    listen_4317="$(lsof -nP -iTCP:4317 -sTCP:LISTEN 2>&1 || true)"
    #region agent log
    debug_log "H1" "prove_effectiveness.sh:run_case:port_check" "port 4317 listeners" "${listen_4317}"
    #endregion

    local podman_ps
    podman_ps="$(podman ps --format '{{.Names}} {{.Status}} {{.Ports}}' 2>&1 || true)"
    #region agent log
    debug_log "H2" "prove_effectiveness.sh:run_case:podman_ps" "running containers snapshot" "${podman_ps}"
    #endregion
    return "${compose_up_exit}"
  fi

  wait_for_app
  generate_traffic 40 10
  sleep 5
  (cd "${ROOT_DIR}" && compose logs otel-collector > "${log_file}" 2>&1 || true)
  (cd "${ROOT_DIR}" && compose down -v)

  local count
  count="$(count_health_mentions "${log_file}")"
  echo "[result] ${case_name} /actuator/health mentions in collector log: ${count}"
}

echo "[start] baseline then mitigation comparison"
run_case "baseline" "collector-baseline.yaml"
run_case "mitigated" "collector-filter-health.yaml"

baseline_count="$(count_health_mentions "${LOG_DIR}/baseline.collector.log")"
mitigated_count="$(count_health_mentions "${LOG_DIR}/mitigated.collector.log")"

echo "=== summary ==="
echo "baseline mentions : ${baseline_count}"
echo "mitigated mentions: ${mitigated_count}"

if [[ "${mitigated_count}" -lt "${baseline_count}" ]]; then
  echo "[ok] mitigation reduces health-check related spans."
else
  echo "[warn] mitigation did not reduce mentions as expected. Check span attribute keys in logs."
fi
