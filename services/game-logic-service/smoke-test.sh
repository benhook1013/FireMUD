#!/usr/bin/env bash
# Simple smoke test for Game Logic Service REST and gRPC endpoints
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

echo "Checking REST /command"
resp=$(curl -fsSL -X POST "$HTTP_URL/command" -d "say hello")
if ! echo "$resp" | grep -q 'hello'; then
  echo "REST command failed" >&2
  exit 1
fi

echo "Checking gRPC Ping"
resp=$(grpcurl -plaintext "$GRPC_ADDR" game_logic.v1.GameLogicService/Ping)
if ! echo "$resp" | grep -q 'pong'; then
  echo "gRPC ping failed" >&2
  exit 1
fi

echo "Checking gRPC ExecuteCommand"
resp=$(grpcurl -plaintext -d '{"tenant_id":"demo","session_id":"demo","command":"north"}' "$GRPC_ADDR" game_logic.v1.GameLogicService/ExecuteCommand)
if ! echo "$resp" | grep -q 'You move'; then
  echo "gRPC command failed" >&2
  exit 1
fi

echo "Smoke tests passed"
