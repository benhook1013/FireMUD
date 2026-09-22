#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <base_sha>" >&2
  exit 1
fi

base_sha="$1"
if [[ ! "$base_sha" =~ ^[0-9A-Fa-f]{40}$ ]]; then
  echo "base image tag must be a 40-character commit SHA" >&2
  exit 1
fi

workflow_file=".github/workflows/runtime-images.yml"
if [[ ! -f "$workflow_file" ]]; then
  echo "required runtime-image workflow is missing: $workflow_file" >&2
  exit 1
fi

mapfile -t services < <(
  python3 - "$workflow_file" <<'PY'
import sys
from pathlib import Path

import yaml

workflow_path = Path(sys.argv[1])
try:
    workflow = yaml.safe_load(workflow_path.read_text(encoding="utf-8"))
except (OSError, yaml.YAMLError) as exc:
    print(f"unable to parse runtime-image workflow {workflow_path}: {exc}", file=sys.stderr)
    raise SystemExit(1)

if not isinstance(workflow, dict):
    print(f"runtime-image workflow must be a YAML mapping: {workflow_path}", file=sys.stderr)
    raise SystemExit(1)
jobs = workflow.get("jobs")
runtime_build = jobs.get("build-runtime-images") if isinstance(jobs, dict) else None
strategy = runtime_build.get("strategy") if isinstance(runtime_build, dict) else None
matrix = strategy.get("matrix") if isinstance(strategy, dict) else None
services = matrix.get("service") if isinstance(matrix, dict) else None
if not isinstance(services, list) or not services or any(not isinstance(service, str) for service in services):
    print(
        "required runtime-image service matrix must be a non-empty list of strings: "
        f"{workflow_path}",
        file=sys.stderr,
    )
    raise SystemExit(1)

for service in services:
    print(service)
PY
)

if ((${#services[@]} == 0)); then
  echo "required runtime-image service matrix is missing: $workflow_file" >&2
  exit 1
fi

declare -A seen_services=()
for service in "${services[@]}"; do
  if [[ ! "$service" =~ ^[a-z0-9][a-z0-9-]*$ ]]; then
    echo "invalid runtime-image service in $workflow_file: $service" >&2
    exit 1
  fi
  if [[ -n "${seen_services[$service]:-}" ]]; then
    echo "duplicate runtime-image service in $workflow_file: $service" >&2
    exit 1
  fi
  seen_services["$service"]=1
done

timeout_seconds="${HOSTED_BASE_IMAGE_WAIT_TIMEOUT_SECONDS:-${PREVIEW_BASE_IMAGE_WAIT_TIMEOUT_SECONDS:-1800}}"
sleep_seconds="${HOSTED_BASE_IMAGE_WAIT_SLEEP_SECONDS:-${PREVIEW_BASE_IMAGE_WAIT_SLEEP_SECONDS:-10}}"
registry_probe_timeout_seconds="${HOSTED_BASE_IMAGE_PROBE_TIMEOUT_SECONDS:-${PREVIEW_BASE_IMAGE_PROBE_TIMEOUT_SECONDS:-30}}"
if [[ ! "$timeout_seconds" =~ ^[0-9]+$ ]] || [[ ! "$sleep_seconds" =~ ^[0-9]+$ ]]; then
  echo "base-image wait timeout and sleep values must be non-negative integers" >&2
  exit 1
fi
if [[ ! "$registry_probe_timeout_seconds" =~ ^[0-9]+$ ]]; then
  echo "base-image registry probe timeout must be a positive integer" >&2
  exit 1
fi
timeout_seconds=$((10#$timeout_seconds))
sleep_seconds=$((10#$sleep_seconds))
registry_probe_timeout_seconds=$((10#$registry_probe_timeout_seconds))
if ((sleep_seconds == 0)); then
  echo "base-image wait sleep value must be a positive integer" >&2
  exit 1
fi
if ((registry_probe_timeout_seconds == 0)); then
  echo "base-image registry probe timeout must be a positive integer" >&2
  exit 1
fi

if ((timeout_seconds == 0)); then
  printf 'Timed out waiting for base runtime images for %s after 0s; missing services: %s\n' \
    "$base_sha" "${services[*]}" >&2
  exit 1
fi

start_epoch="${SECONDS}"
deadline=$((start_epoch + timeout_seconds))
while :; do
  missing_services=()
  sweep_completed=true
  for ((service_index = 0; service_index < ${#services[@]}; service_index++)); do
    service="${services[$service_index]}"
    image="ghcr.io/benhook1013/${service}:${base_sha}"
    remaining_seconds=$((deadline - SECONDS))
    if ((remaining_seconds <= 0)); then
      sweep_completed=false
      for ((remaining_index = service_index; remaining_index < ${#services[@]}; remaining_index++)); do
        missing_services+=("${services[$remaining_index]}")
      done
      break
    fi
    probe_timeout_seconds="$registry_probe_timeout_seconds"
    if ((probe_timeout_seconds > remaining_seconds)); then
      probe_timeout_seconds="$remaining_seconds"
    fi
    if ! timeout --signal=KILL "${probe_timeout_seconds}s" \
      docker manifest inspect "$image" >/dev/null 2>&1; then
      missing_services+=("$service")
    fi
  done

  if [[ "$sweep_completed" == true ]] && ((${#missing_services[@]} == 0)); then
    printf 'All %s base runtime images are available for %s after %ss.\n' \
      "${#services[@]}" "$base_sha" "$((SECONDS - start_epoch))"
    exit 0
  fi

  if ((SECONDS >= deadline)); then
    printf 'Timed out waiting for base runtime images for %s after %ss; missing services: %s\n' \
      "$base_sha" "$((SECONDS - start_epoch))" "${missing_services[*]}" >&2
    exit 1
  fi

  printf 'Waiting for base runtime images for %s after %ss; missing services: %s\n' \
    "$base_sha" "$((SECONDS - start_epoch))" "${missing_services[*]}"
  remaining_seconds=$((deadline - SECONDS))
  if ((remaining_seconds <= 0)); then
    printf 'Timed out waiting for base runtime images for %s after %ss; missing services: %s\n' \
      "$base_sha" "$((SECONDS - start_epoch))" "${missing_services[*]}" >&2
    exit 1
  fi
  sleep_duration="$sleep_seconds"
  if ((sleep_duration > remaining_seconds)); then
    sleep_duration="$remaining_seconds"
  fi
  sleep "$sleep_duration"
done
