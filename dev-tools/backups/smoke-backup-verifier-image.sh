#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 || -z "$1" || -z "$2" ]]; then
  echo "usage: $0 <backup-verifier-image> <velero-version>" >&2
  exit 1
fi

image="$1"
expected_velero_version="$2"
[[ "$expected_velero_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
  echo "expected Velero version must be an exact three-part version" >&2
  exit 1
}

docker run --rm --entrypoint /bin/bash \
  --env "EXPECTED_VELERO_VERSION=$expected_velero_version" \
  "$image" -ceu '
  [[ "$(id -u)" != 0 ]] || { echo "backup verifier image must run as non-root" >&2; exit 1; }
  command -v bash >/dev/null
  command -v aws >/dev/null
  command -v velero >/dev/null
  expected_velero_version="${EXPECTED_VELERO_VERSION:?}"
  velero_version_output="$(velero version --client-only)"
  [[ -n "${velero_version_output//[[:space:]]/}" ]] || {
    echo "Velero client version output was empty" >&2
    exit 1
  }
  grep -Eq "^[[:space:]]*Version:[[:space:]]*v${expected_velero_version}[[:space:]]*$" <<<"$velero_version_output" || {
    echo "Velero client version mismatch: expected v${expected_velero_version}." >&2
    exit 1
  }
  aws_version="$(aws --version 2>&1)"
  [[ -n "$aws_version" ]] || { echo "AWS CLI version output was empty" >&2; exit 1; }
  test -r /opt/firemud/backups/verify-backups.sh
  test -r /opt/firemud/backups/pg-dump-s3-selection.shlib
  bash -n /opt/firemud/backups/verify-backups.sh
  bash -n /opt/firemud/backups/pg-dump-s3-selection.shlib
  echo "backup verifier image smoke passed as uid $(id -u)"
'
