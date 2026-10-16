#!/usr/bin/env bash
set -euo pipefail

# Mock contracts never admit work to the live user resource slot.
export CI=true

DIAGNOSTICS_ONLY=false
case "$#:${1:-}" in
  0:) ;;
  1:--diagnostics-only) DIAGNOSTICS_ONLY=true ;;
  *)
    echo "Usage: $0 [--diagnostics-only]" >&2
    exit 2
    ;;
esac

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ROOT_ENV_FILE="$ROOT_DIR/.env"
DOCKER_ENV_FILE="$ROOT_DIR/docker/.env"
ROOT_ENV_BACKUP=""
DOCKER_ENV_BACKUP=""
ERR_FILE="$(mktemp)"
OUT_FILE="$(mktemp)"
TEST_OWNERSHIP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/firemud-smoke-image-env-contract.XXXXXX")"
TEST_RUN_ID="smoke-image-env-contract-$$"
chmod 700 "$TEST_OWNERSHIP_DIR"
export TEST_OWNERSHIP_DIR

docker() {
  case "$1 $2" in
    "ps -a"|"network ls"|"volume ls")
      if [[ "${SMOKE_TEST_DIAGNOSTICS_MODE:-}" == true && "$1 $2" == "ps -a" \
        && -e "$TEST_OWNERSHIP_DIR/$GITHUB_RUN_ID.resources" ]]; then
        printf 'mock-container\n'
      fi
      return 0
      ;;
    "ps --filter")
      if [[ "${SMOKE_TEST_DIAGNOSTICS_MODE:-}" == true ]]; then
        local arguments=" $* "
        case "$arguments" in
          *"label=com.docker.compose.service=gateway"*) printf 'mock-gateway\n' ;;
          *"label=com.docker.compose.service=tcp-proxy-service"*) printf 'mock-tcp-proxy-service\n' ;;
          *)
            echo "Unexpected smoke service lookup: $*" >&2
            return 90
            ;;
        esac
        return 0
      fi
      command docker "$@"
      ;;
    "port mock-gateway"|"port mock-tcp-proxy-service")
      if [[ "${SMOKE_TEST_DIAGNOSTICS_MODE:-}" == true ]]; then
        local host_port=""
        case "$2 $3" in
          "mock-gateway 8080/tcp")
            host_port=8080
            [[ "${SMOKE_TEST_MISSING_BINDING:-}" != gateway ]] || host_port=8180
            ;;
          "mock-tcp-proxy-service 8080/tcp")
            host_port=8089
            [[ "${SMOKE_TEST_MISSING_BINDING:-}" != tcp-management ]] || host_port=8189
            ;;
          "mock-tcp-proxy-service 2323/tcp")
            host_port=2323
            [[ "${SMOKE_TEST_MISSING_BINDING:-}" != tcp-telnet ]] || host_port=2324
            ;;
          *)
            echo "Unexpected smoke service port lookup: $*" >&2
            return 90
            ;;
        esac
        printf '0.0.0.0:%s\n' "$host_port"
        return 0
      fi
      command docker "$@"
      ;;
    compose\ *)
      if [[ "${SMOKE_TEST_DIAGNOSTICS_MODE:-}" == true ]]; then
        local arguments=" $* "
        if [[ "$arguments" == *" up "* ]]; then
          touch "$TEST_OWNERSHIP_DIR/$GITHUB_RUN_ID.resources"
        elif [[ "$arguments" == *" down "* ]]; then
          if [[ -e "$TEST_OWNERSHIP_DIR/$GITHUB_RUN_ID.resources" && "${SMOKE_TEST_CLEANUP_FAIL:-false}" == true ]]; then
            return 92
          fi
          rm -f "$TEST_OWNERSHIP_DIR/$GITHUB_RUN_ID.resources"
        fi
        if [[ "$arguments" == *" logs "* ]]; then
          if [[ "$arguments" != *" logs --no-color --timestamps --tail 200 "* ]]; then
            echo "Unexpected smoke diagnostic log command: $*" >&2
            return 90
          fi
          local service="${arguments##* --tail 200 }"
          service="${service% }"
          case "$service" in
            game-design-service|game-session-service|world-management-service) ;;
            *)
              echo "Unexpected smoke diagnostic service selection: $*" >&2
              return 90
              ;;
          esac
          printf 'MOCK_LOG service=%s safeDiagnostic=true\n' "$service"
          [[ "${SMOKE_TEST_LOG_CAPTURE_FAIL:-false}" != true ]] || return 91
        fi
        return 0
      fi
      local expected_receipt_directory
      expected_receipt_directory="$FIREMUD_SMOKE_OWNERSHIP_DIR_RESOLVED/$FIREMUD_SMOKE_PROJECT_KEY.authored-world-source"
      if [[ "${FIREMUD_SMOKE_AUTHORED_WORLD_SOURCE_RECEIPT_DIR_HOST_PATH:-}" != "$expected_receipt_directory" ||
        ! -d "$expected_receipt_directory" ||
        "${FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED:-}" != true ||
        ! -f "${FIREMUD_SMOKE_INITIAL_ADMISSION_CAPABILITY_HOST_PATH:-}" ]]; then
        echo "Compose ran before the claimed initial-admission receipt directory was prepared." >&2
        return 1
      fi
      command docker "$@"
      ;;
    *)
      echo "Unexpected Docker command in smoke image env contract: $*" >&2
      return 1
      ;;
  esac
}
export -f docker

python3() {
  if [[ "${SMOKE_TEST_DIAGNOSTICS_MODE:-}" == true && "${1:-}" == "-" \
    && "${2:-}" == "-f" ]]; then
    printf 'MOCK_HELD_ADMISSION_PROBE\n'
    return "${SMOKE_TEST_HELD_ADMISSION_STATUS:-0}"
  fi
  command python3 "$@"
}
export -f python3

bash() {
  if [[ "${SMOKE_TEST_DIAGNOSTICS_MODE:-}" != true ]]; then
    command bash "$@"
    return $?
  fi
  case "${1##*/}" in
    verify-smoke-images.sh)
      command /bin/bash "$@"
      ;;
    ensure-dev-certs.sh)
      local certificate_root="${FIREMUD_COMPOSE_GRPC_MTLS_CERT_ROOT:?}"
      mkdir -p "$certificate_root/workloads/game-session-service"
      mkdir -p "$certificate_root/workloads/world-management-service"
      printf '%s\n' '-----BEGIN CERTIFICATE-----' 'bW9jay1jZXJ0aWZpY2F0ZQ==' '-----END CERTIFICATE-----' \
        >"$certificate_root/workloads/game-session-service/client.crt"
      cp "$certificate_root/workloads/game-session-service/client.crt" \
        "$certificate_root/workloads/game-session-service/ca.crt"
      cp "$certificate_root/workloads/game-session-service/client.crt" \
        "$certificate_root/workloads/world-management-service/client.crt"
      cp "$certificate_root/workloads/game-session-service/ca.crt" \
        "$certificate_root/workloads/world-management-service/ca.crt"
      ;;
    verify-compose-health.sh)
      return 0
      ;;
    websocket-login-look-smoke.sh)
      printf 'MOCK_WEBSOCKET_PROBE\n'
      return "${SMOKE_TEST_PROBE_STATUS:-0}"
      ;;
    telnet-login-look-smoke.sh)
      printf 'MOCK_TELNET_PROBE\n'
      ;;
    *)
      command /bin/bash "$@"
      ;;
  esac
}
export -f bash

openssl() {
  if [[ "${SMOKE_TEST_DIAGNOSTICS_MODE:-}" != true ]]; then
    command openssl "$@"
    return $?
  fi
  case " $* " in
    *" -ext subjectAltName "*)
      if [[ "$*" == *"/workloads/world-management-service/client.crt"* ]]; then
        printf '%s\n' 'X509v3 Subject Alternative Name:' '    URI:spiffe://firemud/ns/dev/sa/world-management-service'
      else
        printf '%s\n' 'X509v3 Subject Alternative Name:' '    URI:spiffe://firemud/ns/dev/sa/game-session-service'
      fi
      ;;
    *" -outform DER "*)
      printf 'mock-der-certificate'
      ;;
    *" dgst -sha256 "*)
      cat >/dev/null
      printf '(stdin)= %064d\n' 0
      ;;
    *)
      return 0
      ;;
  esac
}
export -f openssl

run_diagnostic_smoke_case() {
  local suffix="$1"
  local config_only="$2"
  local probe_status="$3"
  local logs_fail="$4"
  local expected_status="$5"
  local admission_mode="${7:-baseline}"
  local gateway_api_base="${8:-}"
  local tcp_proxy_api_base="${9:-}"
  local telnet_host="${10:-}"
  local telnet_port="${11:-}"
  local missing_binding="${12:-}"
  local run_id="${TEST_RUN_ID}-${suffix}"
  local actual_status=0
  local keep_args=()
  [[ "${6:-}" != keep ]] || keep_args=(--keep-running)

  set +e
  GITHUB_ACTIONS=true \
    GITHUB_RUN_ID="$run_id" \
    GITHUB_RUN_ATTEMPT=1 \
    GITHUB_JOB=smoke-image-env-contract \
    COMPOSE_PROJECT_NAME="smoke-full-$run_id-1" \
    FIREMUD_SMOKE_TEST_MODE=1 \
    FIREMUD_SMOKE_OWNERSHIP_DIR="$TEST_OWNERSHIP_DIR" \
    SMOKE_IMAGE_TAG=contract-smoke-tag \
    SMOKE_COMPOSE_CONFIG_ONLY="$config_only" \
    SMOKE_TEST_DIAGNOSTICS_MODE=true \
    FIREMUD_SMOKE_ADMISSION_QUALIFICATION="$admission_mode" \
    SMOKE_GATEWAY_API_BASE="$gateway_api_base" \
    SMOKE_TCP_PROXY_API_BASE="$tcp_proxy_api_base" \
    SMOKE_TELNET_HOST="$telnet_host" \
    TCP_PROXY_PORT="$telnet_port" \
    SMOKE_TEST_MISSING_BINDING="$missing_binding" \
    SMOKE_TEST_PROBE_STATUS="$probe_status" \
    SMOKE_TEST_LOG_CAPTURE_FAIL="$logs_fail" \
    bash "$ROOT_DIR/dev-tools/verify-smoke-images.sh" "${keep_args[@]}" >"$OUT_FILE" 2>"$ERR_FILE"
  actual_status=$?
  set -e

  if [[ "$actual_status" -ne "$expected_status" ]]; then
    echo "smoke diagnostics case '$suffix' returned $actual_status; expected $expected_status" >&2
    cat "$OUT_FILE" >&2
    cat "$ERR_FILE" >&2
    return 1
  fi
}

run_failure_diagnostic_regressions() {
  run_diagnostic_smoke_case cleanup-success false 0 false 0
  [[ ! -e "$TEST_OWNERSHIP_DIR/${TEST_RUN_ID}-cleanup-success.resources" ]]
  SMOKE_TEST_CLEANUP_FAIL=true run_diagnostic_smoke_case cleanup-failure false 0 false 1
  [[ -e "$TEST_OWNERSHIP_DIR/${TEST_RUN_ID}-cleanup-failure.resources" ]]
  grep -Fq 'proof cleanup failed or remains unknown' "$ERR_FILE"
  SMOKE_TEST_CLEANUP_FAIL=true run_diagnostic_smoke_case combined-failure false 37 false 37
  [[ -e "$TEST_OWNERSHIP_DIR/${TEST_RUN_ID}-combined-failure.resources" ]]
  run_diagnostic_smoke_case deliberate-keep false 0 false 0 keep
  [[ -e "$TEST_OWNERSHIP_DIR/${TEST_RUN_ID}-deliberate-keep.resources" ]]
  run_diagnostic_smoke_case probe-failure false 37 false 37
  for service in game-design-service game-session-service world-management-service; do
    if ! grep -Fq "MOCK_LOG service=$service safeDiagnostic=true" "$OUT_FILE"; then
      echo "failed smoke did not print bounded diagnostics for $service" >&2
      cat "$OUT_FILE" >&2
      cat "$ERR_FILE" >&2
      return 1
    fi
  done

  run_diagnostic_smoke_case probe-success false 0 false 0
  if grep -Fq 'MOCK_LOG service=' "$OUT_FILE" || grep -Fq 'Capturing bounded' "$ERR_FILE"; then
    echo "successful smoke unexpectedly printed failure diagnostics" >&2
    cat "$OUT_FILE" >&2
    cat "$ERR_FILE" >&2
    return 1
  fi
  if ! grep -Fq 'MOCK_WEBSOCKET_PROBE' "$OUT_FILE" || ! grep -Fq 'MOCK_TELNET_PROBE' "$OUT_FILE"; then
    echo "baseline image smoke did not preserve both positive transport probes" >&2
    cat "$OUT_FILE" >&2
    cat "$ERR_FILE" >&2
    return 1
  fi

  run_diagnostic_smoke_case held-success false 0 false 0 "" held
  if ! grep -Fq 'MOCK_HELD_ADMISSION_PROBE' "$OUT_FILE" ||
    ! grep -Fq 'MOCK_WEBSOCKET_PROBE' "$OUT_FILE" ||
    grep -Fq 'MOCK_TELNET_PROBE' "$OUT_FILE" ||
    ! grep -Fq 'public successful admission remains unproved' "$OUT_FILE"; then
    echo "held-admission image smoke did not retain truthful qualification and backend proof" >&2
    cat "$OUT_FILE" >&2
    cat "$ERR_FILE" >&2
    return 1
  fi

  SMOKE_TEST_HELD_ADMISSION_STATUS=37 run_diagnostic_smoke_case held-probe-failure false 0 false 37 "" held
  if [[ -e "$TEST_OWNERSHIP_DIR/${TEST_RUN_ID}-held-probe-failure.resources" ]]; then
    echo "held-admission probe failure did not clean up its run-owned Compose project" >&2
    cat "$OUT_FILE" >&2
    cat "$ERR_FILE" >&2
    return 1
  fi
  if grep -Fq 'MOCK_WEBSOCKET_PROBE' "$OUT_FILE"; then
    echo "held-admission image smoke continued to positive backend proof after a failed edge assertion" >&2
    cat "$OUT_FILE" >&2
    cat "$ERR_FILE" >&2
    return 1
  fi

  run_diagnostic_smoke_case held-gateway-override false 0 false 1 "" held http://localhost:8180
  grep -Fq 'requires the canonical localhost:8080 endpoint' "$ERR_FILE"
  if grep -Fq 'MOCK_HELD_ADMISSION_PROBE' "$OUT_FILE"; then
    echo "held-admission Gateway override reached the edge probe" >&2
    cat "$OUT_FILE" >&2
    cat "$ERR_FILE" >&2
    return 1
  fi
  run_diagnostic_smoke_case held-tcp-management-override false 0 false 1 "" held "" http://localhost:8189
  grep -Fq 'requires the canonical localhost:8089 endpoint' "$ERR_FILE"
  if grep -Fq 'MOCK_HELD_ADMISSION_PROBE' "$OUT_FILE"; then
    echo "held-admission TCP management override reached the edge probe" >&2
    cat "$OUT_FILE" >&2
    cat "$ERR_FILE" >&2
    return 1
  fi
  run_diagnostic_smoke_case held-telnet-host-override false 0 false 1 "" held "" "" 127.0.0.1
  grep -Fq 'requires the canonical localhost:2323 endpoint' "$ERR_FILE"
  if grep -Fq 'MOCK_HELD_ADMISSION_PROBE' "$OUT_FILE"; then
    echo "held-admission Telnet host override reached the edge probe" >&2
    cat "$OUT_FILE" >&2
    cat "$ERR_FILE" >&2
    return 1
  fi
  run_diagnostic_smoke_case held-telnet-port-override false 0 false 1 "" held "" "" "" 2324
  grep -Fq 'requires the canonical localhost:2323 endpoint' "$ERR_FILE"
  if grep -Fq 'MOCK_HELD_ADMISSION_PROBE' "$OUT_FILE"; then
    echo "held-admission Telnet port override reached the edge probe" >&2
    cat "$OUT_FILE" >&2
    cat "$ERR_FILE" >&2
    return 1
  fi

  for binding in gateway tcp-management tcp-telnet; do
    run_diagnostic_smoke_case \
      "held-unbound-$binding" false 0 false 1 "" held "" "" "" "" "$binding"
    if ! grep -Fq 'does not publish' "$ERR_FILE" || grep -Fq 'MOCK_HELD_ADMISSION_PROBE' "$OUT_FILE"; then
      echo "held-admission proof did not fail its run-owned $binding port attribution" >&2
      cat "$OUT_FILE" >&2
      cat "$ERR_FILE" >&2
      return 1
    fi
  done

  run_diagnostic_smoke_case config-only true 37 false 0
  if grep -Fq 'MOCK_LOG service=' "$OUT_FILE" || grep -Fq 'MOCK_WEBSOCKET_PROBE' "$OUT_FILE"; then
    echo "config-only smoke unexpectedly started the stack or printed failure diagnostics" >&2
    cat "$OUT_FILE" >&2
    cat "$ERR_FILE" >&2
    return 1
  fi

  run_diagnostic_smoke_case diagnostic-failure false 37 true 37
  if ! grep -Fq 'Could not capture run-owned smoke service logs.' "$ERR_FILE"; then
    echo "diagnostic command failure was not reported" >&2
    cat "$OUT_FILE" >&2
    cat "$ERR_FILE" >&2
    return 1
  fi
  for service in game-design-service game-session-service world-management-service; do
    if ! grep -Fq "MOCK_LOG service=$service safeDiagnostic=true" "$OUT_FILE"; then
      echo "diagnostic capture failure prevented attempting logs for $service" >&2
      cat "$OUT_FILE" >&2
      cat "$ERR_FILE" >&2
      return 1
    fi
  done
}

cleanup() {
  rm -f "$ERR_FILE" "$OUT_FILE"
  rm -rf -- "$TEST_OWNERSHIP_DIR"
  if [[ -n "$ROOT_ENV_BACKUP" && -f "$ROOT_ENV_BACKUP" ]]; then
    mv "$ROOT_ENV_BACKUP" "$ROOT_ENV_FILE"
  else
    rm -f "$ROOT_ENV_FILE"
  fi
  if [[ -n "$DOCKER_ENV_BACKUP" && -f "$DOCKER_ENV_BACKUP" ]]; then
    mv "$DOCKER_ENV_BACKUP" "$DOCKER_ENV_FILE"
  else
    rm -f "$DOCKER_ENV_FILE"
  fi
}
trap cleanup EXIT

if [[ -f "$ROOT_ENV_FILE" ]]; then
  ROOT_ENV_BACKUP="$(mktemp)"
  cp "$ROOT_ENV_FILE" "$ROOT_ENV_BACKUP"
fi
if [[ -f "$DOCKER_ENV_FILE" ]]; then
  DOCKER_ENV_BACKUP="$(mktemp)"
  cp "$DOCKER_ENV_FILE" "$DOCKER_ENV_BACKUP"
fi

cat >"$ROOT_ENV_FILE" <<'EOF'
FIREMUD_AUTH_JWT_SECRET=contract-override-jwt-secret
EOF

if [[ "$DIAGNOSTICS_ONLY" == true ]]; then
  run_failure_diagnostic_regressions
  echo "smoke image failure diagnostics contract checks passed"
  exit 0
fi

GITHUB_ACTIONS=true \
GITHUB_RUN_ID="$TEST_RUN_ID" \
GITHUB_RUN_ATTEMPT=1 \
GITHUB_JOB=smoke-image-env-contract \
COMPOSE_PROJECT_NAME="smoke-full-$TEST_RUN_ID-1" \
FIREMUD_SMOKE_TEST_MODE=1 \
FIREMUD_SMOKE_OWNERSHIP_DIR="$TEST_OWNERSHIP_DIR" \
SMOKE_IMAGE_TAG=contract-smoke-tag \
SMOKE_COMPOSE_CONFIG_ONLY=true \
bash "$ROOT_DIR/dev-tools/verify-smoke-images.sh" >"$OUT_FILE" 2>"$ERR_FILE"

if grep -q 'variable is not set' "$ERR_FILE"; then
  echo "verify-smoke-images left compose variables unresolved:" >&2
  cat "$ERR_FILE" >&2
  exit 1
fi

if grep -q 'Defaulting to a blank string' "$ERR_FILE"; then
  echo "verify-smoke-images detected and rejected blank-string Compose defaults:" >&2
  cat "$ERR_FILE" >&2
  exit 1
fi

GITHUB_ACTIONS=true \
GITHUB_RUN_ID="$TEST_RUN_ID" \
GITHUB_RUN_ATTEMPT=1 \
GITHUB_JOB=smoke-image-env-contract \
COMPOSE_PROJECT_NAME="smoke-full-$TEST_RUN_ID-1" \
FIREMUD_SMOKE_TEST_MODE=1 \
FIREMUD_SMOKE_OWNERSHIP_DIR="$TEST_OWNERSHIP_DIR" \
SMOKE_IMAGE_TAG=contract-smoke-tag \
SMOKE_IMAGE_LOCAL_ONLY=true \
SMOKE_COMPOSE_CONFIG_ONLY=true \
bash "$ROOT_DIR/dev-tools/verify-smoke-images.sh" >"$OUT_FILE" 2>"$ERR_FILE"

if grep -q 'Defaulting to a blank string' "$ERR_FILE"; then
  echo "local-only smoke configuration detected and rejected blank-string Compose defaults:" >&2
  cat "$ERR_FILE" >&2
  exit 1
fi

if SMOKE_IMAGE_TAG=contract-smoke-tag \
  SMOKE_MINIO_LOCAL_ONLY=true \
  SMOKE_COMPOSE_CONFIG_ONLY=true \
  bash "$ROOT_DIR/dev-tools/verify-smoke-images.sh" >"$OUT_FILE" 2>"$ERR_FILE"; then
  echo "PR-local MinIO smoke accepted missing built image references and IDs" >&2
  exit 1
fi

if ! grep -Fq 'SMOKE_MINIO_LOCAL_ONLY=true requires a unique local server image tag.' "$ERR_FILE"; then
  echo "PR-local MinIO smoke did not reject the missing server image tag with the expected error" >&2
  cat "$ERR_FILE" >&2
  exit 1
fi

if ! GITHUB_ACTIONS=true \
  GITHUB_RUN_ID="$TEST_RUN_ID" \
  GITHUB_RUN_ATTEMPT=1 \
  GITHUB_JOB=smoke-image-env-contract \
  COMPOSE_PROJECT_NAME="smoke-full-$TEST_RUN_ID-1" \
  FIREMUD_SMOKE_TEST_MODE=1 \
  FIREMUD_SMOKE_OWNERSHIP_DIR="$TEST_OWNERSHIP_DIR" \
  SMOKE_IMAGE_TAG=contract-smoke-tag \
  SMOKE_MINIO_LOCAL_ONLY=true \
  SMOKE_MINIO_SERVER_IMAGE=firemud-minio-server-smoke:contract \
  SMOKE_MINIO_CLIENT_IMAGE=firemud-minio-client-smoke:contract \
  SMOKE_MINIO_SERVER_IMAGE_ID=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  SMOKE_MINIO_CLIENT_IMAGE_ID=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb \
  SMOKE_COMPOSE_CONFIG_ONLY=true \
  bash "$ROOT_DIR/dev-tools/verify-smoke-images.sh" >"$OUT_FILE" 2>"$ERR_FILE"; then
  echo "PR-local MinIO smoke positive path failed:" >&2
  cat "$OUT_FILE" >&2
  cat "$ERR_FILE" >&2
  exit 1
fi

if ! grep -q 'Verified PR-local MinIO Compose image tags and pull policies.' "$OUT_FILE"; then
  echo "PR-local MinIO smoke did not prove the rendered image tags and no-pull policy" >&2
  cat "$OUT_FILE" >&2
  cat "$ERR_FILE" >&2
  exit 1
fi

if grep -q -- '--pull never' "$ROOT_DIR/dev-tools/verify-smoke-images.sh"; then
  echo "local-only smoke must not suppress pulls for external dependency images" >&2
  exit 1
fi

# shellcheck disable=SC2016 # Match the literal Compose interpolation contract.
if [[ "$(grep -Fc 'pull_policy: ${SMOKE_IMAGE_PULL_POLICY:-always}' "$ROOT_DIR/docker/docker-compose.smoke-images.override.yml")" -ne 11 ]]; then
  echo "every FireMUD smoke image must use the configurable per-service pull policy" >&2
  exit 1
fi

run_failure_diagnostic_regressions

echo "smoke image env contract checks passed"
