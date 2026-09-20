#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 1 || -z "$1" ]]; then
  echo "usage: $0 <image>" >&2
  exit 2
fi

image="$1"
max_push_attempts=3
push_attempt=1
while true; do
  push_output=""
  if push_output="$(docker push "$image" 2>&1)"; then
    printf '%s\n' "$push_output"
    break
  fi
  printf '%s\n' "$push_output"
  if ((push_attempt >= max_push_attempts)); then
    echo "Failed to publish $image after ${push_attempt} attempts." >&2
    exit 1
  fi
  backoff_seconds=$((5 * 2 ** (push_attempt - 1)))
  echo "Transient push failure for $image; retrying in ${backoff_seconds}s (attempt $((push_attempt + 1))/${max_push_attempts})." >&2
  sleep "$backoff_seconds"
  push_attempt=$((push_attempt + 1))
done

pushed_digests=()
while IFS= read -r output_line; do
  if [[ "$output_line" =~ ^[^[:space:]]+:[[:space:]]digest:[[:space:]](sha256:[0-9a-f]{64})[[:space:]]size:[[:space:]][0-9]+$ ]]; then
    pushed_digests+=("${BASH_REMATCH[1]}")
  fi
done <<< "$push_output"
if ((${#pushed_digests[@]} != 1)); then
  echo "Successful push did not report exactly one exact sha256 digest for $image." >&2
  exit 1
fi
pushed_digest="${pushed_digests[0]}"
[[ "$pushed_digest" =~ ^sha256:[0-9a-f]{64}$ ]] || {
  echo "Successful push did not return an exact sha256 digest for $image." >&2
  exit 1
}
echo "digest=$pushed_digest" >> "${GITHUB_OUTPUT:?GITHUB_OUTPUT must point to a step output file}"
