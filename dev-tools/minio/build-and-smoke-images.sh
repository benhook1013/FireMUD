#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 2 || -z "$1" || -z "$2" ]]; then
  echo "usage: $0 <server-image> <client-image>" >&2
  exit 2
fi

server_image="$1"
client_image="$2"
workspace="${GITHUB_WORKSPACE:-$(git rev-parse --show-toplevel)}"
temp_root="$(mktemp -d "${RUNNER_TEMP:-/tmp}/firemud-minio-source-build.XXXXXX")"
network="minio-source-smoke-${RANDOM}-${RANDOM}"
server_container="minio-source-server"
cleanup() {
  docker rm --force "$server_container" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
  rm -rf -- "$temp_root"
}
trap cleanup EXIT

resolve_source() {
  local name="$1" repository="$2" tag="$3" expected_tag_object="$4" expected_commit="$5"
  local destination="$temp_root/$name" refs actual_tag_object actual_commit

  refs="$(git ls-remote "$repository" "refs/tags/$tag" "refs/tags/$tag^{}")"
  actual_tag_object="$(awk -v ref="refs/tags/$tag" '$2 == ref {print $1}' <<< "$refs")"
  actual_commit="$(awk -v ref="refs/tags/$tag^{}" '$2 == ref {print $1}' <<< "$refs")"
  [[ "$actual_tag_object" == "$expected_tag_object" ]] || {
    echo "$name upstream tag object changed: expected $expected_tag_object, got ${actual_tag_object:-missing}." >&2
    return 1
  }
  [[ "$actual_commit" == "$expected_commit" ]] || {
    echo "$name upstream tag no longer resolves to the pinned source commit." >&2
    return 1
  }

  git clone --quiet --filter=blob:none --depth=1 --branch "$tag" "$repository" "$destination"
  [[ "$(git -C "$destination" rev-parse "refs/tags/$tag")" == "$expected_tag_object" ]] || {
    echo "$name cloned tag object did not match the pinned tag object." >&2
    return 1
  }
  [[ "$(git -C "$destination" rev-parse "refs/tags/$tag^{commit}")" == "$expected_commit" ]] || {
    echo "$name cloned tag did not resolve to the pinned source commit." >&2
    return 1
  }
  [[ "$(git -C "$destination" rev-parse HEAD)" == "$expected_commit" ]] || {
    echo "$name shallow clone did not land on the pinned source commit." >&2
    return 1
  }
}

resolve_source minio https://github.com/minio/minio.git RELEASE.2024-05-10T01-41-38Z \
  0c39917fe562b3c43c77e72327bc4351857c0050 b5984027386ec1e55c504d27f42ef40a189cdb55
resolve_source mc https://github.com/minio/mc.git RELEASE.2024-05-09T17-04-24Z \
  835afd0a86e9a3ed396b8162eabc98243c342e5f fdb36acbb1d793b6cca622a55e6292f0d52309f0

DOCKER_BUILDKIT=1 docker build --pull=false \
  --file "$workspace/docker/minio/server.Dockerfile" --tag "$server_image" "$temp_root/minio"
DOCKER_BUILDKIT=1 docker build --pull=false \
  --file "$workspace/docker/minio/client.Dockerfile" --tag "$client_image" "$temp_root/mc"

docker network create "$network" >/dev/null
docker run --detach --name "$server_container" --network "$network" \
  --env MINIO_ROOT_USER=smoke-access-key \
  --env MINIO_ROOT_PASSWORD=smoke-secret-key-123456789 "$server_image" >/dev/null

ready=false
attempt=0
while ((attempt < 30)); do
  attempt=$((attempt + 1))
  if docker exec "$server_container" busybox wget -q -T 2 -O /dev/null \
    http://127.0.0.1:9000/minio/health/live; then
    ready=true
    break
  fi
  sleep 2
done
if [[ "$ready" != true ]]; then
  docker logs "$server_container" >&2 || true
  echo "MinIO source-built server did not become healthy within 60 seconds." >&2
  exit 1
fi

if ! docker run --rm --network "$network" --entrypoint /bin/sh \
  --env MINIO_ROOT_USER=smoke-access-key \
  --env MINIO_ROOT_PASSWORD=smoke-secret-key-123456789 "$client_image" \
  -c 'set -eu
    mc alias set local http://minio-source-server:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
    mc mb --ignore-existing local/firemud-assets
    mc anonymous set private local/firemud-assets
    anonymous_response="$(busybox wget -S -T 3 -O /dev/null http://minio-source-server:9000/firemud-assets/ 2>&1)" && {
      echo "Anonymous listing unexpectedly succeeded for the private bucket." >&2
      exit 1
    }
    printf "%s\n" "$anonymous_response" | grep -Eq "HTTP/[0-9.]+ 403"'; then
  echo "MinIO client smoke failed." >&2
  docker logs "$server_container" >&2 || true
  exit 1
fi

server_image_id="$(docker image inspect --format '{{.Id}}' "$server_image")"
client_image_id="$(docker image inspect --format '{{.Id}}' "$client_image")"
[[ "$server_image_id" =~ ^sha256:[0-9a-f]{64}$ ]]
[[ "$client_image_id" =~ ^sha256:[0-9a-f]{64}$ ]]
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    printf 'server_image_id=%s\n' "$server_image_id"
    printf 'client_image_id=%s\n' "$client_image_id"
  } >> "$GITHUB_OUTPUT"
fi

if [[ -n "${RUNNER_TEMP:-}" ]]; then
  artifact_dir="$RUNNER_TEMP/minio-image-artifact"
  mkdir -p "$artifact_dir"
  docker save "$server_image" "$client_image" | gzip -1 > "$artifact_dir/images.tar.gz"
  python3 - "$artifact_dir/provenance.json" "$server_image" "$client_image" "$server_image_id" "$client_image_id" <<'PY'
import json
import os
import sys
from pathlib import Path

output, server_image, client_image, server_image_id, client_image_id = sys.argv[1:]
payload = {
    "repository": os.environ["GITHUB_REPOSITORY"],
    "sourceEvent": os.environ["GITHUB_EVENT_NAME"],
    "sourceRunId": int(os.environ["GITHUB_RUN_ID"]),
    "sourceRunAttempt": int(os.environ["GITHUB_RUN_ATTEMPT"]),
    "sourceHeadSha": os.environ["GITHUB_SHA"],
    "server": {"image": server_image, "imageId": server_image_id,
               "tagObject": "0c39917fe562b3c43c77e72327bc4351857c0050",
               "sourceCommit": "b5984027386ec1e55c504d27f42ef40a189cdb55"},
    "client": {"image": client_image, "imageId": client_image_id,
               "tagObject": "835afd0a86e9a3ed396b8162eabc98243c342e5f",
               "sourceCommit": "fdb36acbb1d793b6cca622a55e6292f0d52309f0"},
    "builder": "golang:1.21.13-bookworm@sha256:c6a5b9308b3f3095e8fde83c8bf4d68bd101fce606c1a0a1394522542509dda9",
    "runtime": "alpine:3.22.1@sha256:4bcff63911fcb4448bd4fdacec207030997caf25e9bea4045fa6c8c44de311d1",
}
Path(output).write_text(json.dumps(payload, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
PY
fi
