#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <target_namespace> <max_active>" >&2
  exit 1
fi

target_namespace="$1"
max_active="$2"

if ! [[ "$max_active" =~ ^[0-9]+$ ]]; then
  echo "max_active must be an integer, got: $max_active" >&2
  exit 1
fi

mapfile -t namespaces < <(
  kubectl get namespaces -l firemud.dev/preview=true -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' \
    | sed '/^$/d'
)

active_count=0
for namespace in "${namespaces[@]}"; do
  if [[ "$namespace" != "$target_namespace" ]]; then
    active_count=$((active_count + 1))
  fi
done

echo "Active preview namespaces excluding ${target_namespace}: ${active_count}"
echo "Configured preview capacity limit: ${max_active}"

if (( active_count >= max_active )); then
  echo "Preview capacity exhausted: ${active_count} active preview(s), limit ${max_active}" >&2
  exit 1
fi
