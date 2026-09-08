#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/../.." && pwd)
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

python3 - \
  "$ROOT_DIR/k8s/helm/firemud/values-hosted-shared.example.yaml" \
  "$TMP_DIR/values-controller.yaml" <<'PY'
import json
import sys
from pathlib import Path

template_path = Path(sys.argv[1])
output_path = Path(sys.argv[2])
text = template_path.read_text(encoding="utf-8")
replacements = {
    "__PR_NUMBER__": "42",
    "__NAMESPACE__": "pr-42",
    "__RELEASE_NAME__": "preview-release",
    "__HOSTNAME__": "preview-42.preview.example.test",
    "__TELNET_PORT__": "32042",
    "__IMAGE_TAG__": "image-tag",
    "__TLS_SECRET_NAME__": "preview-release-tls",
    "__TELNET_TLS_SECRET_NAME__": "preview-release-telnet-tls",
    "__JWT_SIGNING_KEY__": "a" * 64,
    "__JWKS_JSON__": json.dumps({"keys": []}, separators=(",", ":")),
    "__SEED_GAME_NAME__": "Telnet TLS Contract Game",
    "__SEED_GAME_DESCRIPTION__": "Telnet TLS contract fixture game.",
    "__SEED_VERSION_NOTES__": "Telnet TLS contract fixture version",
    "__SEED_TEMPLATE_NAME__": "Telnet TLS Contract Template",
    "__SEED_TEMPLATE_DESCRIPTION__": "Telnet TLS contract fixture template.",
    "__SEED_WORKFLOW_ID__": "telnet-tls-contract-seed",
    "__SEED_MANIFEST_HASH__": "telnet-tls-contract-manifest",
    "__SEED_GENERATION_CONFIG_REVISION__": "genrev:telnet-tls-contract",
}
for target, replacement in replacements.items():
    if target not in text:
        raise SystemExit(f"Telnet TLS contract fixture token is missing: {target}")
    text = text.replace(target, replacement)
text = text.replace("        # __TCP_PROXY_GATEWAY_BASE_URL_LINE__", "")
text = text.replace("        # __TCP_PROXY_ADDITIONAL_SERVICE_PORTS__", "")
output_path.write_text(text, encoding="utf-8")
PY
helm template preview-release "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/values-controller.yaml" \
  --namespace pr-42 >"$TMP_DIR/rendered-controller.yaml"
cp "$TMP_DIR/values-controller.yaml" "$TMP_DIR/values.yaml"
python3 - "$TMP_DIR/values.yaml" <<'PY'
import sys
from pathlib import Path

import yaml

path = Path(sys.argv[1])
values = yaml.safe_load(path.read_text(encoding="utf-8"))
values["previewStack"]["certificateIdentity"]["mode"] = "standalone"
path.write_text(yaml.safe_dump(values, sort_keys=False), encoding="utf-8")
PY
helm template preview-release "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/values.yaml" --namespace pr-42 >"$TMP_DIR/rendered.yaml"
cp "$TMP_DIR/values.yaml" "$TMP_DIR/values-omitted.yaml"
python3 - "$TMP_DIR/values-omitted.yaml" <<'PY'
import sys
from pathlib import Path

import yaml

path = Path(sys.argv[1])
values = yaml.safe_load(path.read_text(encoding="utf-8"))
values["previewStack"].pop("telnetTls")
path.write_text(yaml.safe_dump(values, sort_keys=False), encoding="utf-8")
PY
helm template preview-release "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/values-omitted.yaml" --namespace pr-42 >"$TMP_DIR/rendered-omitted.yaml"
cp "$TMP_DIR/values.yaml" "$TMP_DIR/values-configured-mode.yaml"
python3 - "$TMP_DIR/values-configured-mode.yaml" <<'PY'
import sys
from pathlib import Path

import yaml

path = Path(sys.argv[1])
values = yaml.safe_load(path.read_text(encoding="utf-8"))
for service in values["previewStack"]["services"]:
    if service["name"] == "tcp-proxy-service":
        service.setdefault("extraEnv", {})["TCP_PROXY_TELNET_MODE"] = "PLAINTEXT"
        break
else:
    raise SystemExit("tcp-proxy-service fixture is missing")
path.write_text(yaml.safe_dump(values, sort_keys=False), encoding="utf-8")
PY
helm template preview-release "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/values-configured-mode.yaml" --namespace pr-42 >"$TMP_DIR/rendered-configured-enabled.yaml"
cp "$TMP_DIR/values-configured-mode.yaml" "$TMP_DIR/values-managed-env.yaml"
python3 - "$TMP_DIR/values-managed-env.yaml" <<'PY'
import sys
from pathlib import Path

import yaml

path = Path(sys.argv[1])
values = yaml.safe_load(path.read_text(encoding="utf-8"))
for service in values["previewStack"]["services"]:
    if service["name"] == "tcp-proxy-service":
        service.setdefault("extraEnv", {}).update(
            {
                "TCP_PROXY_TLS_ENABLED": "shadow-enabled",
                "TCP_PROXY_TLS_CERT": "/shadow/tls.crt",
                "TCP_PROXY_TLS_KEY": "/shadow/tls.key",
                "TELNET_CONTRACT_PASSTHROUGH": "preserved",
            }
        )
        break
else:
    raise SystemExit("tcp-proxy-service fixture is missing")
path.write_text(yaml.safe_dump(values, sort_keys=False), encoding="utf-8")
PY
helm template preview-release "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/values-managed-env.yaml" --namespace pr-42 >"$TMP_DIR/rendered-managed-env.yaml"
helm template preview-release "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/values-configured-mode.yaml" \
  --set previewStack.telnetTls.enabled=false \
  --set-string 'previewStack.telnetTls.clusterIssuer=' \
  --set-string 'preview.hostname=' \
  --namespace pr-42 >"$TMP_DIR/rendered-disabled.yaml"
helm template preview-release "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/values.yaml" --set-json 'previewStack.imagePullSecrets=[]' --namespace pr-42 >"$TMP_DIR/rendered-empty-pull-secrets.yaml"
cp "$TMP_DIR/values-managed-env.yaml" "$TMP_DIR/values-spring-profile.yaml"
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

TELNET_TLS_SECRET_NAME_ERROR="previewStack.telnetTls.secretName is required when Telnet TLS is enabled"
if helm template preview-release "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/values.yaml" \
  --set-string 'previewStack.telnetTls.secretName=' \
  --namespace pr-42 >/dev/null 2>"$TMP_DIR/missing-secret.err"; then
  echo "chart rendered with Telnet TLS enabled and no Secret name" >&2
  exit 1
fi
if ! grep -Fq "$TELNET_TLS_SECRET_NAME_ERROR" "$TMP_DIR/missing-secret.err"; then
  echo "chart did not report the expected missing Secret name diagnostic" >&2
  sed -n '1,20p' "$TMP_DIR/missing-secret.err" >&2
  exit 1
fi

TELNET_TLS_SECRET_SUFFIX_ERROR="previewStack.telnetTls.secretName must end with -telnet-tls when Telnet TLS is enabled"
helm template raw-hosted-sentinel "$ROOT_DIR/k8s/helm/firemud" \
  -f "$ROOT_DIR/k8s/helm/firemud/values-hosted-shared.example.yaml" \
  --set previewStack.certificateIdentity.mode=standalone \
  --namespace pr-42 >/dev/null
for invalid_secret_name in \
  '__TELNET_TLS_SECRET_NAME_' \
  '_TELNET_TLS_SECRET_NAME__' \
  'preview-release-public-tls'
do
  if helm template preview-release "$ROOT_DIR/k8s/helm/firemud" \
    -f "$TMP_DIR/values.yaml" \
    --set-string "previewStack.telnetTls.secretName=${invalid_secret_name}" \
    --namespace pr-42 >/dev/null 2>"$TMP_DIR/invalid-secret-suffix.err"; then
    echo "chart rendered Telnet TLS with invalid Secret name ${invalid_secret_name}" >&2
    exit 1
  fi
  if ! grep -Fq "$TELNET_TLS_SECRET_SUFFIX_ERROR" "$TMP_DIR/invalid-secret-suffix.err"; then
    echo "chart did not report the expected invalid Telnet TLS Secret suffix diagnostic for ${invalid_secret_name}" >&2
    sed -n '1,20p' "$TMP_DIR/invalid-secret-suffix.err" >&2
    exit 1
  fi
done

helm template preview-release "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/values-controller.yaml" \
  --set-string 'previewStack.telnetTls.secretName=other-release-telnet-tls' \
  --namespace pr-42 >"$TMP_DIR/rendered-mismatched-hosted-secret.yaml"
python3 - "$TMP_DIR/rendered-mismatched-hosted-secret.yaml" <<'PY'
import sys
from pathlib import Path

import yaml

documents = [
    document
    for document in yaml.safe_load_all(Path(sys.argv[1]).read_text(encoding="utf-8"))
    if isinstance(document, dict)
]
deployment = next(
    document
    for document in documents
    if document.get("kind") == "Deployment"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service"
)
telnet_volume = next(
    volume
    for volume in deployment["spec"]["template"]["spec"]["volumes"]
    if volume.get("name") == "telnet-tls"
)
assert telnet_volume["secret"]["secretName"] == "preview-release-telnet-tls"
PY

CERTIFICATE_IDENTITY_MODE_ERROR="previewStack.certificateIdentity.mode must be standalone or hosted-controller"
if helm template preview-release "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/values.yaml" \
  --set previewStack.certificateIdentity.mode=other \
  --namespace pr-42 >/dev/null 2>"$TMP_DIR/invalid-certificate-mode.err"; then
  echo "chart rendered an unsupported certificate identity mode" >&2
  exit 1
fi
if ! grep -Fq "$CERTIFICATE_IDENTITY_MODE_ERROR" "$TMP_DIR/invalid-certificate-mode.err"; then
  echo "chart did not report the unsupported certificate identity mode" >&2
  sed -n '1,20p' "$TMP_DIR/invalid-certificate-mode.err" >&2
  exit 1
fi
helm template preview-release "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/values.yaml" \
  --set-json 'previewStack.certificateIdentity=null' \
  --namespace pr-42 >"$TMP_DIR/rendered-null-certificate-identity.yaml"

TELNET_TLS_CLUSTER_ISSUER_ERROR="previewStack.telnetTls.clusterIssuer is required when rendering the standalone Telnet TLS Certificate"
if helm template preview-release "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/values.yaml" \
  --set-string 'previewStack.telnetTls.clusterIssuer=' \
  --namespace pr-42 >/dev/null 2>"$TMP_DIR/missing-cluster-issuer.err"; then
  echo "chart rendered a standalone Telnet TLS Certificate with no ClusterIssuer" >&2
  exit 1
fi
if ! grep -Fq "$TELNET_TLS_CLUSTER_ISSUER_ERROR" "$TMP_DIR/missing-cluster-issuer.err"; then
  echo "chart did not report the expected missing ClusterIssuer diagnostic" >&2
  sed -n '1,20p' "$TMP_DIR/missing-cluster-issuer.err" >&2
  exit 1
fi

PREVIEW_HOSTNAME_ERROR="preview.hostname is required when rendering the standalone Telnet TLS Certificate"
if helm template preview-release "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/values.yaml" \
  --set-string 'preview.hostname=' \
  --namespace pr-42 >/dev/null 2>"$TMP_DIR/missing-preview-hostname.err"; then
  echo "chart rendered a standalone Telnet TLS Certificate with no preview hostname" >&2
  exit 1
fi
if ! grep -Fq "$PREVIEW_HOSTNAME_ERROR" "$TMP_DIR/missing-preview-hostname.err"; then
  echo "chart did not report the expected missing preview hostname diagnostic" >&2
  sed -n '1,20p' "$TMP_DIR/missing-preview-hostname.err" >&2
  exit 1
fi

ROOT_DIR="$ROOT_DIR" RENDERED="$TMP_DIR/rendered.yaml" CONTROLLER_RENDERED="$TMP_DIR/rendered-controller.yaml" NULL_IDENTITY_RENDERED="$TMP_DIR/rendered-null-certificate-identity.yaml" OMITTED_RENDERED="$TMP_DIR/rendered-omitted.yaml" CONFIGURED_ENABLED_RENDERED="$TMP_DIR/rendered-configured-enabled.yaml" MANAGED_ENV_RENDERED="$TMP_DIR/rendered-managed-env.yaml" DISABLED_RENDERED="$TMP_DIR/rendered-disabled.yaml" EMPTY_PULL_SECRETS_RENDERED="$TMP_DIR/rendered-empty-pull-secrets.yaml" SPRING_PROFILE_RENDERED="$TMP_DIR/rendered-spring-profile.yaml" python3 - <<'PY'
import os
import sys
from copy import deepcopy
from pathlib import Path

import yaml

root = Path(os.environ["ROOT_DIR"])
sys.path.insert(0, str(root / "dev-tools" / "deploy"))
import preflight


def load_yaml_mappings(path):
    return [
        document
        for document in yaml.safe_load_all(path.read_text(encoding="utf-8"))
        if isinstance(document, dict)
    ]


documents = load_yaml_mappings(Path(os.environ["RENDERED"]))
issues = preflight.validate_hosted_telnet_tls_values(documents)
assert not issues, issues

controller_documents = load_yaml_mappings(Path(os.environ["CONTROLLER_RENDERED"]))
controller_issues = preflight.validate_hosted_telnet_tls_values(controller_documents)
assert not controller_issues, controller_issues
null_identity_documents = load_yaml_mappings(Path(os.environ["NULL_IDENTITY_RENDERED"]))
null_identity_issues = preflight.validate_hosted_telnet_tls_values(
    null_identity_documents
)
assert not null_identity_issues, null_identity_issues
null_identity_resources = [
    document
    for document in null_identity_documents
    if (
        document.get("kind") in {"Deployment", "Service"}
        and document.get("metadata", {}).get("name") == "tcp-proxy-service"
    )
]
assert len(null_identity_resources) == 2, null_identity_resources
assert all(
    document["metadata"]["labels"]["firemud.dev/certificate-identity-mode"]
    == "standalone"
    for document in null_identity_resources
)
assert not any(
    document.get("kind") == "Certificate"
    and document.get("metadata", {}).get("name", "").endswith("-telnet-tls")
    for document in controller_documents
), "hosted-controller mode rendered a chart-owned Telnet Certificate"
controller_deployment = next(
    document
    for document in controller_documents
    if document.get("kind") == "Deployment"
    and document["metadata"]["name"] == "tcp-proxy-service"
)
controller_service = next(
    document
    for document in controller_documents
    if document.get("kind") == "Service"
    and document["metadata"]["name"] == "tcp-proxy-service"
)
for document in (controller_deployment, controller_service):
    assert document["metadata"]["labels"][
        "firemud.dev/certificate-identity-mode"
    ] == "hosted-controller"
controller_telnet_port = next(
    port
    for port in controller_service["spec"]["ports"]
    if port.get("port") == 2323
)
assert controller_telnet_port["nodePort"] == 32042, (
    "hosted-controller render did not bind the allocated Telnet NodePort"
)
assert controller_service["metadata"]["annotations"][
    "firemud.dev/allocated-telnet-port"
] == "32042"
controller_volume = next(
    volume
    for volume in controller_deployment["spec"]["template"]["spec"]["volumes"]
    if volume.get("name") == "telnet-tls"
)
assert controller_volume["secret"]["secretName"] == "preview-release-telnet-tls"

controller_without_nodeport_service = [
    deepcopy(document)
    for document in controller_documents
    if not (
        document.get("kind") == "Service"
        and document.get("metadata", {}).get("name") == "tcp-proxy-service"
        and document.get("spec", {}).get("type") == "NodePort"
    )
]
assert not preflight.validate_hosted_telnet_tls_values(
    controller_without_nodeport_service
), "optional hosted Telnet validation rejected an inapplicable render"
missing_nodeport_issues = preflight.validate_hosted_telnet_tls_values(
    controller_without_nodeport_service,
    required_identity_mode="hosted-controller",
)
assert any(
    "requires exactly one tcp-proxy-service NodePort Service" in issue
    for issue in missing_nodeport_issues
), "required hosted identity mode accepted a missing TCP Proxy NodePort Service"

controller_with_certificate = deepcopy(controller_documents)
controller_with_certificate.append(
    {
        "apiVersion": "cert-manager.io/v1",
        "kind": "Certificate",
        "metadata": {
            "name": "preview-release-telnet-tls",
        },
        "spec": {"secretName": "preview-release-telnet-tls"},
    }
)
controller_certificate_issues = preflight.validate_hosted_telnet_tls_values(
    controller_with_certificate
)
assert any(
    "hosted-controller TCP Proxy TLS must not render" in issue
    for issue in controller_certificate_issues
), "hosted-controller mode accepted a chart-owned Telnet Certificate"

controller_mode_mismatch = deepcopy(controller_documents)
next(
    document
    for document in controller_mode_mismatch
    if document.get("kind") == "Service"
    and document["metadata"]["name"] == "tcp-proxy-service"
)["metadata"]["labels"].pop("firemud.dev/certificate-identity-mode")
mode_mismatch_issues = preflight.validate_hosted_telnet_tls_values(
    controller_mode_mismatch
)
assert any(
    "certificate identity mode labels must match" in issue
    for issue in mode_mismatch_issues
), "hosted-controller mode accepted a missing rendered ownership signal"

controller_missing_modes = deepcopy(controller_documents)
for document in controller_missing_modes:
    if (
        document.get("kind") in {"Deployment", "Service"}
        and document.get("metadata", {}).get("name") == "tcp-proxy-service"
    ):
        document["metadata"]["labels"].pop(
            "firemud.dev/certificate-identity-mode"
        )
missing_modes_issues = preflight.validate_hosted_telnet_tls_values(
    controller_missing_modes
)
assert any(
    "require explicit certificate identity mode labels" in issue
    for issue in missing_modes_issues
), "Helm-rendered controller ownership downgraded to standalone when both labels were removed"

controller_missing_nodeport = deepcopy(controller_documents)
next(
    port
    for document in controller_missing_nodeport
    if document.get("kind") == "Service"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service"
    for port in document["spec"]["ports"]
    if port.get("port") == 2323
).pop("nodePort")
missing_explicit_nodeport_issues = preflight.validate_hosted_telnet_tls_values(
    controller_missing_nodeport
)
assert any(
    "requires exactly one explicit allocated nodePort" in issue
    for issue in missing_explicit_nodeport_issues
), "hosted-controller mode accepted a missing allocated TCP Proxy nodePort"

controller_missing_allocation = deepcopy(controller_documents)
next(
    document
    for document in controller_missing_allocation
    if document.get("kind") == "Service"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service"
)["metadata"]["annotations"].pop("firemud.dev/allocated-telnet-port")
missing_allocation_issues = preflight.validate_hosted_telnet_tls_values(
    controller_missing_allocation
)
assert any(
    "requires an allocated Telnet port annotation" in issue
    for issue in missing_allocation_issues
), "hosted-controller mode accepted missing allocator metadata"

controller_wrong_nodeport = deepcopy(controller_documents)
next(
    port
    for document in controller_wrong_nodeport
    if document.get("kind") == "Service"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service"
    for port in document["spec"]["ports"]
    if port.get("port") == 2323
)["nodePort"] = 32043
wrong_nodeport_issues = preflight.validate_hosted_telnet_tls_values(
    controller_wrong_nodeport
)
assert any(
    "nodePort must match its allocated Telnet port" in issue
    for issue in wrong_nodeport_issues
), "hosted-controller mode accepted the wrong allocated TCP Proxy nodePort"

controller_additional_nodeport = deepcopy(controller_documents)
next(
    document
    for document in controller_additional_nodeport
    if document.get("kind") == "Service"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service"
)["spec"]["ports"].append(
    {
        "name": "unrelated",
        "port": 9000,
        "targetPort": 9000,
        "protocol": "TCP",
        "nodePort": 32043,
    }
)
assert not preflight.validate_hosted_telnet_tls_values(
    controller_additional_nodeport
), "an unrelated NodePort was mistaken for the allocated Telnet listener"

unknown_mode = deepcopy(controller_documents)
for document in unknown_mode:
    if (
        document.get("kind") in {"Deployment", "Service"}
        and document.get("metadata", {}).get("name") == "tcp-proxy-service"
    ):
        document["metadata"]["labels"][
            "firemud.dev/certificate-identity-mode"
        ] = "other"
unknown_mode_issues = preflight.validate_hosted_telnet_tls_values(unknown_mode)
assert any(
    "certificate identity mode must be standalone or hosted-controller" in issue
    for issue in unknown_mode_issues
), "preflight accepted an unsupported certificate identity mode"

standalone_without_certificate = deepcopy(documents)
standalone_without_certificate = [
    document
    for document in standalone_without_certificate
    if document.get("kind") != "Certificate"
    or not document.get("metadata", {}).get("name", "").endswith("-telnet-tls")
]
standalone_missing_issues = preflight.validate_hosted_telnet_tls_values(
    standalone_without_certificate
)
assert any(
    "requires exactly one dedicated -telnet-tls Certificate" in issue
    for issue in standalone_missing_issues
), "standalone mode accepted a missing Telnet Certificate"

deployment = next(d for d in documents if d.get("kind") == "Deployment" and d["metadata"]["name"] == "tcp-proxy-service")
target_namespace = preflight.workload_namespace(deployment)
foreign_namespace = f"{target_namespace}-foreign"
container = deployment["spec"]["template"]["spec"]["containers"][0]
expected_telnet_env_order = [
    "TCP_PROXY_TLS_ENABLED",
    "TCP_PROXY_TLS_CERT",
    "TCP_PROXY_TLS_KEY",
    "TCP_PROXY_TELNET_MODE",
]
telnet_env_order = [
    entry["name"]
    for entry in container.get("env", [])
    if entry.get("name") in expected_telnet_env_order
]
assert telnet_env_order == expected_telnet_env_order, telnet_env_order
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

omitted_documents = load_yaml_mappings(Path(os.environ["OMITTED_RENDERED"]))
assert not any(d.get("kind") == "Certificate" for d in omitted_documents), "omitted TLS still renders a Certificate"
omitted_deployment = next(
    d for d in omitted_documents
    if d.get("kind") == "Deployment" and d["metadata"]["name"] == "tcp-proxy-service"
)
omitted_pod_spec = omitted_deployment["spec"]["template"]["spec"]
omitted_container = omitted_pod_spec["containers"][0]
omitted_env = {entry["name"]: entry.get("value") for entry in omitted_container.get("env", [])}
assert "TCP_PROXY_TLS_ENABLED" not in omitted_env, "omitted TLS still renders enablement"
assert not any(
    item.get("mountPath") == "/telnet-tls"
    for item in omitted_container.get("volumeMounts", [])
), "omitted TLS still renders a volume mount"
assert not any(
    item.get("name") == "telnet-tls"
    for item in omitted_pod_spec.get("volumes", [])
), "omitted TLS still renders a volume"

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
assert renamed_grpc_issues == [
    "TCP Proxy Telnet TLS Secret must not reuse the gRPC TLS Secret"
], renamed_grpc_issues

certificate = next(d for d in documents if d.get("kind") == "Certificate")
assert certificate["spec"]["secretName"] == "preview-release-telnet-tls"
assert certificate["spec"]["privateKey"]["algorithm"] == "RSA"
assert certificate["spec"]["privateKey"]["encoding"] == "PKCS8"
assert certificate["spec"]["dnsNames"] == ["preview-42.preview.example.test"]
assert certificate["spec"]["issuerRef"]["name"] == "letsencrypt-prod"
ingress = next(d for d in documents if d.get("kind") == "Ingress")
assert ingress["spec"]["tls"][0]["secretName"] == "preview-release-tls"
assert certificate["spec"]["secretName"] != ingress["spec"]["tls"][0]["secretName"]

mismatched = deepcopy(documents)
mismatched_certificate = next(d for d in mismatched if d.get("kind") == "Certificate")
mismatched_certificate["spec"]["secretName"] = "wrong-telnet-secret"
mismatched_issues = preflight.validate_hosted_telnet_tls_values(mismatched)
assert mismatched_issues == [
    "TCP Proxy Telnet TLS Certificate secretName must match its dedicated Secret name",
    "/telnet-tls must reference the dedicated Telnet TLS Secret",
], mismatched_issues

reused = deepcopy(documents)
reused_certificate = next(d for d in reused if d.get("kind") == "Certificate")
reused_certificate["spec"]["secretName"] = ingress["spec"]["tls"][0]["secretName"]
reused_issues = preflight.validate_hosted_telnet_tls_values(reused)
assert reused_issues == [
    "TCP Proxy Telnet TLS Certificate secretName must match its dedicated Secret name",
    "TCP Proxy Telnet TLS Secret must not reuse the HTTP Ingress TLS Secret",
    "/telnet-tls must reference the dedicated Telnet TLS Secret",
], reused_issues

reused_in_later_ingress_entry = deepcopy(documents)
later_ingress = next(d for d in reused_in_later_ingress_entry if d.get("kind") == "Ingress")
later_ingress["spec"]["tls"] = [
    {"secretName": "unrelated-http-secret"},
    {"secretName": "preview-release-telnet-tls"},
]
later_ingress_reuse_issues = preflight.validate_hosted_telnet_tls_values(
    reused_in_later_ingress_entry
)
assert later_ingress_reuse_issues == [
    "TCP Proxy Telnet TLS Secret must not reuse the HTTP Ingress TLS Secret"
], later_ingress_reuse_issues

cross_namespace_decoys = deepcopy(documents)
cross_namespace_decoys.extend(
    [
        {
            "kind": "Certificate",
            "metadata": {
                "name": "preview-release-telnet-tls",
                "namespace": foreign_namespace,
            },
            "spec": {"secretName": "wrong-cross-namespace-secret"},
        },
        {
            "kind": "Ingress",
            "metadata": {"name": "other-ingress", "namespace": foreign_namespace},
            "spec": {"tls": [{"secretName": "preview-release-telnet-tls"}]},
        },
        {
            "kind": "Deployment",
            "metadata": {
                "name": "tcp-proxy-service",
                "namespace": foreign_namespace,
            },
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
        "metadata": {"name": "tcp-proxy-service", "namespace": target_namespace},
        "spec": {"type": "NodePort"},
    }
)
ambiguous_issues = preflight.validate_hosted_telnet_tls_values(ambiguous_nodeports)
assert any("exactly one tcp-proxy-service NodePort Service" in issue for issue in ambiguous_issues), (
    "multiple target-namespace tcp-proxy-service NodePorts were accepted"
)

ambiguous_certificates = deepcopy(documents)
ambiguous_certificates.append(
    {
        "kind": "Certificate",
        "metadata": {"name": "other-telnet-tls", "namespace": target_namespace},
        "spec": {"secretName": "wrong-telnet-secret"},
    }
)
ambiguous_certificate_issues = preflight.validate_hosted_telnet_tls_values(
    ambiguous_certificates
)
assert ambiguous_certificate_issues == [
    "hosted TCP Proxy TLS requires exactly one dedicated -telnet-tls Certificate"
], ambiguous_certificate_issues

disabled_documents = load_yaml_mappings(Path(os.environ["DISABLED_RENDERED"]))
assert not any(d.get("kind") == "Certificate" for d in disabled_documents), "disabled TLS still renders a Certificate"
disabled_deployment = next(d for d in disabled_documents if d.get("kind") == "Deployment" and d["metadata"]["name"] == "tcp-proxy-service")
disabled_env = {entry["name"]: entry.get("value") for entry in disabled_deployment["spec"]["template"]["spec"]["containers"][0].get("env", [])}
assert "TCP_PROXY_TLS_ENABLED" not in disabled_env, "disabled TLS still renders enablement"
assert disabled_env["TCP_PROXY_TELNET_MODE"] == "PLAINTEXT", "disabled TLS discarded the configured Telnet mode"
configured_enabled_documents = load_yaml_mappings(
    Path(os.environ["CONFIGURED_ENABLED_RENDERED"])
)
configured_enabled_deployment = next(d for d in configured_enabled_documents if d.get("kind") == "Deployment" and d["metadata"]["name"] == "tcp-proxy-service")
configured_enabled_env = {entry["name"]: entry.get("value") for entry in configured_enabled_deployment["spec"]["template"]["spec"]["containers"][0].get("env", [])}
assert configured_enabled_env["TCP_PROXY_TELNET_MODE"] == "DIRECT_TLS", "enabled TLS did not enforce canonical direct mode"
managed_env_documents = load_yaml_mappings(Path(os.environ["MANAGED_ENV_RENDERED"]))
managed_env_deployment = next(
    d for d in managed_env_documents
    if d.get("kind") == "Deployment" and d["metadata"]["name"] == "tcp-proxy-service"
)
managed_env_entries = managed_env_deployment["spec"]["template"]["spec"]["containers"][0].get("env", [])
managed_env_names = [entry.get("name") for entry in managed_env_entries]
for managed_name in expected_telnet_env_order:
    assert managed_env_names.count(managed_name) == 1, (
        f"controller-managed {managed_name} was shadowed by extraEnv"
    )
managed_env = {entry["name"]: entry.get("value") for entry in managed_env_entries}
assert managed_env["TCP_PROXY_TLS_ENABLED"] == "true"
assert managed_env["TCP_PROXY_TLS_CERT"] == "/telnet-tls/tls.crt"
assert managed_env["TCP_PROXY_TLS_KEY"] == "/telnet-tls/tls.key"
assert managed_env["TCP_PROXY_TELNET_MODE"] == "DIRECT_TLS"
assert managed_env["TELNET_CONTRACT_PASSTHROUGH"] == "preserved", "unrelated extraEnv entry was filtered"
empty_pull_secret_documents = load_yaml_mappings(
    Path(os.environ["EMPTY_PULL_SECRETS_RENDERED"])
)
assert sum(d.get("kind") == "Deployment" for d in empty_pull_secret_documents) > 0, "empty imagePullSecrets omitted Deployments"
spring_profile_documents = load_yaml_mappings(Path(os.environ["SPRING_PROFILE_RENDERED"]))
spring_profile_deployment = next(
    d for d in spring_profile_documents
    if d.get("kind") == "Deployment" and d["metadata"]["name"] == "tcp-proxy-service"
)
spring_profile_telnet_env_order = [
    entry["name"]
    for entry in spring_profile_deployment["spec"]["template"]["spec"]["containers"][0].get("env", [])
    if entry.get("name") in expected_telnet_env_order
]
assert spring_profile_telnet_env_order == expected_telnet_env_order, spring_profile_telnet_env_order
spring_profile_env = {
    entry["name"]: entry.get("value")
    for entry in spring_profile_deployment["spec"]["template"]["spec"]["containers"][0].get("env", [])
}
assert spring_profile_env["TCP_PROXY_TLS_ENABLED"] == "true", "spring-profile Telnet TLS env was omitted"
assert spring_profile_env["TCP_PROXY_TLS_CERT"] == "/telnet-tls/tls.crt"
assert spring_profile_env["TCP_PROXY_TLS_KEY"] == "/telnet-tls/tls.key"
assert spring_profile_env["TCP_PROXY_TELNET_MODE"] == "DIRECT_TLS"
assert spring_profile_env["TELNET_CONTRACT_PASSTHROUGH"] == "preserved", "spring-profile unrelated extraEnv entry was filtered"
print("hosted Telnet direct-TLS chart contract passed")
PY
