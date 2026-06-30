#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 3 ]]; then
  echo "usage: $0 <namespace> [stage] [lane]" >&2
  exit 1
fi

namespace="$1"
stage="${2:-unknown}"
lane="${3:-Hosted}"

if ! command -v kubectl >/dev/null 2>&1; then
  echo "kubectl is required" >&2
  exit 1
fi

section() {
  local title="$1"
  shift
  echo "=== ${title} ==="
  "$@" || true
  echo
}

namespace_summary() {
  local json_file
  json_file="$(mktemp)"
  trap 'rm -f "$json_file"' RETURN
  kubectl get namespace "$namespace" -o json >"$json_file"
  python3 - "$json_file" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as handle:
    data = json.load(handle)
metadata = data.get("metadata", {})
labels = metadata.get("labels", {}) or {}
annotations = metadata.get("annotations", {}) or {}

print(f"name={metadata.get('name', '<unknown>')}")
print(f"phase={data.get('status', {}).get('phase', '<unknown>')}")

interesting_labels = {
    key: value
    for key, value in sorted(labels.items())
    if key.startswith("firemud.dev/") or key.startswith("app.kubernetes.io/")
}
if interesting_labels:
    print("labels:")
    for key, value in interesting_labels.items():
        print(f"  {key}={value}")

interesting_annotations = {
    key: value
    for key, value in sorted(annotations.items())
    if key.startswith("firemud.dev/")
}
if interesting_annotations:
    print("annotations:")
    for key, value in interesting_annotations.items():
        print(f"  {key}={value}")
PY
}

service_port_detail() {
  local json_file
  json_file="$(mktemp)"
  trap 'rm -f "$json_file"' RETURN
  kubectl -n "$namespace" get svc -o json >"$json_file"
  python3 - "$json_file" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as handle:
    data = json.load(handle)
items = sorted(data.get("items", []), key=lambda item: item["metadata"]["name"])
if not items:
    print("No services found.")
    raise SystemExit(0)

for item in items:
    ports = item.get("spec", {}).get("ports", []) or []
    formatted = []
    for port in ports:
        name = port.get("name") or "<unnamed>"
        node = f", nodePort={port['nodePort']}" if "nodePort" in port else ""
        formatted.append(
            f"{name}: port={port.get('port')} targetPort={port.get('targetPort', '<none>')} protocol={port.get('protocol', 'TCP')}{node}"
        )
    joined = "; ".join(formatted) if formatted else "<no ports>"
    print(f"{item['metadata']['name']}: {joined}")
PY
}

configmap_summary() {
  local json_file
  json_file="$(mktemp)"
  trap 'rm -f "$json_file"' RETURN
  kubectl -n "$namespace" get configmap -o json >"$json_file"
  python3 - "$json_file" <<'PY'
import json
import sys

SAFE_EXACT = {
    "SPRING_PROFILES_ACTIVE",
    "SERVER_PORT",
    "MANAGEMENT_SERVER_PORT",
    "GRPC_SERVER_PORT",
    "MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE",
    "ASSET_STORE_ENDPOINT",
}
SAFE_PREFIXES = (
    "FIREMUD_SERVICES_",
    "FIREMUD_GATEWAY_ROUTE_",
    "GRPC_",
    "MANAGEMENT_",
)

def include_key(key: str) -> bool:
    if key in SAFE_EXACT:
        return True
    if key.endswith("_PORT") or key.endswith("_HOST"):
        return True
    return any(key.startswith(prefix) for prefix in SAFE_PREFIXES)

with open(sys.argv[1], "r", encoding="utf-8") as handle:
    data = json.load(handle)
items = sorted(data.get("items", []), key=lambda item: item["metadata"]["name"])
if not items:
    print("No configmaps found.")
    raise SystemExit(0)

for item in items:
    name = item["metadata"]["name"]
    entries = item.get("data", {}) or {}
    keys = sorted(entries.keys())
    print(f"{name}: keys={len(keys)}")
    selected = []
    for key in keys:
        if not include_key(key):
            continue
        value = entries[key].replace("\n", "\\n")
        if len(value) > 140:
          value = value[:137] + "..."
        selected.append(f"  {key}={value}")
    if selected:
        print("\n".join(selected))
PY
}

secret_summary() {
  local json_file
  json_file="$(mktemp)"
  trap 'rm -f "$json_file"' RETURN
  kubectl -n "$namespace" get secret -o json >"$json_file"
  python3 - "$json_file" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as handle:
    data = json.load(handle)
items = sorted(data.get("items", []), key=lambda item: item["metadata"]["name"])
if not items:
    print("No secrets found.")
    raise SystemExit(0)

for item in items:
    name = item["metadata"]["name"]
    secret_type = item.get("type", "<unknown>")
    keys = sorted((item.get("data", {}) or {}).keys())
    keys_display = ",".join(keys) if keys else "<none>"
    print(f"{name}: type={secret_type} dataKeys={keys_display}")
PY
}

tls_secret_summary() {
  mapfile -t tls_secrets < <(
    kubectl -n "$namespace" get secret -o jsonpath='{range .items[?(@.type=="kubernetes.io/tls")]}{.metadata.name}{"\n"}{end}' 2>/dev/null \
      | sed '/^$/d'
  )

  if [[ "${#tls_secrets[@]}" -eq 0 ]]; then
    echo "No TLS secrets found."
    return
  fi

  if ! command -v openssl >/dev/null 2>&1; then
    echo "openssl not available; TLS certificate detail skipped."
    return
  fi

  for secret_name in "${tls_secrets[@]}"; do
    echo "--- ${secret_name} ---"
    kubectl -n "$namespace" get secret "$secret_name" -o jsonpath='{.data.tls\.crt}' 2>/dev/null \
      | base64 -d 2>/dev/null \
      | openssl x509 -noout -subject -issuer -enddate || true
    echo
  done
}

blocked_readiness_summary() {
  local json_file
  json_file="$(mktemp)"
  trap 'rm -f "$json_file"' RETURN
  kubectl -n "$namespace" get deployment,statefulset,pod -o json >"$json_file"
  python3 - "$json_file" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as handle:
    data = json.load(handle)
items = data.get("items", [])
workloads = []
pods = []
for item in items:
    kind = item.get("kind")
    if kind in {"Deployment", "StatefulSet"}:
        workloads.append(item)
    elif kind == "Pod":
        pods.append(item)

found = False
for item in sorted(workloads, key=lambda entry: (entry["kind"], entry["metadata"]["name"])):
    spec = item.get("spec", {}) or {}
    status = item.get("status", {}) or {}
    desired = spec.get("replicas", 1)
    ready = status.get("readyReplicas", 0)
    available = status.get("availableReplicas", 0)
    if ready == desired and available == desired:
        continue
    found = True
    print(f"{item['kind']}/{item['metadata']['name']}: ready={ready}/{desired} available={available}/{desired}")
    for condition in status.get("conditions", []) or []:
        status_value = condition.get("status")
        if status_value == "True" and condition.get("type") == "Available":
            continue
        reason = condition.get("reason", "<none>")
        message = condition.get("message", "").replace("\n", " ").strip()
        print(f"  condition {condition.get('type', '<unknown>')}={status_value} reason={reason}")
        if message:
            print(f"    {message}")

for pod in sorted(pods, key=lambda entry: entry["metadata"]["name"]):
    status = pod.get("status", {}) or {}
    phase = status.get("phase", "<unknown>")
    container_statuses = status.get("containerStatuses", []) or []
    waiting = []
    for container in container_statuses:
        if container.get("ready"):
            continue
        state = container.get("state", {}) or {}
        details = None
        if "waiting" in state:
            details = state["waiting"]
        elif "terminated" in state:
            details = state["terminated"]
        elif "running" in state:
            details = {"reason": "Running", "message": ""}
        else:
            details = {"reason": "Unknown", "message": ""}
        waiting.append(
            f"{container.get('name', '<unnamed>')}: reason={details.get('reason', '<none>')} message={str(details.get('message', '')).replace(chr(10), ' ').strip()}"
        )
    if phase in {"Running", "Succeeded"} and not waiting:
        continue
    found = True
    owner_refs = pod.get("metadata", {}).get("ownerReferences", []) or []
    owner = owner_refs[0]["name"] if owner_refs else "<none>"
    print(f"Pod/{pod['metadata']['name']}: phase={phase} owner={owner}")
    for detail in waiting:
        print(f"  {detail}")

if not found:
    print("No blocked workloads or problematic pods detected.")
PY
}

print_problematic_logs() {
  local pod="$1"
  mapfile -t containers < <(
    kubectl -n "$namespace" get pod "$pod" -o jsonpath='{range .spec.containers[*]}{.name}{"\n"}{end}' 2>/dev/null \
      | sed '/^$/d'
  )
  if [[ "${#containers[@]}" -eq 0 ]]; then
    echo "No containers found for ${pod}."
    return
  fi
  for container in "${containers[@]}"; do
    echo "--- ${pod}/${container} current logs ---"
    kubectl -n "$namespace" logs "$pod" -c "$container" --tail=120 || true
    echo
    echo "--- ${pod}/${container} previous logs ---"
    kubectl -n "$namespace" logs "$pod" -c "$container" --previous --tail=120 || true
    echo
  done
}

echo "${lane} rollout diagnostics for namespace ${namespace} (stage: ${stage})"
echo

section "namespace summary" namespace_summary
section "workload summary" kubectl -n "$namespace" get deployment,statefulset,pods -o wide
section "blocked readiness summary" blocked_readiness_summary
section "service and ingress summary" kubectl -n "$namespace" get svc,ingress -o wide
section "service port detail" service_port_detail
section "configmap summary" configmap_summary
section "secret summary" secret_summary
section "tls secret summary" tls_secret_summary
section "recent events" bash -lc "kubectl -n '$namespace' get events --sort-by=.lastTimestamp | tail -n 160"

mapfile -t problematic_workloads < <(
  kubectl -n "$namespace" get deployment,statefulset --no-headers 2>/dev/null \
    | awk '
        {
          split($2, ready, "/");
          if (ready[1] != ready[2]) {
            print $1;
          }
        }'
)

if [[ "${#problematic_workloads[@]}" -gt 0 ]]; then
  echo "=== describe unavailable workloads ==="
  for workload in "${problematic_workloads[@]}"; do
    echo "--- ${workload} ---"
    kubectl -n "$namespace" describe "$workload" || true
    echo
  done
  echo
fi

mapfile -t problematic_pods < <(
  kubectl -n "$namespace" get pods --no-headers 2>/dev/null \
    | awk '
        {
          split($2, ready, "/");
          if (ready[1] != ready[2] || ($3 != "Running" && $3 != "Completed")) {
            print $1;
          }
        }'
)

if [[ "${#problematic_pods[@]}" -gt 0 ]]; then
  echo "=== describe problematic pods ==="
  for pod in "${problematic_pods[@]}"; do
    echo "--- ${pod} ---"
    kubectl -n "$namespace" describe pod "$pod" || true
    echo
  done
  echo

  echo "=== problematic pod logs ==="
  for pod in "${problematic_pods[@]}"; do
    print_problematic_logs "$pod"
  done
fi
