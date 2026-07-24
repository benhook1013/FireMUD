#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

if ! command -v yamllint >/dev/null 2>&1; then
  echo "yamllint is required. Install it with: python3 -m pip install --user yamllint" >&2
  exit 1
fi

mapfile -t yaml_files < <(
  git ls-files \
    '.github/workflows/*.yml' \
    'docker/*.yml' \
    'services/*/src/main/resources/*.yml' \
    'services/*/src/main/resources/*.yaml' \
    'services/*/src/test/resources/*.yml' \
    'services/*/src/test/resources/*.yaml' \
    'design/architecture/*.yml' \
    'design/architecture/*.yaml' \
    'design/architecture/**/*.yml' \
    'design/architecture/**/*.yaml' \
    'design/operations/**/*.yml' \
    'design/operations/**/*.yaml' \
    'k8s/**/*.yml' \
    'k8s/**/*.yaml' \
    ':!:k8s/base/**' \
    ':!:k8s/minio/**' \
    ':!:k8s/monitoring/**' \
    ':!:k8s/network-policies/**' \
    ':!:k8s/postgres/**' \
    ':!:k8s/preview/cluster-issuers.yaml' \
    ':!:k8s/preview/preview-deployer-rbac.yaml' \
    ':!:k8s/velero/minio.yaml' \
    ':!:k8s/velero/schedule.yaml' \
    ':!:k8s/velero/verify-backups-cronjob.yaml' \
    ':!:k8s/helm/**/templates/**'
)

existing_yaml_files=()
for yaml_file in "${yaml_files[@]}"; do
  if [ -f "$yaml_file" ]; then
    existing_yaml_files+=("$yaml_file")
  fi
done
yaml_files=("${existing_yaml_files[@]}")

if [ "${#yaml_files[@]}" -eq 0 ]; then
  echo "No tracked YAML files matched lint scope."
  exit 0
fi

yamllint -c config/yamllint/config.yaml "${yaml_files[@]}"
