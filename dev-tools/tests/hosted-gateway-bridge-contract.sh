#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

RENDERED="$TMP_DIR/rendered.yaml"
python3 "$ROOT_DIR/dev-tools/hosted/preview/render-preview-values.py" \
  "$ROOT_DIR/k8s/helm/firemud/values-hosted-shared.example.yaml" \
  "$TMP_DIR/preview-values.yaml" \
  123 pr-123 pr-123 preview-123.example.test image-tag 32123
helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/preview-values.yaml" \
  --namespace pr-123 \
  >"$RENDERED"

FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  python3 "$ROOT_DIR/dev-tools/deploy/preflight.py" hosted-bridge \
    "$RENDERED" pr-123 pr-123 >"$TMP_DIR/preflight.json"

DEV_RENDERED="$TMP_DIR/dev-rendered.yaml"
python3 "$ROOT_DIR/dev-tools/hosted/dev-demo/render-dev-demo-values.py" \
  "$ROOT_DIR/k8s/helm/firemud/values-hosted-shared.example.yaml" \
  "$TMP_DIR/dev-values.yaml" \
  dev dev dev.example.test image-tag 32023
helm template dev "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/dev-values.yaml" \
  --namespace dev \
  >"$DEV_RENDERED"
FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  python3 "$ROOT_DIR/dev-tools/deploy/preflight.py" hosted-bridge \
    "$DEV_RENDERED" dev dev >"$TMP_DIR/dev-preflight.json"

PREVIEW_PR_NUMBER_ERROR="preview.prNumber is required when Gateway WebSocket TLS is enabled"
for invalid_pr_number in missing empty; do
  INVALID_PR_VALUES="$TMP_DIR/preview-values-pr-number-$invalid_pr_number.yaml"
  cp "$TMP_DIR/preview-values.yaml" "$INVALID_PR_VALUES"
  python3 - "$INVALID_PR_VALUES" "$invalid_pr_number" <<'PY'
import sys
from pathlib import Path

import yaml

path = Path(sys.argv[1])
values = yaml.safe_load(path.read_text(encoding="utf-8"))
if sys.argv[2] == "missing":
    values["preview"].pop("prNumber")
else:
    values["preview"]["prNumber"] = ""
path.write_text(yaml.safe_dump(values, sort_keys=False), encoding="utf-8")
PY
  if helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
    -f "$INVALID_PR_VALUES" \
    --namespace pr-123 >/dev/null 2>"$TMP_DIR/invalid-pr-number-$invalid_pr_number.err"; then
    echo "Gateway WebSocket TLS rendered with $invalid_pr_number preview.prNumber" >&2
    exit 1
  fi
  if ! grep -Fq "$PREVIEW_PR_NUMBER_ERROR" "$TMP_DIR/invalid-pr-number-$invalid_pr_number.err"; then
    echo "chart did not report the expected $invalid_pr_number preview.prNumber diagnostic" >&2
    sed -n '1,20p' "$TMP_DIR/invalid-pr-number-$invalid_pr_number.err" >&2
    exit 1
  fi
done

GATEWAY_WS_TARGET_PORT_ERROR="previewStack.gatewayWsTls.targetPort is required when Gateway WebSocket TLS is enabled"
for template in templates/apps.yaml templates/network-policies.yaml; do
  error_file="$TMP_DIR/missing-gateway-target-port-$(basename "$template").err"
  if helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
    -f "$TMP_DIR/preview-values.yaml" \
    --set-string 'previewStack.gatewayWsTls.targetPort=' \
    --show-only "$template" \
    --namespace pr-123 >/dev/null 2>"$error_file"; then
    echo "$template rendered Gateway WebSocket TLS with no targetPort" >&2
    exit 1
  fi
  if ! grep -Fq "$GATEWAY_WS_TARGET_PORT_ERROR" "$error_file"; then
    echo "$template did not report the expected missing targetPort diagnostic" >&2
    sed -n '1,20p' "$error_file" >&2
    exit 1
  fi
done

GATEWAY_WS_SERVICE_PORT_ERROR="previewStack.gatewayWsTls.servicePort is required when Gateway WebSocket TLS is enabled"
if helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/preview-values.yaml" \
  --set-string 'previewStack.gatewayWsTls.servicePort=' \
  --show-only templates/apps.yaml \
  --namespace pr-123 >/dev/null 2>"$TMP_DIR/missing-gateway-service-port.err"; then
  echo "apps template rendered Gateway WebSocket TLS with no servicePort" >&2
  exit 1
fi
if ! grep -Fq "$GATEWAY_WS_SERVICE_PORT_ERROR" "$TMP_DIR/missing-gateway-service-port.err"; then
  echo "apps template did not report the expected missing servicePort diagnostic" >&2
  sed -n '1,20p' "$TMP_DIR/missing-gateway-service-port.err" >&2
  exit 1
fi

NON_DEFAULT_SERVICE_PORT_RENDERED="$TMP_DIR/non-default-service-port.yaml"
helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/preview-values.yaml" \
  --set previewStack.gatewayWsTls.servicePort=444 \
  --show-only templates/apps.yaml \
  --namespace pr-123 >"$NON_DEFAULT_SERVICE_PORT_RENDERED"
python3 - "$NON_DEFAULT_SERVICE_PORT_RENDERED" <<'PY'
import sys
from pathlib import Path

import yaml

documents = [
    document
    for document in yaml.safe_load_all(Path(sys.argv[1]).read_text(encoding="utf-8"))
    if isinstance(document, dict)
]
proxy = next(
    document
    for document in documents
    if document.get("kind") == "Deployment"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service"
)
proxy_env = {
    entry["name"]: entry.get("value")
    for entry in proxy["spec"]["template"]["spec"]["containers"][0]["env"]
}
assert proxy_env["GATEWAY_WS_URL"] == (
    "wss://spring-cloud-gateway-mtls.pr-123.svc.cluster.local:444/ws/game"
)
gateway_service = next(
    document
    for document in documents
    if document.get("kind") == "Service"
    and document.get("metadata", {}).get("name") == "spring-cloud-gateway-mtls"
)
assert gateway_service["spec"]["ports"] == [
    {"name": "wss-mtls", "port": 444, "targetPort": 8443, "protocol": "TCP"}
]
PY

for context in ci-static operator; do
  set +e
  invalid_output="$(
    FIREMUD_PREFLIGHT_CONTEXT="$context" \
      python3 "$ROOT_DIR/dev-tools/deploy/preflight.py" hosted-bridge \
        "$RENDERED" production prod 2>&1
  )"
  invalid_status=$?
  set -e
  if [[ "$invalid_status" -ne 1 ]] ||
    [[ "$invalid_output" != *"hosted bridge identity must be exactly dev/dev"* ]]; then
    echo "$context CLI preflight did not reject a non-hosted identity before validation" >&2
    exit 1
  fi
done

python3 - "$ROOT_DIR" "$RENDERED" "$DEV_RENDERED" <<'PY'
import copy
import importlib.util
import io
import json
import pathlib
import subprocess
import sys
from unittest import mock

import yaml

root = pathlib.Path(sys.argv[1])
rendered_path = pathlib.Path(sys.argv[2])
dev_rendered_path = pathlib.Path(sys.argv[3])
spec = importlib.util.spec_from_file_location(
    "hosted_bridge_preflight", root / "dev-tools/deploy/preflight.py"
)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)

documents = [
    document
    for document in yaml.safe_load_all(rendered_path.read_text(encoding="utf-8"))
    if isinstance(document, dict)
]
for document in documents:
    document.setdefault("metadata", {}).setdefault("namespace", "pr-123")

dev_documents = [
    document
    for document in yaml.safe_load_all(dev_rendered_path.read_text(encoding="utf-8"))
    if isinstance(document, dict)
]
for environment, rendered_documents in (
    ("preview", documents),
    ("dev-demo", dev_documents),
):
    tcp_proxy_resources = [
        document
        for document in rendered_documents
        if document.get("kind") in {"Deployment", "Service"}
        and document.get("metadata", {}).get("name") == "tcp-proxy-service"
    ]
    if len(tcp_proxy_resources) != 2 or any(
        document.get("metadata", {}).get("labels", {}).get(
            "firemud.dev/certificate-identity-mode"
        )
        != "hosted-controller"
        for document in tcp_proxy_resources
    ):
        raise SystemExit(
            f"{environment} workflow-rendered artifact did not retain hosted-controller ownership"
        )

expected = module.hosted_bridge_expected_bindings("pr-123", "pr-123")
values, issues = module.validate_gateway_ws_values(documents, expected)
if issues or values != [
    "wss://spring-cloud-gateway-mtls.pr-123.svc.cluster.local:443/ws/game"
]:
    raise SystemExit(f"canonical hosted bridge render failed validation: {issues}")

if module.hosted_bridge_expected_bindings("dev", "dev")["environment"] != "dev-demo-cluster":
    raise SystemExit("canonical dev hosted bridge identity was rejected")

invalid_identities = (
    ("production", "prod"),
    ("other", "other"),
    ("pr-0", "pr-0"),
    ("pr-01", "pr-01"),
    ("pr-1", "pr-2"),
    ("pr-1-preview", "pr-1-preview"),
    ("pr-1foo", "pr-1foo"),
    ("preview-pr-1", "preview-pr-1"),
    ("pr-" + "1" * 61, "pr-" + "1" * 61),
    ("dev", "dev-demo"),
)
for namespace, release_name in invalid_identities:
    try:
        module.hosted_bridge_expected_bindings(namespace, release_name)
    except ValueError:
        pass
    else:
        raise SystemExit(
            f"invalid hosted bridge identity was classified: {namespace}/{release_name}"
        )

for context in ("ci-static", "operator"):
    for namespace, release_name in invalid_identities:
        with mock.patch.object(
            module,
            "secret_keys_lookup_failure",
            side_effect=AssertionError("invalid identity reached Secret lookup"),
        ), mock.patch.object(module.sys, "stderr", io.StringIO()):
            try:
                module.hosted_bridge_preflight(
                    rendered_path, namespace, release_name, context
                )
            except SystemExit as exc:
                if exc.code != 1:
                    raise SystemExit(
                        f"invalid hosted bridge identity exited unexpectedly: {exc.code}"
                    ) from exc
            else:
                raise SystemExit(
                    f"{context} preflight accepted invalid identity: "
                    f"{namespace}/{release_name}"
                )

with mock.patch.object(
    module.subprocess,
    "run",
    return_value=subprocess.CompletedProcess(
        args=[],
        returncode=0,
        stdout='{"data":{"tls.crt":"redacted","tls.key":"redacted"}}',
        stderr="",
    ),
):
    missing_key_issue = module.secret_keys_lookup_failure(
        "pr-123-tcp-proxy-bridge",
        "pr-123",
        {"tls.crt", "tls.key", "ca.crt"},
    )
if missing_key_issue is None or "missing keys: ca.crt" not in missing_key_issue:
    raise SystemExit("operator preflight accepted an incomplete controller-projected Secret")

operator_secret_lookups = []


def record_operator_secret_lookup(secret_name, namespace, required_keys):
    operator_secret_lookups.append((secret_name, namespace, required_keys))
    return None


with mock.patch.object(
    module,
    "secret_keys_lookup_failure",
    side_effect=record_operator_secret_lookup,
), mock.patch.object(module.sys, "stdout", io.StringIO()):
    operator_result = module.hosted_bridge_preflight(
        rendered_path, "pr-123", "pr-123", "operator"
    )
if operator_result != 0:
    raise SystemExit("operator preflight rejected ready controller-projected Secrets")
if operator_secret_lookups != [
    (
        "pr-123-gateway-internal-ws",
        "pr-123",
        {"tls.crt", "tls.key", "ca.crt"},
    ),
    (
        "pr-123-tcp-proxy-bridge",
        "pr-123",
        {"tls.crt", "tls.key", "ca.crt"},
    ),
    (
        "pr-123-telnet-tls",
        "pr-123",
        {"tls.crt", "tls.key"},
    ),
]:
    raise SystemExit(
        f"operator preflight did not verify every controller projection: "
        f"{operator_secret_lookups}"
    )

forged_standalone = copy.deepcopy(documents)
for document in forged_standalone:
    if (
        document.get("kind") in {"Deployment", "Service"}
        and document.get("metadata", {}).get("name") == "tcp-proxy-service"
    ):
        document["metadata"]["labels"][
            "firemud.dev/certificate-identity-mode"
        ] = "standalone"
forged_standalone.append(
    {
        "apiVersion": "cert-manager.io/v1",
        "kind": "Certificate",
        "metadata": {
            "name": "pr-123-telnet-tls",
            "namespace": "pr-123",
            "labels": {
                "app.kubernetes.io/name": "firemud",
                "app.kubernetes.io/instance": "pr-123",
                "app.kubernetes.io/managed-by": "Helm",
            },
        },
        "spec": {
            "secretName": "pr-123-telnet-tls",
            "privateKey": {"algorithm": "RSA", "encoding": "PKCS8"},
            "dnsNames": ["preview-123.example.test"],
            "issuerRef": {"name": "letsencrypt-prod", "kind": "ClusterIssuer"},
        },
    }
)
forged_path = rendered_path.parent / "forged-standalone.yaml"
forged_path.write_text(
    yaml.safe_dump_all(forged_standalone, sort_keys=False), encoding="utf-8"
)
forged_static_output = io.StringIO()
with mock.patch.object(module.sys, "stdout", forged_static_output):
    forged_static_result = module.hosted_bridge_preflight(
        forged_path, "pr-123", "pr-123", "ci-static"
    )
forged_static_policy = json.loads(forged_static_output.getvalue())
if (
    forged_static_result != 1
    or forged_static_policy.get("policyId") != "PREFLIGHT-BRIDGE-001"
    or forged_static_policy.get("status") != "fail"
    or "certificate identity mode must be hosted-controller"
    not in forged_static_policy.get("message", "")
):
    raise SystemExit(
        "hosted preflight accepted artifact-controlled standalone certificate ownership"
    )

forged_operator_lookups = []


def record_forged_operator_lookup(secret_name, namespace, required_keys):
    forged_operator_lookups.append((secret_name, namespace, required_keys))
    return None


with mock.patch.object(
    module,
    "secret_keys_lookup_failure",
    side_effect=record_forged_operator_lookup,
), mock.patch.object(module.sys, "stdout", io.StringIO()):
    forged_operator_result = module.hosted_bridge_preflight(
        forged_path, "pr-123", "pr-123", "operator"
    )
if forged_operator_result != 1 or (
    "pr-123-telnet-tls",
    "pr-123",
    {"tls.crt", "tls.key"},
) not in forged_operator_lookups:
    raise SystemExit(
        "forged standalone markers bypassed the operator Telnet Secret expectation"
    )

resource_kinds = {
    (document.get("kind"), document.get("metadata", {}).get("name"))
    for document in documents
}
for forbidden in (
    ("Certificate", "pr-123-gateway-internal-ws"),
    ("Certificate", "pr-123-tcp-proxy-bridge"),
    ("Certificate", "pr-123-telnet-tls"),
    ("Issuer", "pr-123-gateway-internal-ws"),
    ("Secret", "pr-123-gateway-internal-ws"),
    ("Secret", "pr-123-tcp-proxy-bridge"),
):
    if forbidden in resource_kinds:
        raise SystemExit(f"chart took ownership of controller-projected identity: {forbidden}")

gateway = next(
    document
    for document in documents
    if document.get("kind") == "Deployment"
    and document.get("metadata", {}).get("name") == "spring-cloud-gateway"
)
proxy = next(
    document
    for document in documents
    if document.get("kind") == "Deployment"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service"
)
if gateway.get("spec", {}).get("strategy") != {"type": "Recreate"}:
    raise SystemExit("Gateway identity withdrawal can retain a stale rolling-update pod")
if proxy.get("spec", {}).get("strategy") != {"type": "Recreate"}:
    raise SystemExit("TCP Proxy identity withdrawal can retain a stale rolling-update pod")

strategy_issue = (
    "TCP Proxy bridge Deployment strategy must be Recreate so identity withdrawal "
    "cannot retain stale pods"
)
multi_container_proxy = copy.deepcopy(documents)
proxy_copy = next(
    document
    for document in multi_container_proxy
    if document.get("kind") == "Deployment"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service"
)
second_proxy_container = copy.deepcopy(
    proxy_copy["spec"]["template"]["spec"]["containers"][0]
)
second_proxy_container["name"] = "tcp-proxy-shadow-service"
proxy_copy["spec"]["template"]["spec"]["containers"].append(second_proxy_container)
_, recreate_issues = module.validate_gateway_ws_values(multi_container_proxy, expected)
if strategy_issue in recreate_issues:
    raise SystemExit(
        f"multi-container Recreate bridge reported a strategy issue: {recreate_issues}"
    )
proxy_copy["spec"]["strategy"] = {"type": "RollingUpdate"}
_, rolling_update_issues = module.validate_gateway_ws_values(
    multi_container_proxy, expected
)
if rolling_update_issues.count(strategy_issue) != 1:
    raise SystemExit(
        "multi-container non-Recreate bridge did not report exactly one strategy issue: "
        f"{rolling_update_issues}"
    )


def mutate_env(documents_to_mutate, workload, name, value=None, remove=False):
    deployment = next(
        document
        for document in documents_to_mutate
        if document.get("kind") == "Deployment"
        and document.get("metadata", {}).get("name") == workload
    )
    container = deployment["spec"]["template"]["spec"]["containers"][0]
    if remove:
        container["env"] = [
            entry for entry in container.get("env", []) if entry.get("name") != name
        ]
        return
    next(entry for entry in container["env"] if entry.get("name") == name)["value"] = value


def append_env(documents_to_mutate, workload, name, value):
    deployment = next(
        document
        for document in documents_to_mutate
        if document.get("kind") == "Deployment"
        and document.get("metadata", {}).get("name") == workload
    )
    deployment["spec"]["template"]["spec"]["containers"][0]["env"].append(
        {"name": name, "value": value}
    )


for label, mutation, expected_fragment in (
    (
        "plaintext",
        lambda docs: mutate_env(
            docs,
            "tcp-proxy-service",
            "GATEWAY_WS_URL",
            "ws://spring-cloud-gateway/ws/game",
        ),
        "does not match canonical",
    ),
    (
        "missing-listener",
        lambda docs: mutate_env(
            docs,
            "spring-cloud-gateway",
            "FIREMUD_GATEWAY_TCP_PROXY_TLS_ENABLED",
            remove=True,
        ),
        "TLS_ENABLED must be exactly",
    ),
    (
        "development-profile",
        lambda docs: mutate_env(
            docs,
            "spring-cloud-gateway",
            "FIREMUD_GATEWAY_TCP_PROXY_TRUST_PROFILE",
            "development_cidr",
        ),
        "TRUST_PROFILE must be exactly 'production_uri'",
    ),
    (
        "legacy-cidr-fallback",
        lambda docs: append_env(
            docs,
            "spring-cloud-gateway",
            "FIREMUD_GATEWAY_HEADER_TRUST_TCP_PROXY_ALLOW_INSECURE_HEADERS_FROM_TRUSTED_CIDRS",
            "true",
        ),
        "must not configure legacy TCP Proxy header trust",
    ),
):
    mutated = copy.deepcopy(documents)
    mutation(mutated)
    _, mutation_issues = module.validate_gateway_ws_values(mutated, expected)
    if not any(expected_fragment in issue for issue in mutation_issues):
        raise SystemExit(f"{label} mutation was accepted: {mutation_issues}")

missing_credentials = copy.deepcopy(documents)
proxy_copy = next(
    document
    for document in missing_credentials
    if document.get("kind") == "Deployment"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service"
)
proxy_container = proxy_copy["spec"]["template"]["spec"]["containers"][0]
proxy_container["volumeMounts"] = [
    mount
    for mount in proxy_container["volumeMounts"]
    if mount.get("name") != "gateway-ws-client-tls"
]
_, credential_issues = module.validate_gateway_ws_values(missing_credentials, expected)
if not any("requires exactly one /gateway-ws-client-tls mount" in issue for issue in credential_issues):
    raise SystemExit(f"missing bridge credentials were accepted: {credential_issues}")

shared_telnet_identity = copy.deepcopy(documents)
proxy_copy = next(
    document
    for document in shared_telnet_identity
    if document.get("kind") == "Deployment"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service"
)
next(
    volume
    for volume in proxy_copy["spec"]["template"]["spec"]["volumes"]
    if volume.get("name") == "telnet-tls"
)["secret"]["secretName"] = "pr-123-tcp-proxy-bridge"
_, shared_identity_issues = module.validate_gateway_ws_values(
    shared_telnet_identity, expected
)
if not any("distinct from the Telnet TLS Secret" in issue for issue in shared_identity_issues):
    raise SystemExit(
        f"bridge client reused the Telnet identity without failing: {shared_identity_issues}"
    )

proxy_policy = next(
    document
    for document in documents
    if document.get("kind") == "NetworkPolicy"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service-egress"
)
collector_rules = [
    rule
    for rule in proxy_policy["spec"]["egress"]
    if rule.get("to")
    == [{"podSelector": {"matchLabels": {"app": "otel-collector"}}}]
]
if len(collector_rules) != 1 or collector_rules[0].get("ports") != [
    {"protocol": "TCP", "port": 4317}
]:
    raise SystemExit(
        "TCP Proxy egress does not narrowly allow same-namespace OTLP to app=otel-collector"
    )
if any(
    rule.get("to") == [{"podSelector": {"matchLabels": {"app": "postgres"}}}]
    for rule in proxy_policy["spec"]["egress"]
):
    raise SystemExit("TCP Proxy egress unexpectedly allows direct Postgres access")

widened_policy = copy.deepcopy(documents)
gateway_policy = next(
    document
    for document in widened_policy
    if document.get("kind") == "NetworkPolicy"
    and document.get("metadata", {}).get("name") == "spring-cloud-gateway-ingress"
)
gateway_policy["spec"]["ingress"][0]["from"] = [{"podSelector": {}}]
_, policy_issues = module.validate_gateway_ws_values(widened_policy, expected)
if not any("exactly one app=tcp-proxy-service peer rule" in issue for issue in policy_issues):
    raise SystemExit(f"widened Gateway listener policy was accepted: {policy_issues}")

widened_port_range = copy.deepcopy(documents)
gateway_policy = next(
    document
    for document in widened_port_range
    if document.get("kind") == "NetworkPolicy"
    and document.get("metadata", {}).get("name") == "spring-cloud-gateway-ingress"
)
gateway_policy["spec"]["ingress"][0]["ports"] = [
    {"protocol": "TCP", "port": 8000, "endPort": 9000}
]
_, range_issues = module.validate_gateway_ws_values(widened_port_range, expected)
if not any("listener rule must be exactly TCP 8443" in issue for issue in range_issues):
    raise SystemExit(f"widened Gateway listener port range was accepted: {range_issues}")

for label, policy_name, direction, peer in (
    (
        "Gateway ingress",
        "spring-cloud-gateway-ingress",
        "ingress",
        "tcp-proxy-service",
    ),
    (
        "TCP Proxy egress",
        "tcp-proxy-service-egress",
        "egress",
        "spring-cloud-gateway",
    ),
):
    extra_port_policy = copy.deepcopy(documents)
    policy = next(
        document
        for document in extra_port_policy
        if document.get("kind") == "NetworkPolicy"
        and document.get("metadata", {}).get("name") == policy_name
    )
    canonical_peer_rule = next(
        rule
        for rule in policy["spec"][direction]
        if module.canonical_bridge_peer(rule, peer)
    )
    canonical_peer_rule["ports"] = [
        {"protocol": "TCP", "port": 8443},
        {"protocol": "TCP", "port": 8080},
    ]
    _, extra_port_issues = module.validate_gateway_ws_values(
        extra_port_policy, expected
    )
    if not any(
        f"{label} listener rule must be exactly TCP 8443" in issue
        for issue in extra_port_issues
    ):
        raise SystemExit(
            f"{label} canonical peer rule with an extra port was accepted: "
            f"{extra_port_issues}"
        )

for label, mutation in (
    (
        "omitted-ports",
        lambda rule: rule.pop("ports"),
    ),
    (
        "empty-ports",
        lambda rule: rule.__setitem__("ports", []),
    ),
    (
        "missing-port-value",
        lambda rule: rule.__setitem__("ports", [{"protocol": "TCP"}]),
    ),
):
    all_port_policy = copy.deepcopy(documents)
    gateway_policy = next(
        document
        for document in all_port_policy
        if document.get("kind") == "NetworkPolicy"
        and document.get("metadata", {}).get("name") == "spring-cloud-gateway-ingress"
    )
    mutation(gateway_policy["spec"]["ingress"][0])
    _, all_port_issues = module.validate_gateway_ws_values(
        all_port_policy, expected
    )
    if not any("must not contain an all-port rule" in issue for issue in all_port_issues):
        raise SystemExit(f"{label} Gateway policy was accepted: {all_port_issues}")

for label, ports, expected_fragment in (
    (
        "repeated-listener-qualifiers",
        [
            {"protocol": "TCP", "port": 8443},
            {"protocol": "TCP", "port": 8000, "endPort": 9000},
        ],
        "listener rule must be exactly TCP 8443",
    ),
    (
        "repeated-malformed-qualifiers",
        [{"protocol": "TCP"}, {"protocol": "TCP"}],
        "must not contain an all-port rule",
    ),
):
    repeated_qualifier_policy = copy.deepcopy(documents)
    gateway_policy = next(
        document
        for document in repeated_qualifier_policy
        if document.get("kind") == "NetworkPolicy"
        and document.get("metadata", {}).get("name")
        == "spring-cloud-gateway-ingress"
    )
    gateway_policy["spec"]["ingress"][0]["ports"] = ports
    _, repeated_qualifier_issues = module.validate_gateway_ws_values(
        repeated_qualifier_policy, expected
    )
    if not any(
        expected_fragment in issue for issue in repeated_qualifier_issues
    ) or any(
        "exactly one app=tcp-proxy-service peer rule" in issue
        for issue in repeated_qualifier_issues
    ):
        raise SystemExit(
            f"{label} counted one Gateway policy rule more than once: "
            f"{repeated_qualifier_issues}"
        )

for label, policy_name, policy_types, expected_fragment in (
    (
        "gateway-direction-disabled",
        "spring-cloud-gateway-ingress",
        ["Egress"],
        "Gateway ingress must allow TCP 8443",
    ),
    (
        "proxy-direction-disabled",
        "tcp-proxy-service-egress",
        ["Ingress"],
        "TCP Proxy egress must allow TCP 8443",
    ),
):
    wrong_direction_policy = copy.deepcopy(documents)
    policy = next(
        document
        for document in wrong_direction_policy
        if document.get("kind") == "NetworkPolicy"
        and document.get("metadata", {}).get("name") == policy_name
    )
    policy["spec"]["policyTypes"] = policy_types
    _, direction_issues = module.validate_gateway_ws_values(
        wrong_direction_policy, expected
    )
    if not any(expected_fragment in issue for issue in direction_issues):
        raise SystemExit(f"{label} policy was accepted: {direction_issues}")

for workflow_name, render_name in (
    ("preview.yml", "preview-rendered.yaml"),
    ("dev-demo.yml", "dev-demo-rendered.yaml"),
):
    source = (root / ".github/workflows" / workflow_name).read_text(encoding="utf-8")
    render_index = source.find(f">/tmp/{render_name}")
    preflight_index = source.find("python3 ./dev-tools/deploy/preflight.py hosted-bridge", render_index)
    operator_index = source.find("FIREMUD_PREFLIGHT_CONTEXT=operator", render_index)
    dry_run_index = source.find("kubectl apply --dry-run=server", render_index)
    deploy_index = source.find("helm upgrade --install", render_index)
    if min(render_index, operator_index, preflight_index, dry_run_index, deploy_index) < 0:
        raise SystemExit(f"{workflow_name} is missing hosted bridge preflight wiring")
    if not render_index < operator_index < preflight_index < dry_run_index < deploy_index:
        raise SystemExit(f"{workflow_name} does not fail bridge preflight before deploy")
PY

echo "Hosted Gateway bridge Helm, NetworkPolicy, preflight, and workflow contracts passed"
