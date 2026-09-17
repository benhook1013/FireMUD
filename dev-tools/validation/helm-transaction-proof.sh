#!/usr/bin/env bash
set -euo pipefail

: "${KUBECONFIG:?KUBECONFIG must point to the disposable proof cluster}"
command -v helm >/dev/null 2>&1 || { echo "helm is required" >&2; exit 1; }
command -v kubectl >/dev/null 2>&1 || { echo "kubectl is required" >&2; exit 1; }

work_dir="$(mktemp -d)"
namespace="helm-transaction-proof"
release="firemud-proof"
cleanup() {
  helm uninstall "$release" --namespace "$namespace" --wait >/dev/null 2>&1 || true
  kubectl delete namespace "$namespace" --wait >/dev/null 2>&1 || true
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
kubectl create namespace "$namespace" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
helm install "$release" "$chart_dir" --namespace "$namespace" --wait
helm status "$release" --namespace "$namespace" >/dev/null
test "$(kubectl -n "$namespace" get configmap "$release-marker" -o jsonpath='{.data.marker}')" = installed

helm upgrade "$release" "$chart_dir" --namespace "$namespace" --set marker=upgraded --wait
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
helm rollback "$release" 1 --namespace "$namespace" --wait
helm status "$release" --namespace "$namespace" >/dev/null
test "$(kubectl -n "$namespace" get configmap "$release-marker" -o jsonpath='{.data.marker}')" = installed
helm uninstall "$release" --namespace "$namespace" --wait
kubectl delete namespace "$namespace" --wait
rm -rf -- "$work_dir"
trap - EXIT

echo "Helm install/upgrade/history/rollback/status proof passed"
