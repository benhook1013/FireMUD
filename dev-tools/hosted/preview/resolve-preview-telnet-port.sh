#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 2 ]]; then
  echo "usage: $0 <private|public> <allocated-telnet-port>" >&2
  exit 2
fi

exposure_mode="$1"
allocated_telnet_port="$2"

case "$exposure_mode" in
  private)
    printf '0\n'
    ;;
  public)
    if [[ ! "$allocated_telnet_port" =~ ^32(00[0-9]|01[0-5])$ ]]; then
      echo "::error title=Invalid allocated Telnet port::Expected a port from 32000 through 32015; actual value was ${allocated_telnet_port:-empty}." >&2
      exit 1
    fi
    printf '%s\n' "$allocated_telnet_port"
    ;;
  *)
    echo "::error title=Invalid preview exposure mode::Expected private or public; actual ${exposure_mode:-empty}." >&2
    exit 1
    ;;
esac
