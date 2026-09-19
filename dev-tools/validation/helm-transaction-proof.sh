#!/usr/bin/env bash
set -euo pipefail

: "${KUBECONFIG:?KUBECONFIG must point to the disposable proof cluster}"
command -v helm >/dev/null 2>&1 || { echo "helm is required" >&2; exit 1; }
command -v kubectl >/dev/null 2>&1 || { echo "kubectl is required" >&2; exit 1; }

work_dir="$(mktemp -d)"
temp_id="$(basename "$work_dir" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9')"
if [[ -z "$temp_id" ]]; then
  echo "sanitized temp_id derived from work_dir $work_dir is empty" >&2
  rm -rf -- "$work_dir"
  exit 1
fi
namespace="helm-transaction-proof-${temp_id}"
release="firemud-proof"
namespace_owned=false
cleanup() {
  if [[ "$namespace_owned" == true ]]; then
    helm uninstall "$release" --namespace "$namespace" --wait --timeout 180s >/dev/null 2>&1 || true
    kubectl delete namespace "$namespace" --wait --request-timeout=180s --timeout=180s >/dev/null 2>&1 || true
  fi
  rm -rf -- "$work_dir"
}
trap cleanup EXIT
chart_dir="$work_dir/chart"
mkdir -p "$chart_dir/templates"
cat > "$chart_dir/Chart.yaml" <<'EOF'
apiVersion: v2
name: firemud-helm-transaction-proof
description: Disposable Helm lifecycle proof chart
type: application
version: 0.1.0
EOF
cat > "$chart_dir/values.yaml" <<'EOF'
marker: installed
EOF
cat > "$chart_dir/templates/configmap.yaml" <<'EOF'
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ .Release.Name }}-marker
data:
  marker: {{ .Values.marker | quote }}
EOF

helm lint "$chart_dir"
if kubectl create namespace "$namespace" >/dev/null 2>&1; then
  namespace_owned=true
else
  echo "unable to exclusively create disposable namespace $namespace" >&2
  exit 1
fi
helm install "$release" "$chart_dir" --namespace "$namespace" --wait --timeout 180s
helm status "$release" --namespace "$namespace" >/dev/null
test "$(kubectl -n "$namespace" get configmap "$release-marker" -o jsonpath='{.data.marker}')" = installed

helm upgrade "$release" "$chart_dir" --namespace "$namespace" --set marker=upgraded --wait --timeout 180s
helm status "$release" --namespace "$namespace" >/dev/null
test "$(kubectl -n "$namespace" get configmap "$release-marker" -o jsonpath='{.data.marker}')" = upgraded

history_json="$(helm history "$release" --namespace "$namespace" --output json)"
python3 -c '
import json
import sys

history = json.load(sys.stdin)
if len(history) < 2:
    print("Helm history must contain at least two revisions", file=sys.stderr)
    raise SystemExit(1)
' <<<"$history_json"
helm rollback "$release" 1 --namespace "$namespace" --wait --timeout 180s
helm status "$release" --namespace "$namespace" >/dev/null
test "$(kubectl -n "$namespace" get configmap "$release-marker" -o jsonpath='{.data.marker}')" = installed
helm uninstall "$release" --namespace "$namespace" --wait --timeout 180s
kubectl delete namespace "$namespace" --wait --request-timeout=180s --timeout=180s
rm -rf -- "$work_dir"
trap - EXIT

echo "Helm install/upgrade/history/rollback/status proof passed"
