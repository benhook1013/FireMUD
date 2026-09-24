#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT_DIR/dev-tools/hosted/shared/ensure-standalone-grpc-certificates.sh"
POLICY="$ROOT_DIR/k8s/trust-bootstrap/issuance-admission.yaml"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf -- "$TEMP_DIR"' EXIT

FAKE_BIN="$TEMP_DIR/bin"
STATE_DIR="$TEMP_DIR/state"
mkdir -p "$FAKE_BIN" "$STATE_DIR"

cat >"$FAKE_BIN/kubectl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

state_dir="$FAKE_KUBECTL_STATE"
printf '%s\n' "$*" >>"$state_dir/calls"

case " $* " in
  *' apply -f - '*)
    cat >"$state_dir/applied.yaml"
    ;;
  *' wait '*)
    [[ "$FAKE_KUBECTL_FAIL_WAIT" != true ]]
    ;;
  *)
    echo "unexpected kubectl invocation: $*" >&2
    exit 2
    ;;
esac
EOF
chmod 700 "$FAKE_BIN/kubectl"

for invalid_namespace in pr-0 pr-42-identity pr-01; do
  if PATH="$FAKE_BIN:$PATH" FAKE_KUBECTL_STATE="$STATE_DIR" FAKE_KUBECTL_FAIL_WAIT=false \
    bash "$SCRIPT" "$invalid_namespace" >"$TEMP_DIR/invalid.out" 2>"$TEMP_DIR/invalid.err"; then
    echo "accepted invalid runtime namespace $invalid_namespace" >&2
    exit 1
  fi
  grep -Fq 'runtime namespace must be dev or canonical pr-N' "$TEMP_DIR/invalid.err"
done
[[ ! -s "$STATE_DIR/calls" ]] || {
  echo "invalid namespaces contacted Kubernetes" >&2
  exit 1
}

for namespace in pr-42 dev; do
  : >"$STATE_DIR/calls"
  PATH="$FAKE_BIN:$PATH" FAKE_KUBECTL_STATE="$STATE_DIR" FAKE_KUBECTL_FAIL_WAIT=false \
    bash "$SCRIPT" "$namespace" >"$TEMP_DIR/$namespace.out"
  grep -Fxq "namespace=$namespace" "$TEMP_DIR/$namespace.out"
  grep -Fxq 'certificates=ready' "$TEMP_DIR/$namespace.out"

  python3 - "$STATE_DIR/applied.yaml" "$namespace" <<'PY'
import sys
from pathlib import Path

import yaml

path, namespace = sys.argv[1:]
documents = [
    document
    for document in yaml.safe_load_all(Path(path).read_text(encoding="utf-8"))
    if document is not None
]
workloads = (
    "game-design-service",
    "world-management-service",
    "entity-management-service",
    "game-logic-service",
    "automation-scripting-service",
)
if len(documents) != len(workloads):
    raise SystemExit(f"expected five Certificate documents, got {len(documents)}")
for workload, document in zip(workloads, documents, strict=True):
    expected_name = f"{namespace}-grpc-{workload}"
    if document.get("apiVersion") != "cert-manager.io/v1" or document.get("kind") != "Certificate":
        raise SystemExit(f"{expected_name} is not a cert-manager Certificate")
    if document.get("metadata", {}).get("name") != expected_name:
        raise SystemExit(f"unexpected Certificate name: {document.get('metadata')}")
    if document["metadata"].get("namespace") != namespace:
        raise SystemExit(f"{expected_name} has the wrong namespace")
    spec = document["spec"]
    if spec.get("secretName") != f"firemud-grpc-{workload}":
        raise SystemExit(f"{expected_name} targets an unexpected Secret")
    if spec.get("issuerRef") != {
        "name": "firemud-ca-issuer",
        "kind": "ClusterIssuer",
        "group": "cert-manager.io",
    }:
        raise SystemExit(f"{expected_name} uses an unexpected issuer")
    if spec.get("isCA") is not False or spec.get("revisionHistoryLimit") != 1:
        raise SystemExit(f"{expected_name} has an unsafe CA or history setting")
    if spec.get("privateKey") != {
        "algorithm": "RSA",
        "size": 2048,
        "encoding": "PKCS8",
        "rotationPolicy": "Always",
    }:
        raise SystemExit(f"{expected_name} has an unexpected private-key profile")
    if spec.get("dnsNames") != [
        workload,
        f"{workload}.{namespace}",
        f"{workload}.{namespace}.svc",
        f"{workload}.{namespace}.svc.cluster.local",
    ]:
        raise SystemExit(f"{expected_name} has unexpected DNS identities")
    if spec.get("uris") != [f"spiffe://firemud/ns/{namespace}/sa/{workload}"]:
        raise SystemExit(f"{expected_name} has an unexpected workload URI")
    if spec.get("usages") != [
        "digital signature",
        "key encipherment",
        "server auth",
        "client auth",
    ]:
        raise SystemExit(f"{expected_name} has unexpected usages")
    if spec.get("encodeUsagesInRequest") is not True:
        raise SystemExit(f"{expected_name} omits encoded usages")
PY

  actual_wait_count="$(grep -c ' wait ' "$STATE_DIR/calls")"
  [[ "$actual_wait_count" -eq 5 ]] || {
    echo "expected five Certificate readiness waits for $namespace, got $actual_wait_count" >&2
    exit 1
  }
  if grep -E 'get secret|create secret|apply.*secret' "$STATE_DIR/calls" >/dev/null; then
    echo "certificate writer accessed a runtime Secret" >&2
    exit 1
  fi
done

: >"$STATE_DIR/calls"
if PATH="$FAKE_BIN:$PATH" FAKE_KUBECTL_STATE="$STATE_DIR" FAKE_KUBECTL_FAIL_WAIT=true \
  CERTIFICATE_WAIT_TIMEOUT_SECONDS=1 bash "$SCRIPT" pr-42 \
  >"$TEMP_DIR/failure.out" 2>"$TEMP_DIR/failure.err"; then
  echo "accepted a failed Certificate readiness wait" >&2
  exit 1
fi
grep -Fq 'did not become Ready' "$TEMP_DIR/failure.err"

python3 - "$POLICY" <<'PY'
import sys
from pathlib import Path

import yaml

documents = list(yaml.safe_load_all(Path(sys.argv[1]).read_text(encoding="utf-8")))
by_name = {
    document.get("metadata", {}).get("name"): document
    for document in documents
    if document.get("kind") == "ValidatingAdmissionPolicy"
}
certificate_request = by_name["firemud-trust-bootstrap-certificaterequest"]
request_text = str(certificate_request["spec"])
certificate = by_name["firemud-trust-bootstrap-certificate"]
certificate_text = str(certificate["spec"])
for workload in (
    "game-design-service",
    "world-management-service",
    "entity-management-service",
    "game-logic-service",
    "automation-scripting-service",
):
    assert workload in request_text, workload
    assert f"firemud-grpc-{workload}" in certificate_text, workload
    assert "spiffe://firemud/ns/" in certificate_text
assert "firemud-standalone-certificate-writer" in certificate_text
assert "request.namespace.matches('^(dev|pr-[1-9][0-9]{0,50})$')" in certificate_text
assert "request.namespace.matches('^pr-[1-9][0-9]{0,50}$') || object.metadata.name.contains('-grpc-')" in certificate_text
assert "firemud-ca-issuer" in certificate_text
PY

echo "standalone gRPC certificate contract passed"
