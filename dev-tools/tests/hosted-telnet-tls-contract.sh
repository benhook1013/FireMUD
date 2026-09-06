#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/../.." && pwd)
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

python3 "$ROOT_DIR/dev-tools/hosted/preview/render-preview-values.py" \
  "$ROOT_DIR/k8s/helm/firemud/values-hosted-shared.example.yaml" \
  "$TMP_DIR/values.yaml" 42 pr-42 preview-release preview-42.preview.example.test image-tag 32042
helm template preview-release "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/values.yaml" --namespace pr-42 >"$TMP_DIR/rendered.yaml"
helm template preview-release "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/values.yaml" \
  --set previewStack.certificateIdentity.mode=standalone \
  --namespace pr-42 >"$TMP_DIR/rendered-standalone.yaml"
helm template preview-release "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/values.yaml" --set previewStack.telnetTls.enabled=false --namespace pr-42 >"$TMP_DIR/rendered-disabled.yaml"
helm template preview-release "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/values.yaml" --set-json 'previewStack.imagePullSecrets=[]' --namespace pr-42 >"$TMP_DIR/rendered-empty-pull-secrets.yaml"

ROOT_DIR="$ROOT_DIR" RENDERED="$TMP_DIR/rendered.yaml" STANDALONE_RENDERED="$TMP_DIR/rendered-standalone.yaml" DISABLED_RENDERED="$TMP_DIR/rendered-disabled.yaml" EMPTY_PULL_SECRETS_RENDERED="$TMP_DIR/rendered-empty-pull-secrets.yaml" python3 - <<'PY'
import os
import sys
from copy import deepcopy
from pathlib import Path

import yaml

root = Path(os.environ["ROOT_DIR"])
sys.path.insert(0, str(root / "dev-tools" / "deploy"))
import preflight

hosted_documents = list(yaml.safe_load_all(Path(os.environ["RENDERED"]).read_text(encoding="utf-8")))
assert not any(d.get("kind") == "Certificate" for d in hosted_documents), "hosted-controller mode still renders a Certificate"
hosted_ingress = next(d for d in hosted_documents if d.get("kind") == "Ingress")
assert "annotations" not in hosted_ingress.get("metadata", {}), "hosted-controller mode still renders a cert-manager ingress shim"
hosted_service = next(d for d in hosted_documents if d.get("kind") == "Service" and d["metadata"]["name"] == "tcp-proxy-service")
assert all("nodePort" not in port for port in hosted_service["spec"]["ports"]), "hosted-controller mode carries a workflow-selected NodePort"

documents = list(yaml.safe_load_all(Path(os.environ["STANDALONE_RENDERED"]).read_text(encoding="utf-8")))
issues = preflight.validate_hosted_telnet_tls_values(documents)
assert not issues, issues

deployment = next(d for d in documents if d.get("kind") == "Deployment" and d["metadata"]["name"] == "tcp-proxy-service")
container = deployment["spec"]["template"]["spec"]["containers"][0]
env = {entry["name"]: entry.get("value") for entry in container.get("env", [])}
assert env["TCP_PROXY_TLS_ENABLED"] == "true"
assert env["TCP_PROXY_TLS_CERT"] == "/telnet-tls/tls.crt"
assert env["TCP_PROXY_TLS_KEY"] == "/telnet-tls/tls.key"
assert env["TCP_PROXY_TELNET_MODE"] == "DIRECT_TLS"
mount = next(m for m in container["volumeMounts"] if m["mountPath"] == "/telnet-tls")
volumes = deployment["spec"]["template"]["spec"]["volumes"]
volume = next(v for v in volumes if v["name"] == mount["name"])
assert volume["secret"]["secretName"] == "preview-release-telnet-tls"

certificate = next(d for d in documents if d.get("kind") == "Certificate")
assert certificate["spec"]["secretName"] == "preview-release-telnet-tls"
assert certificate["spec"]["privateKey"]["algorithm"] == "RSA"
assert certificate["spec"]["privateKey"]["encoding"] == "PKCS8"
assert certificate["spec"]["dnsNames"] == ["preview-42.preview.example.test"]
ingress = next(d for d in documents if d.get("kind") == "Ingress")
assert ingress["spec"]["tls"][0]["secretName"] == "preview-release-tls"
assert certificate["spec"]["secretName"] != ingress["spec"]["tls"][0]["secretName"]

mismatched = deepcopy(documents)
mismatched_certificate = next(d for d in mismatched if d.get("kind") == "Certificate")
mismatched_certificate["spec"]["secretName"] = "wrong-telnet-secret"
assert preflight.validate_hosted_telnet_tls_values(mismatched), "certificate Secret mismatch was accepted"

reused = deepcopy(documents)
reused_certificate = next(d for d in reused if d.get("kind") == "Certificate")
reused_certificate["spec"]["secretName"] = ingress["spec"]["tls"][0]["secretName"]
assert preflight.validate_hosted_telnet_tls_values(reused), "HTTP Ingress Secret reuse was accepted"

cross_namespace_decoys = deepcopy(documents)
cross_namespace_decoys.extend(
    [
        {
            "kind": "Certificate",
            "metadata": {
                "name": "preview-release-telnet-tls",
                "namespace": "other",
            },
            "spec": {"secretName": "wrong-cross-namespace-secret"},
        },
        {
            "kind": "Ingress",
            "metadata": {"name": "other-ingress", "namespace": "other"},
            "spec": {"tls": [{"secretName": "preview-release-telnet-tls"}]},
        },
        {
            "kind": "Deployment",
            "metadata": {"name": "tcp-proxy-service", "namespace": "other"},
            "spec": {"template": {"spec": {"containers": []}}},
        },
    ]
)
assert not preflight.validate_hosted_telnet_tls_values(cross_namespace_decoys), (
    "same-name resources in another namespace affected hosted Telnet TLS validation"
)

ambiguous_nodeports = deepcopy(documents)
ambiguous_nodeports.append(
    {
        "kind": "Service",
        "metadata": {"name": "tcp-proxy-service", "namespace": "other"},
        "spec": {"type": "NodePort"},
    }
)
ambiguous_issues = preflight.validate_hosted_telnet_tls_values(ambiguous_nodeports)
assert any("exactly one tcp-proxy-service NodePort Service" in issue for issue in ambiguous_issues), (
    "multiple cross-namespace tcp-proxy-service NodePorts were accepted"
)

disabled_documents = list(yaml.safe_load_all(Path(os.environ["DISABLED_RENDERED"]).read_text(encoding="utf-8")))
assert not any(d.get("kind") == "Certificate" for d in disabled_documents), "disabled TLS still renders a Certificate"
disabled_deployment = next(d for d in disabled_documents if d.get("kind") == "Deployment" and d["metadata"]["name"] == "tcp-proxy-service")
disabled_env = {entry["name"]: entry.get("value") for entry in disabled_deployment["spec"]["template"]["spec"]["containers"][0].get("env", [])}
assert "TCP_PROXY_TLS_ENABLED" not in disabled_env, "disabled TLS still renders enablement"
empty_pull_secret_documents = list(yaml.safe_load_all(Path(os.environ["EMPTY_PULL_SECRETS_RENDERED"]).read_text(encoding="utf-8")))
assert sum(d.get("kind") == "Deployment" for d in empty_pull_secret_documents) > 0, "empty imagePullSecrets omitted Deployments"
print("hosted Telnet direct-TLS chart contract passed")
PY
