#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="${ROOT_DIR}/logs"
mkdir -p "${LOG_DIR}"

PROJECT="${PROJECT:-$(oc project -q)}"
BUILD_NAME="${BUILD_NAME:-redhat-otel2-app-image}"
IMAGESTREAM_NAME="${IMAGESTREAM_NAME:-redhat-otel2-app}"
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
  local rendered="${LOG_DIR}/redhat-otel2-stack.rendered.yaml"
  sed "s|__APP_IMAGE__|${APP_IMAGE}|g" "${ROOT_DIR}/openshift/stack.yaml" > "${rendered}"
  oc apply -f "${rendered}" -n "${PROJECT}" >/dev/null
}

wait_ready() {
  oc rollout status deploy/redhat-otel2-collector -n "${PROJECT}" --timeout=180s >/dev/null
  oc rollout status deploy/redhat-otel2-app -n "${PROJECT}" --timeout=180s >/dev/null
}

wait_scaled_down() {
  local attempts=0
  while true; do
    local count
    count="$(oc get pods -n "${PROJECT}" -l app=redhat-otel2-app --no-headers 2>/dev/null | wc -l | tr -d ' ')"
    if [[ "${count}" == "0" ]]; then
      break
    fi
    attempts=$((attempts+1))
    if [[ "${attempts}" -ge 30 ]]; then
      break
    fi
    sleep 2
  done
}

set_case_env() {
  local mode="$1"
  if [[ "${mode}" == "no_exclude" ]]; then
    oc set env deploy/redhat-otel2-app CAMEL_OPENTELEMETRY2_EXCLUDE_PATTERNS=doesNotMatchAnything* -n "${PROJECT}" >/dev/null
  else
    oc set env deploy/redhat-otel2-app CAMEL_OPENTELEMETRY2_EXCLUDE_PATTERNS- -n "${PROJECT}" >/dev/null
  fi
  oc rollout status deploy/redhat-otel2-app -n "${PROJECT}" --timeout=180s >/dev/null
}

restart_case() {
  oc scale deploy/redhat-otel2-app -n "${PROJECT}" --replicas=0 >/dev/null
  wait_scaled_down
  oc rollout restart deploy/redhat-otel2-collector -n "${PROJECT}" >/dev/null
  oc rollout status deploy/redhat-otel2-collector -n "${PROJECT}" --timeout=180s >/dev/null
  oc scale deploy/redhat-otel2-app -n "${PROJECT}" --replicas=1 >/dev/null
  wait_ready
}

wait_for_spans() {
  local collector_log="$1"
  local attempts=0
  while true; do
    oc logs deploy/redhat-otel2-collector -n "${PROJECT}" > "${collector_log}"
    if rg -q "async-custom-span" "${collector_log}"; then
      break
    fi
    attempts=$((attempts+1))
    if [[ "${attempts}" -ge 12 ]]; then
      break
    fi
    sleep 3
  done
}

collect_counts() {
  local collector_log="$1"
  local async_count process_validate_count process_async_count to_internal_count
  async_count="$(rg -c "async-custom-span" "${collector_log}" || echo 0)"
  process_validate_count="$(rg -c "processValidate-process" "${collector_log}" || echo 0)"
  process_async_count="$(rg -c "processAsync-process" "${collector_log}" || echo 0)"
  to_internal_count="$(rg -c "toInternal-to" "${collector_log}" || echo 0)"
  echo "${async_count}|${process_validate_count}|${process_async_count}|${to_internal_count}"
}

run_case() {
  local case_name="$1"
  local mode="$2"
  local collector_log="${LOG_DIR}/${case_name}.collector.log"

  echo "=== case: ${case_name} ==="
  set_case_env "${mode}"
  restart_case
  wait_for_spans "${collector_log}"

  local result
  result="$(collect_counts "${collector_log}")"
  local async_count="${result%%|*}"
  local remain="${result#*|}"
  local process_validate_count="${remain%%|*}"
  remain="${remain#*|}"
  local process_async_count="${remain%%|*}"
  local to_internal_count="${remain##*|}"

  echo "[result] ${case_name} async-custom-span     : ${async_count}"
  echo "[result] ${case_name} processValidate-process: ${process_validate_count}"
  echo "[result] ${case_name} processAsync-process   : ${process_async_count}"
  echo "[result] ${case_name} toInternal-to          : ${to_internal_count}"
}

echo "[start] OpenShift Red Hat exclude-patterns difference verification"
require_cmds
setup_build
if [[ "${SKIP_BUILD}" != "true" ]]; then
  build_app_image
else
  echo "[build] skip build (SKIP_BUILD=true)"
fi
apply_stack
wait_ready

run_case "redhat_otel2_no_exclude" "no_exclude"
run_case "redhat_otel2_with_exclude" "with_exclude"

no_exclude_validate="$(rg -c "processValidate-process" "${LOG_DIR}/redhat_otel2_no_exclude.collector.log" || echo 0)"
no_exclude_async="$(rg -c "async-custom-span" "${LOG_DIR}/redhat_otel2_no_exclude.collector.log" || echo 0)"
no_exclude_process_async="$(rg -c "processAsync-process" "${LOG_DIR}/redhat_otel2_no_exclude.collector.log" || echo 0)"
no_exclude_to_internal="$(rg -c "toInternal-to" "${LOG_DIR}/redhat_otel2_no_exclude.collector.log" || echo 0)"

with_exclude_validate="$(rg -c "processValidate-process" "${LOG_DIR}/redhat_otel2_with_exclude.collector.log" || echo 0)"
with_exclude_async="$(rg -c "async-custom-span" "${LOG_DIR}/redhat_otel2_with_exclude.collector.log" || echo 0)"
with_exclude_process_async="$(rg -c "processAsync-process" "${LOG_DIR}/redhat_otel2_with_exclude.collector.log" || echo 0)"
with_exclude_to_internal="$(rg -c "toInternal-to" "${LOG_DIR}/redhat_otel2_with_exclude.collector.log" || echo 0)"

echo "=== summary ==="
echo "no exclude processValidate-process : ${no_exclude_validate}"
echo "with exclude processValidate-process: ${with_exclude_validate}"
echo "no exclude processAsync-process    : ${no_exclude_process_async}"
echo "with exclude processAsync-process  : ${with_exclude_process_async}"
echo "no exclude toInternal-to           : ${no_exclude_to_internal}"
echo "with exclude toInternal-to         : ${with_exclude_to_internal}"
echo "no exclude async-custom-span       : ${no_exclude_async}"
echo "with exclude async-custom-span     : ${with_exclude_async}"

if [[ "${no_exclude_validate}" -gt 0 && "${no_exclude_process_async}" -gt 0 && "${no_exclude_to_internal}" -gt 0 \
   && "${with_exclude_validate}" -eq 0 && "${with_exclude_process_async}" -eq 0 && "${with_exclude_to_internal}" -eq 0 \
   && "${with_exclude_async}" -gt 0 ]]; then
  echo "[ok] Red Hat camel-opentelemetry2 starter reduced processor/to output while preserving async span propagation."
else
  echo "[warn] expected difference was not fully observed."
  exit 1
fi
