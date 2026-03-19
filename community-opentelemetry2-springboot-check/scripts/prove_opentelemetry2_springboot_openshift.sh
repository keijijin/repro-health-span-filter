#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="${ROOT_DIR}/logs"
mkdir -p "${LOG_DIR}"

PROJECT="${PROJECT:-$(oc project -q)}"
BUILD_NAME="${BUILD_NAME:-community-otel2-app-image}"
IMAGESTREAM_NAME="${IMAGESTREAM_NAME:-community-otel2-app}"
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
  local rendered="${LOG_DIR}/community-otel2-stack.rendered.yaml"
  sed "s|__APP_IMAGE__|${APP_IMAGE}|g" "${ROOT_DIR}/openshift/stack.yaml" > "${rendered}"
  oc apply -f "${rendered}" -n "${PROJECT}" >/dev/null
}

wait_ready() {
  oc rollout status deploy/otel2-collector -n "${PROJECT}" --timeout=180s >/dev/null
  oc rollout status deploy/community-otel2-app -n "${PROJECT}" --timeout=180s >/dev/null
}

collect_logs() {
  local app_log="${LOG_DIR}/community-otel2-app.log"
  local collector_log="${LOG_DIR}/community-otel2-collector.log"
  oc logs deploy/community-otel2-app -n "${PROJECT}" > "${app_log}"
  oc logs deploy/otel2-collector -n "${PROJECT}" > "${collector_log}"
  echo "${app_log}|${collector_log}"
}

verify_counts() {
  local collector_log="$1"
  local async_count process_validate_count process_async_count to_internal_count
  async_count="$(rg -c "async-custom-span" "${collector_log}" || echo 0)"
  process_validate_count="$(rg -c "processValidate-process" "${collector_log}" || echo 0)"
  process_async_count="$(rg -c "processAsync-process" "${collector_log}" || echo 0)"
  to_internal_count="$(rg -c "toInternal-to" "${collector_log}" || echo 0)"

  echo "[result] async-custom-span count     : ${async_count}"
  echo "[result] processValidate-process cnt: ${process_validate_count}"
  echo "[result] processAsync-process cnt   : ${process_async_count}"
  echo "[result] toInternal-to count        : ${to_internal_count}"

  if [[ "${async_count}" -gt 0 && "${process_validate_count}" -eq 0 && "${process_async_count}" -eq 0 && "${to_internal_count}" -eq 0 ]]; then
    echo "[ok] community camel-opentelemetry2 starter preserved async span propagation and suppressed processor/to output."
  else
    echo "[warn] expected span pattern was not observed."
    return 1
  fi
}

echo "[start] OpenShift community opentelemetry2 starter verification"
require_cmds
setup_build
if [[ "${SKIP_BUILD}" != "true" ]]; then
  build_app_image
else
  echo "[build] skip build (SKIP_BUILD=true)"
fi
apply_stack
wait_ready
sleep 8

result="$(collect_logs)"
app_log="${result%%|*}"
collector_log="${result##*|}"

echo "[info] app log: ${app_log}"
echo "[info] collector log: ${collector_log}"
verify_counts "${collector_log}"
