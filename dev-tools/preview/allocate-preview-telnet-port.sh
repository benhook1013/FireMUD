#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <namespace> <pr_number>" >&2
  exit 1
fi

namespace="$1"
pr_number="$2"
min_port=30000
max_port=32767
port_span=$((max_port - min_port + 1))

existing_port="$(
  kubectl get namespace "$namespace" -o jsonpath='{.metadata.annotations.firemud\.dev/last-preview-telnet-port}' 2>/dev/null || true
)"

if [[ "$existing_port" =~ ^[0-9]+$ ]] && (( existing_port >= min_port && existing_port <= max_port )); then
  echo "$existing_port"
  exit 0
fi

declare -A used_ports=()
while IFS=$'\t' read -r existing_namespace port; do
  if [[ -z "$port" || "$existing_namespace" == "$namespace" ]]; then
    continue
  fi
  used_ports["$port"]=1
done < <(
  kubectl get namespaces -l firemud.dev/preview=true \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.metadata.annotations.firemud\.dev/last-preview-telnet-port}{"\n"}{end}'
)

start_offset=$((pr_number % port_span))
for ((i = 0; i < port_span; i++)); do
  candidate=$((min_port + ((start_offset + i) % port_span)))
  if [[ -z "${used_ports[$candidate]:-}" ]]; then
    echo "$candidate"
    exit 0
  fi
done

echo "No free preview telnet NodePort available in ${min_port}-${max_port}" >&2
exit 1
