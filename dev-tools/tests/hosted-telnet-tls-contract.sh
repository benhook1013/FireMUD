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
cp "$TMP_DIR/values.yaml" "$TMP_DIR/values-spring-profile.yaml"
python3 - "$TMP_DIR/values-spring-profile.yaml" <<'PY'
import sys
from pathlib import Path

import yaml

path = Path(sys.argv[1])
values = yaml.safe_load(path.read_text(encoding="utf-8"))
for service in values["previewStack"]["services"]:
    if service["name"] == "tcp-proxy-service":
        service["springProfile"] = True
        break
else:
    raise SystemExit("tcp-proxy-service fixture is missing")
path.write_text(yaml.safe_dump(values, sort_keys=False), encoding="utf-8")
PY
helm template preview-release "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/values-spring-profile.yaml" --namespace pr-42 >"$TMP_DIR/rendered-spring-profile.yaml"

ROOT_DIR="$ROOT_DIR" RENDERED="$TMP_DIR/rendered.yaml" STANDALONE_RENDERED="$TMP_DIR/rendered-standalone.yaml" DISABLED_RENDERED="$TMP_DIR/rendered-disabled.yaml" EMPTY_PULL_SECRETS_RENDERED="$TMP_DIR/rendered-empty-pull-secrets.yaml" SPRING_PROFILE_RENDERED="$TMP_DIR/rendered-spring-profile.yaml" python3 - <<'PY'
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

wrong_mode = deepcopy(documents)
wrong_mode_deployment = next(
    d for d in wrong_mode
    if d.get("kind") == "Deployment" and d["metadata"]["name"] == "tcp-proxy-service"
)
wrong_mode_container = wrong_mode_deployment["spec"]["template"]["spec"]["containers"][0]
for entry in wrong_mode_container["env"]:
    if entry.get("name") == "TCP_PROXY_TELNET_MODE":
        entry["value"] = "PLAINTEXT"
        break
else:
    raise AssertionError("canonical fixture is missing TCP_PROXY_TELNET_MODE")
wrong_mode_issues = preflight.validate_hosted_telnet_tls_values(wrong_mode)
assert any("TCP_PROXY_TELNET_MODE=DIRECT_TLS" in issue for issue in wrong_mode_issues), (
    "non-DIRECT_TLS Telnet mode was accepted"
)

mount = next(m for m in container["volumeMounts"] if m["mountPath"] == "/telnet-tls")
volumes = deployment["spec"]["template"]["spec"]["volumes"]
volume = next(v for v in volumes if v["name"] == mount["name"])
assert volume["secret"]["secretName"] == "preview-release-telnet-tls"

renamed_grpc = deepcopy(documents)
renamed_grpc_deployment = next(
    d for d in renamed_grpc
    if d.get("kind") == "Deployment" and d["metadata"]["name"] == "tcp-proxy-service"
)
renamed_grpc_pod_spec = renamed_grpc_deployment["spec"]["template"]["spec"]
renamed_grpc_container = renamed_grpc_pod_spec["containers"][0]
grpc_mount = next(m for m in renamed_grpc_container["volumeMounts"] if m["mountPath"] == "/tls")
old_grpc_volume_name = grpc_mount["name"]
new_grpc_volume_name = "renamed-grpc-volume"
renamed_grpc_secret = "renamed-grpc-secret-telnet-tls"
grpc_mount["name"] = new_grpc_volume_name
for renamed_volume in renamed_grpc_pod_spec["volumes"]:
    if renamed_volume["name"] == old_grpc_volume_name:
        renamed_volume["name"] = new_grpc_volume_name
        renamed_volume["secret"]["secretName"] = renamed_grpc_secret
    elif renamed_volume["name"] == mount["name"]:
        renamed_volume["secret"]["secretName"] = renamed_grpc_secret
renamed_certificate = next(d for d in renamed_grpc if d.get("kind") == "Certificate")
renamed_certificate["metadata"]["name"] = renamed_grpc_secret
renamed_certificate["spec"]["secretName"] = renamed_grpc_secret
renamed_grpc_issues = preflight.validate_hosted_telnet_tls_values(renamed_grpc)
assert any("must not reuse the gRPC TLS Secret" in issue for issue in renamed_grpc_issues), (
    "gRPC TLS Secret reuse was accepted after the volume was renamed"
)

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

reused_in_later_ingress_entry = deepcopy(documents)
later_ingress = next(d for d in reused_in_later_ingress_entry if d.get("kind") == "Ingress")
later_ingress["spec"]["tls"] = [
    {"secretName": "unrelated-http-secret"},
    {"secretName": "preview-release-telnet-tls"},
]
assert preflight.validate_hosted_telnet_tls_values(reused_in_later_ingress_entry), (
    "Telnet TLS Secret reuse in a later Ingress TLS entry was accepted"
)

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

ambiguous_certificates = deepcopy(documents)
ambiguous_certificates.append(
    {
        "kind": "Certificate",
        "metadata": {"name": "other-telnet-tls", "namespace": "firemud"},
        "spec": {"secretName": "wrong-telnet-secret"},
    }
)
ambiguous_certificate_issues = preflight.validate_hosted_telnet_tls_values(
    ambiguous_certificates
)
assert ambiguous_certificate_issues == [
    "hosted TCP Proxy TLS requires exactly one dedicated -telnet-tls Certificate"
], ambiguous_certificate_issues

disabled_documents = list(yaml.safe_load_all(Path(os.environ["DISABLED_RENDERED"]).read_text(encoding="utf-8")))
assert not any(d.get("kind") == "Certificate" for d in disabled_documents), "disabled TLS still renders a Certificate"
disabled_deployment = next(d for d in disabled_documents if d.get("kind") == "Deployment" and d["metadata"]["name"] == "tcp-proxy-service")
disabled_env = {entry["name"]: entry.get("value") for entry in disabled_deployment["spec"]["template"]["spec"]["containers"][0].get("env", [])}
assert "TCP_PROXY_TLS_ENABLED" not in disabled_env, "disabled TLS still renders enablement"
assert "TCP_PROXY_TELNET_MODE" not in disabled_env, "disabled TLS still renders DIRECT_TLS mode"
empty_pull_secret_documents = list(yaml.safe_load_all(Path(os.environ["EMPTY_PULL_SECRETS_RENDERED"]).read_text(encoding="utf-8")))
assert sum(d.get("kind") == "Deployment" for d in empty_pull_secret_documents) > 0, "empty imagePullSecrets omitted Deployments"
spring_profile_documents = list(yaml.safe_load_all(Path(os.environ["SPRING_PROFILE_RENDERED"]).read_text(encoding="utf-8")))
spring_profile_deployment = next(
    d for d in spring_profile_documents
    if d.get("kind") == "Deployment" and d["metadata"]["name"] == "tcp-proxy-service"
)
spring_profile_env = {
    entry["name"]: entry.get("value")
    for entry in spring_profile_deployment["spec"]["template"]["spec"]["containers"][0].get("env", [])
}
assert spring_profile_env["TCP_PROXY_TLS_ENABLED"] == "true", "spring-profile Telnet TLS env was omitted"
assert spring_profile_env["TCP_PROXY_TLS_CERT"] == "/telnet-tls/tls.crt"
assert spring_profile_env["TCP_PROXY_TLS_KEY"] == "/telnet-tls/tls.key"
assert spring_profile_env["TCP_PROXY_TELNET_MODE"] == "DIRECT_TLS"
print("hosted Telnet direct-TLS chart contract passed")
PY
