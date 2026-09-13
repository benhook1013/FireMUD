#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 ]]; then
  echo "usage: $0 <runtime_namespace> <head_sha> <image_tag> <telnet_port>" >&2
  exit 1
fi

runtime_namespace="$1"
head_sha="$2"
image_tag="$3"
telnet_port="$4"

if [[ "$runtime_namespace" != dev ]]; then
  echo "::error title=Invalid dev-demo runtime namespace::Expected the canonical dev namespace." >&2
  exit 1
fi
if [[ ! "$head_sha" =~ ^[0-9a-f]{40}$ ]]; then
  echo "::error title=Invalid dev-demo head SHA::Expected exactly 40 lowercase hexadecimal characters." >&2
  exit 1
fi
if [[ -z "$image_tag" || ! "$image_tag" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]]; then
  echo "::error title=Invalid dev-demo image tag::Expected a non-empty tag of at most 128 safe characters." >&2
  exit 1
fi
if [[ "$telnet_port" != 32016 ]]; then
  echo "::error title=Invalid dev-demo Telnet port::Expected the canonical 32016 port." >&2
  exit 1
fi
