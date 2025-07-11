#!/usr/bin/env bash
# Simple smoke test for TCP Proxy Service REST and gRPC endpoints
set -euo pipefail

HTTP_URL=${HTTP_URL:-http://localhost:8080}
GRPC_ADDR=${GRPC_ADDR:-localhost:6565}

function require_cmd() {
  command -v "$1" >/dev/null 2>&1 || { echo >&2 "Required command '$1' not found"; exit 1; }
}

require_cmd curl
require_cmd grpcurl

echo "Checking REST /ping"
resp=$(curl -fsSL "$HTTP_URL/ping")
if ! echo "$resp" | grep -q '"SUCCESS"'; then
  echo "REST ping failed" >&2
  exit 1
fi

echo "Checking gRPC Ping"
resp=$(grpcurl -plaintext "$GRPC_ADDR" tcp_proxy.v1.TcpProxyService/Ping)
if ! echo "$resp" | grep -q 'pong'; then
  echo "gRPC ping failed" >&2
  exit 1
fi

echo "Smoke tests passed"
