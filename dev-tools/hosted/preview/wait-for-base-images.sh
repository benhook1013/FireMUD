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

workflow_file=".github/workflows/docker-images.yml"
if [[ ! -f "$workflow_file" ]]; then
  echo "required base-image workflow is missing: $workflow_file" >&2
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
    print(f"unable to parse base-image workflow {workflow_path}: {exc}", file=sys.stderr)
    raise SystemExit(1)

if not isinstance(workflow, dict):
    print(f"base-image workflow must be a YAML mapping: {workflow_path}", file=sys.stderr)
    raise SystemExit(1)
jobs = workflow.get("jobs")
docker_build = jobs.get("docker-build") if isinstance(jobs, dict) else None
strategy = docker_build.get("strategy") if isinstance(docker_build, dict) else None
matrix = strategy.get("matrix") if isinstance(strategy, dict) else None
services = matrix.get("service") if isinstance(matrix, dict) else None
if not isinstance(services, list) or not services or any(not isinstance(service, str) for service in services):
    print(
        "required base-image service matrix must be a non-empty list of strings: "
        f"{workflow_path}",
        file=sys.stderr,
    )
    raise SystemExit(1)

for service in services:
    print(service)
PY
)

if ((${#services[@]} == 0)); then
  echo "required base-image service matrix is missing: $workflow_file" >&2
  exit 1
fi

declare -A seen_services=()
for service in "${services[@]}"; do
  if [[ ! "$service" =~ ^[a-z0-9][a-z0-9-]*$ ]]; then
    echo "invalid base-image service in $workflow_file: $service" >&2
    exit 1
  fi
  if [[ -n "${seen_services[$service]:-}" ]]; then
    echo "duplicate base-image service in $workflow_file: $service" >&2
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
if [[ ! "$registry_probe_timeout_seconds" =~ ^[1-9][0-9]*$ ]]; then
  echo "base-image registry probe timeout must be a positive integer" >&2
  exit 1
fi

start_epoch="${SECONDS}"
deadline=$((SECONDS + timeout_seconds))
while :; do
  missing_services=()
  for service in "${services[@]}"; do
    image="ghcr.io/benhook1013/${service}:${base_sha}"
    if ! timeout --signal=KILL "${registry_probe_timeout_seconds}s" \
      docker manifest inspect "$image" >/dev/null 2>&1; then
      missing_services+=("$service")
    fi
  done

  if ((${#missing_services[@]} == 0)); then
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
  sleep "$sleep_seconds"
done
