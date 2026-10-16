#!/usr/bin/env bash
set -euo pipefail

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  echo "Held-admission qualification must be invoked by sourcing it from the run-owned smoke wrapper." >&2
  exit 2
fi

HELD_ADMISSION_REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

run_held_admission_qualification() {
  local gateway_api_base="${SMOKE_GATEWAY_API_BASE:-http://localhost:8080}"
  local tcp_proxy_api_base="${SMOKE_TCP_PROXY_API_BASE:-http://localhost:8089}"
  local telnet_host="${SMOKE_TELNET_HOST:-localhost}"
  local telnet_port="${TCP_PROXY_PORT:-2323}"

  if [[ "${FIREMUD_SMOKE_ADMISSION_QUALIFICATION:-}" != "held" ]]; then
    echo "Set FIREMUD_SMOKE_ADMISSION_QUALIFICATION=held to run this qualification." >&2
    return 1
  fi
  if [[ "$gateway_api_base" != "http://localhost:8080" ]]; then
    echo "Held-admission Gateway probe requires the canonical localhost:8080 endpoint." >&2
    return 1
  fi
  if [[ "$tcp_proxy_api_base" != "http://localhost:8089" ]]; then
    echo "Held-admission TCP Proxy probe requires the canonical localhost:8089 endpoint." >&2
    return 1
  fi
  if [[ "$telnet_host" != "localhost" || "$telnet_port" != "2323" ]]; then
    echo "Held-admission Telnet probe requires the canonical localhost:2323 endpoint." >&2
    return 1
  fi
  if ! declare -F require_run_owned_compose_service >/dev/null; then
    echo "Held-admission qualification requires the run-owned Compose service guard." >&2
    return 1
  fi

  require_run_owned_compose_service gateway 8080 8080 || return 1
  require_run_owned_compose_service tcp-proxy-service 8080 8089 || return 1
  require_run_owned_compose_service tcp-proxy-service 2323 2323 || return 1

  export SMOKE_GATEWAY_API_BASE="$gateway_api_base"
  export SMOKE_TCP_PROXY_API_BASE="$tcp_proxy_api_base"
  export SMOKE_TELNET_HOST="$telnet_host"
  export TCP_PROXY_PORT="$telnet_port"
  PYTHONPATH="$HELD_ADMISSION_REPO_ROOT/dev-tools/smoke${PYTHONPATH:+:$PYTHONPATH}" \
    python3 - "$@" <<'PY'
import sys

from held_admission_qualification import run_qualification

raise SystemExit(run_qualification(sys.argv[1:]))
PY
}
