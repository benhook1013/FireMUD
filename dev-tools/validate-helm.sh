#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

usage() {
  cat <<'EOF'
Usage: dev-tools/validate-helm.sh [lint|render|all]

  lint   Run helm lint for tracked charts and representative values
  render Render tracked charts and validate the manifests with kubectl --dry-run=client
  all    Run both lint and render validation
EOF
}

if ! command -v helm >/dev/null 2>&1; then
  echo "helm is required for Helm chart validation." >&2
  exit 1
fi

mode="${1:-all}"

run_chart_lint() {
  echo "==> helm lint account-service"
  helm lint k8s/helm/account-service

  echo "==> helm lint game-session-service"
  helm lint k8s/helm/game-session-service

  local firemud_values=(
    "k8s/helm/firemud/values.yaml"
    "k8s/helm/values-local.yaml"
    "k8s/helm/values-dev.yaml"
    "k8s/helm/firemud/values-preview.example.yaml"
    "k8s/helm/firemud/values-dev-demo.example.yaml"
  )
  local values_file
  for values_file in "${firemud_values[@]}"; do
    echo "==> helm lint firemud with ${values_file}"
    helm lint k8s/helm/firemud -f "${values_file}"
  done
}

render_and_validate() {
  local release_name="$1"
  local chart_path="$2"
  local namespace="$3"
  shift 3

  local rendered_file
  rendered_file="$(mktemp)"
  helm template "${release_name}" "${chart_path}" --namespace "${namespace}" "$@" > "${rendered_file}"
  if ! grep -q '[^[:space:]]' "${rendered_file}"; then
    echo "Rendered chart ${chart_path} with release ${release_name} produced no manifests." >&2
    rm -f "${rendered_file}"
    exit 1
  fi
  kubeconform -strict -summary -ignore-missing-schemas < "${rendered_file}"
  rm -f "${rendered_file}"
}

run_render_validation() {
  echo "==> helm template account-service"
  render_and_validate "account-service-lint" "k8s/helm/account-service" "helm-lint"

  echo "==> helm template game-session-service"
  render_and_validate "game-session-service-lint" "k8s/helm/game-session-service" "helm-lint"

  local firemud_values=(
    "k8s/helm/firemud/values-preview.example.yaml"
    "k8s/helm/firemud/values-dev-demo.example.yaml"
  )
  local values_file
  for values_file in "${firemud_values[@]}"; do
    echo "==> helm template firemud with ${values_file}"
    render_and_validate "firemud-lint" "k8s/helm/firemud" "helm-lint" -f "${values_file}"
  done
}

case "${mode}" in
  lint)
    run_chart_lint
    ;;
  render)
    if ! command -v kubeconform >/dev/null 2>&1; then
      echo "kubeconform is required for Helm render validation." >&2
      exit 1
    fi
    run_render_validation
    ;;
  all)
    if ! command -v kubeconform >/dev/null 2>&1; then
      echo "kubeconform is required for Helm render validation." >&2
      exit 1
    fi
    run_chart_lint
    run_render_validation
    ;;
  *)
    usage >&2
    exit 1
    ;;
esac
