#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="${ROOT_DIR}/logs"
mkdir -p "${LOG_DIR}"

PROJECT="${PROJECT:-$(oc project -q)}"
BUILD_NAME="${BUILD_NAME:-repro-app-image}"
IMAGESTREAM_NAME="${IMAGESTREAM_NAME:-repro-app}"
APP_IMAGE="image-registry.openshift-image-registry.svc:5000/${PROJECT}/${IMAGESTREAM_NAME}:latest"
SKIP_BUILD="${SKIP_BUILD:-false}"

require_cmds() {
  command -v oc >/dev/null
  command -v rg >/dev/null
}

setup_build() {
  if ! oc get bc "${BUILD_NAME}" -n "${PROJECT}" >/dev/null 2>&1; then
    oc new-build --binary=true --strategy=docker --name "${BUILD_NAME}" --to "${IMAGESTREAM_NAME}:latest" -n "${PROJECT}" >/dev/null
  fi
}

build_app_image() {
  echo "[build] start OpenShift binary build..."
  oc start-build "${BUILD_NAME}" --from-dir "${ROOT_DIR}" --follow --wait -n "${PROJECT}"
}

apply_stack() {
  local rendered="${LOG_DIR}/stack.rendered.yaml"
  sed "s|__APP_IMAGE__|${APP_IMAGE}|g" "${ROOT_DIR}/openshift/stack.yaml" > "${rendered}"
  oc apply -f "${rendered}" -n "${PROJECT}" >/dev/null
}

wait_ready() {
  oc rollout status deploy/otel-collector -n "${PROJECT}" --timeout=180s >/dev/null
  oc rollout status deploy/repro-app -n "${PROJECT}" --timeout=180s >/dev/null
}

restart_and_wait() {
  oc rollout restart deploy/otel-collector -n "${PROJECT}" >/dev/null
  oc rollout restart deploy/repro-app -n "${PROJECT}" >/dev/null
  wait_ready
}

set_source_exclusion() {
  local extra_opts="$1"
  oc set env deploy/repro-app EXTRA_JAVA_TOOL_OPTIONS="${extra_opts}" -n "${PROJECT}" >/dev/null
  oc rollout status deploy/repro-app -n "${PROJECT}" --timeout=180s >/dev/null
}

generate_traffic() {
  echo "[traffic] /actuator/health x50, /api/work x10"

  oc run traffic-gen --rm -i --restart=Never --image=curlimages/curl:8.7.1 -n "${PROJECT}" -- \
    sh -ec '
      i=0
      until curl -fsS http://repro-app:8080/actuator/health >/dev/null; do
        i=$((i+1))
        if [ "$i" -ge 60 ]; then
          echo "app is not reachable from traffic-gen pod" >&2
          exit 1
        fi
        sleep 1
      done
      i=0
      while [ "$i" -lt 50 ]; do
        curl -fsS http://repro-app:8080/actuator/health >/dev/null
        i=$((i+1))
      done
      i=0
      while [ "$i" -lt 10 ]; do
        curl -fsS http://repro-app:8080/api/work >/dev/null
        i=$((i+1))
      done
      echo done
    ' >/dev/null
}

collect_counts() {
  local case_name="$1"
  local collector_log="${LOG_DIR}/${case_name}.collector.log"
  oc logs deploy/otel-collector -n "${PROJECT}" > "${collector_log}"
  local health_count
  local work_count
  health_count="$(rg -c "/actuator/health" "${collector_log}" || echo 0)"
  work_count="$(rg -c "/api/work" "${collector_log}" || echo 0)"
  echo "${health_count}|${work_count}"
}

run_case() {
  local case_name="$1"
  local extra_opts="$2"
  echo "=== case: ${case_name} ==="
  set_source_exclusion "${extra_opts}"
  restart_and_wait
  generate_traffic
  sleep 5
  local result
  result="$(collect_counts "${case_name}")"
  local health_count="${result%%|*}"
  local work_count="${result##*|}"
  echo "[result] ${case_name} health mentions: ${health_count}"
  echo "[result] ${case_name} work mentions  : ${work_count}"
}

echo "[start] OpenShift source-side exclusion verification"
require_cmds
setup_build
if [[ "${SKIP_BUILD}" != "true" ]]; then
  build_app_image
else
  echo "[build] skip build (SKIP_BUILD=true)"
fi
apply_stack
wait_ready

run_case "openshift_baseline_source_off" ""
run_case "openshift_source_exclusion_on" "-Dotel.javaagent.extensions=/app/actuator-drop-extension.jar"

baseline_health="$(rg -c "/actuator/health" "${LOG_DIR}/openshift_baseline_source_off.collector.log" || echo 0)"
source_health="$(rg -c "/actuator/health" "${LOG_DIR}/openshift_source_exclusion_on.collector.log" || echo 0)"
baseline_work="$(rg -c "/api/work" "${LOG_DIR}/openshift_baseline_source_off.collector.log" || echo 0)"
source_work="$(rg -c "/api/work" "${LOG_DIR}/openshift_source_exclusion_on.collector.log" || echo 0)"

echo "=== summary ==="
echo "baseline health mentions      : ${baseline_health}"
echo "source-exclusion health count : ${source_health}"
echo "baseline work mentions        : ${baseline_work}"
echo "source-exclusion work count   : ${source_work}"

if [[ "${source_health}" -lt "${baseline_health}" ]]; then
  echo "[ok] source-side exclusion works on OpenShift."
else
  echo "[warn] health spans were not reduced. Check extension loading."
fi
