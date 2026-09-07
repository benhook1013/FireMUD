#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

RENDERED="$TMP_DIR/rendered.yaml"
helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
  -f "$ROOT_DIR/k8s/helm/firemud/values-hosted-shared.example.yaml" \
  --namespace pr-123 \
  --set previewStack.telnetTls.secretName=pr-123-telnet-tls \
  >"$RENDERED"

FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  python3 "$ROOT_DIR/dev-tools/deploy/preflight.py" hosted-bridge \
    "$RENDERED" pr-123 pr-123 >"$TMP_DIR/preflight.json"

DEV_RENDERED="$TMP_DIR/dev-rendered.yaml"
helm template dev "$ROOT_DIR/k8s/helm/firemud" \
  -f "$ROOT_DIR/k8s/helm/firemud/values-hosted-shared.example.yaml" \
  --namespace dev \
  --set preview.prNumber=0 \
  --set previewStack.telnetTls.secretName=dev-telnet-tls \
  >"$DEV_RENDERED"
FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  python3 "$ROOT_DIR/dev-tools/deploy/preflight.py" hosted-bridge \
    "$DEV_RENDERED" dev dev >"$TMP_DIR/dev-preflight.json"

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

python3 - "$ROOT_DIR" "$RENDERED" <<'PY'
import copy
import importlib.util
import io
import pathlib
import subprocess
import sys
from unittest import mock

import yaml

root = pathlib.Path(sys.argv[1])
rendered_path = pathlib.Path(sys.argv[2])
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

expected = module.hosted_bridge_expected_bindings("pr-123", "pr-123")
values, issues = module.validate_gateway_ws_values(documents, expected)
if issues or values != [
    "wss://spring-cloud-gateway-mtls.pr-123.svc.cluster.local/ws/game"
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

resource_kinds = {
    (document.get("kind"), document.get("metadata", {}).get("name"))
    for document in documents
}
for forbidden in (
    ("Certificate", "pr-123-gateway-internal-ws"),
    ("Certificate", "pr-123-tcp-proxy-bridge"),
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
