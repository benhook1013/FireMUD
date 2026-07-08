#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT_ENV_FILE="$ROOT_DIR/.env"
ROOT_ENV_SAMPLE="$ROOT_DIR/.env.sample"
DOCKER_ENV_FILE="$ROOT_DIR/docker/.env"

if [[ ! -f "$ROOT_ENV_FILE" ]]; then
  if [[ ! -f "$ROOT_ENV_SAMPLE" ]]; then
    echo ".env is missing and .env.sample was not found." >&2
    exit 1
  fi
  cp "$ROOT_ENV_SAMPLE" "$ROOT_ENV_FILE"
  echo "Seeded local .env from .env.sample" >&2
elif [[ -f "$ROOT_ENV_SAMPLE" ]]; then
  missing_keys_file="$(mktemp)"
  trap 'rm -f "$missing_keys_file"' EXIT
  while IFS= read -r line || [[ -n "$line" ]]; do
    if [[ -z "${line//[[:space:]]/}" || "$line" =~ ^[[:space:]]*# ]]; then
      continue
    fi
    key="${line%%=*}"
    if ! grep -Eq "^[[:space:]]*${key}=" "$ROOT_ENV_FILE"; then
      printf '%s\n' "$line" >>"$missing_keys_file"
    fi
  done <"$ROOT_ENV_SAMPLE"
  if [[ -s "$missing_keys_file" ]]; then
    printf '\n' >>"$ROOT_ENV_FILE"
    cat "$missing_keys_file" >>"$ROOT_ENV_FILE"
    echo "Appended missing local .env keys from .env.sample" >&2
  fi
fi

cp "$ROOT_ENV_FILE" "$DOCKER_ENV_FILE"
echo "Synchronized docker/.env from repository-root .env" >&2
