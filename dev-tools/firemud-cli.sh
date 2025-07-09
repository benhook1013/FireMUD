#!/usr/bin/env bash
# Simple CLI for local FireMUD management
set -e

function usage() {
  echo "Usage: $0 {up|down|ping}" >&2
  exit 1
}

case "$1" in
  up)
    ./gradlew devUp
    ;;
  down)
    ./gradlew devDown
    ;;
  ping)
    curl -fsSL http://localhost:8080/ping
    ;;
  *)
    usage
    ;;
esac
