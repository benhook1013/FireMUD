#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || -z "$1" ]]; then
  echo "usage: $0 <backup-verifier-image>" >&2
  exit 1
fi

image="$1"
docker run --rm --entrypoint /bin/bash "$image" -ceu '
  [[ "$(id -u)" != 0 ]] || { echo "backup verifier image must run as non-root" >&2; exit 1; }
  command -v bash >/dev/null
  command -v aws >/dev/null
  command -v velero >/dev/null
  velero_version="$(velero version --client-only)"
  [[ -n "$velero_version" ]] || { echo "Velero client version output was empty" >&2; exit 1; }
  aws_version="$(aws --version 2>&1)"
  [[ -n "$aws_version" ]] || { echo "AWS CLI version output was empty" >&2; exit 1; }
  test -r /opt/firemud/backups/verify-backups.sh
  test -r /opt/firemud/backups/pg-dump-s3-selection.shlib
  bash -n /opt/firemud/backups/verify-backups.sh
  bash -n /opt/firemud/backups/pg-dump-s3-selection.shlib
  echo "backup verifier image smoke passed as uid $(id -u)"
'
