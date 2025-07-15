#!/usr/bin/env bash
# wait-for-it.sh -- wait for a host and port to be available
# Usage: wait-for-it.sh host port [cmd...]
set -e
HOST="$1"
PORT="$2"
shift 2
TIMEOUT=${WAITFORIT_TIMEOUT:-30}

for i in $(seq "$TIMEOUT"); do
  if bash -c "</dev/tcp/$HOST/$PORT" >/dev/null 2>&1; then
    if [ "$#" -gt 0 ]; then
      exec "$@"
    fi
    exit 0
  fi
  sleep 1
  echo "Waiting for $HOST:$PORT... $((TIMEOUT - i))s remaining" >&2

done
echo "Timeout waiting for $HOST:$PORT" >&2
exit 1

