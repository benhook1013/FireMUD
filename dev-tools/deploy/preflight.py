#!/usr/bin/env python3

from __future__ import annotations

import datetime as dt
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml


USAGE = """Usage: preflight.py <staging|production|hobby-self-hosted>

Environment variables:
  FIREMUD_PREFLIGHT_CONTEXT          Context for applicability (default: operator)
                                     allowed: operator, ci-static
  FIREMUD_PREFLIGHT_OUTPUT           Optional output report path
  FIREMUD_DEPLOYMENT_REF             Optional deployment ref token for report naming
  FIREMUD_PREFLIGHT_RENDER_PATH      Required for hobby-self-hosted; explicit manifest/render path
  FIREMUD_PREFLIGHT_WAIVER           Optional waiver JSON path with fields:
                                     approver, ticket, waivedPolicyIds[]
  FIREMUD_PROMOTION_ATTESTATION      Required in operator production context; path to attestation JSON
  FIREMUD_BACKUP_READINESS_EVIDENCE  Required for production roll-forward-only promotions; path to backup-readiness JSON
  FIREMUD_TRAFFIC_OPEN_EVENT         Optional traffic-open gate: first-live or reopen
  FIREMUD_TRAFFIC_OPEN_EVIDENCE      Optional hobby traffic-open evidence path
"""


@dataclass
class CheckResult:
    policy_id: str
    required: bool
    status: str
    message: str


NON_WAIVABLE_READINESS_GATES = {
    "PREFLIGHT-BACKUP-001",
    "PREFLIGHT-BACKUP-002",
}


def fail(message: str) -> "NoReturn":
    print(message, file=sys.stderr)
    raise SystemExit(1)


def usage() -> "NoReturn":
    print(USAGE, file=sys.stderr)
    raise SystemExit(1)


def run(args: list[str]) -> str:
    result = subprocess.run(args, check=False, capture_output=True, text=True)
    if result.returncode != 0:
        stderr = result.stderr.strip()
        stdout = result.stdout.strip()
        detail = stderr or stdout or f"command failed: {' '.join(args)}"
        fail(detail)
    return result.stdout


def repo_root() -> Path:
    return Path(run(["git", "rev-parse", "--show-toplevel"]).strip())


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def load_yaml(path: Path) -> Any:
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def parse_documents(rendered_text: str) -> list[dict[str, Any]]:
    return [doc for doc in yaml.safe_load_all(rendered_text) if isinstance(doc, dict)]


def resolve_repo_path(root_dir: Path, ref: str) -> Path:
    path = Path(ref)
    return path if path.is_absolute() else root_dir / ref


def load_preflight_report(
    path_ref: str,
    environment: str,
    expected_bindings_ref: str,
    deployment_ref: str,
    root_dir: Path,
) -> tuple[str, str]:
    if not path_ref:
        return ("fail", f"{environment.capitalize()} traffic-open evidence missing preflightReportPath")
    path = resolve_repo_path(root_dir, path_ref)
    if not path.exists():
        return ("fail", f"{environment.capitalize()} preflight report not found: {path_ref}")
    try:
        report = load_json(path)
    except Exception as exc:
        return ("fail", f"{environment.capitalize()} preflight report unreadable: {exc}")
    if report.get("environment") != environment:
        return ("fail", f"{environment.capitalize()} preflight report must target {environment}")
    if report.get("expectedBindingsRef") != expected_bindings_ref:
        return ("fail", f"{environment.capitalize()} preflight report expectedBindingsRef mismatch")
    deployment_ref_obj = report.get("deploymentRef", {})
    manifest_ref = ""
    overlay_sha = ""
    if isinstance(deployment_ref_obj, dict):
        manifest_ref = str(deployment_ref_obj.get("manifestRef", ""))
        overlay_sha = str(deployment_ref_obj.get("overlayCommitSha", ""))
    if deployment_ref not in {manifest_ref, overlay_sha}:
        return ("fail", f"{environment.capitalize()} preflight report deploymentRef mismatch")
    preflight_results = report.get("checkResults")
    if not isinstance(preflight_results, list) or not preflight_results:
        return ("fail", f"{environment.capitalize()} preflight report missing checkResults")
    required_failures = [
        check.get("policyId")
        for check in preflight_results
        if isinstance(check, dict)
        and check.get("status") == "fail"
        and check.get("policyId") != "PREFLIGHT-DIGEST-002"
    ]
    if required_failures:
        return (
            "fail",
            f"{environment.capitalize()} preflight report contains failing required checks: "
            + ", ".join(required_failures),
        )
    return ("pass", "")


def walk(node: Any):
    if isinstance(node, dict):
        yield node
        for value in node.values():
            yield from walk(value)
    elif isinstance(node, list):
        for item in node:
            yield from walk(item)


def get(data: Any, dotted: str) -> Any:
    cur = data
    for part in dotted.split("."):
        if not isinstance(cur, dict) or part not in cur:
            return None
        cur = cur[part]
    return cur


def player_facing_environments() -> tuple[str, ...]:
    return ("staging", "production", "hobby-self-hosted")


def normalize_binding_value(raw: Any) -> tuple[str | None, bool, str]:
    if isinstance(raw, dict):
        shared = bool(raw.get("shared"))
        rationale = str(raw.get("sharedRationale") or "")
        for key in ("value", "bindingRef", "fingerprint"):
            value = raw.get(key)
            if isinstance(value, str) and value:
                return (value, shared, rationale)
        return (None, shared, rationale)
    if isinstance(raw, str) and raw:
        return (raw, False, "")
    return (None, False, "")


def extract_service_discovery_overrides(rendered_text: str) -> dict[str, str]:
    overrides = {}
    for raw_key, raw_value in re.findall(
        r"(FIREMUD_SERVICES_[A-Z0-9_]+):\s*([^\n]+)", rendered_text
    ):
        value = str(raw_value).strip().strip("\"'")
        overrides[raw_key] = value
    return overrides


def external_binding_uniqueness_issues(
    manifests_root: Path, env_class: str, current_data: dict[str, Any]
) -> list[str]:
    current_backup = current_data.get("backupStorage") or {}
    current_asset = current_data.get("assetStorage") or {}
    current_outbound = current_data.get("outboundComms") or {}
    current_operator = current_data.get("operatorCredentials") or {}

    def add_candidate(
        issues: list[str],
        candidates: list[tuple[str, str, bool, str]],
        label: str,
        raw_value: Any,
    ) -> None:
        value, shared, rationale = normalize_binding_value(raw_value)
        if value is None:
            return
        if shared and not rationale:
            issues.append(f"{label} is marked shared but missing sharedRationale")
            return
        candidates.append((label, value, shared, rationale))

    issues: list[str] = []
    candidates: list[tuple[str, str, bool, str]] = []
    add_candidate(issues, candidates, "backupStorage.bucket", current_backup.get("bucket"))
    add_candidate(
        issues,
        candidates,
        "backupStorage.bindingRef",
        current_backup.get("bindingRef") or current_backup.get("fingerprint"),
    )
    add_candidate(issues, candidates, "assetStorage.bucket", current_asset.get("bucket"))
    add_candidate(issues, candidates, "assetStorage.endpoint", current_asset.get("endpoint"))
    add_candidate(
        issues,
        candidates,
        "assetStorage.bindingRef",
        current_asset.get("bindingRef") or current_asset.get("fingerprint"),
    )
    add_candidate(issues, candidates, "outboundComms.smtpHost", current_outbound.get("smtpHost"))
    for target_name, raw_target in sorted((current_outbound.get("webhookTargets") or {}).items()):
        add_candidate(
            issues, candidates, f"outboundComms.webhookTargets.{target_name}", raw_target
        )
    add_candidate(
        issues,
        candidates,
        "operatorCredentials.bindingRef",
        current_operator.get("bindingRef") or current_operator.get("fingerprint"),
    )
    if issues:
        return issues

    for other_env in player_facing_environments():
        if other_env == env_class:
            continue
        other_path = manifests_root / other_env / "expected-bindings.yaml"
        if not other_path.exists():
            issues.append(f"Missing expected-bindings manifest for {other_env}")
            continue
        try:
            other_data = load_yaml(other_path) or {}
        except Exception as exc:
            issues.append(f"Unreadable expected-bindings manifest for {other_env}: {exc}")
            continue
        other_backup = other_data.get("backupStorage") or {}
        other_asset = other_data.get("assetStorage") or {}
        other_outbound = other_data.get("outboundComms") or {}
        other_operator = other_data.get("operatorCredentials") or {}
        other_lookup = {
            "backupStorage.bucket": other_backup.get("bucket"),
            "backupStorage.bindingRef": other_backup.get("bindingRef") or other_backup.get("fingerprint"),
            "assetStorage.bucket": other_asset.get("bucket"),
            "assetStorage.endpoint": other_asset.get("endpoint"),
            "assetStorage.bindingRef": other_asset.get("bindingRef") or other_asset.get("fingerprint"),
            "outboundComms.smtpHost": other_outbound.get("smtpHost"),
            "operatorCredentials.bindingRef": other_operator.get("bindingRef") or other_operator.get("fingerprint"),
        }
        for target_name, raw_target in sorted((other_outbound.get("webhookTargets") or {}).items()):
            other_lookup[f"outboundComms.webhookTargets.{target_name}"] = raw_target

        for label, value, current_shared, current_rationale in candidates:
            other_value, other_shared, other_rationale = normalize_binding_value(
                other_lookup.get(label)
            )
            if other_value != value:
                continue
            if current_shared != other_shared:
                issues.append(
                    f"{label} matches {other_env} but shared declaration does not match on both manifests"
                )
                continue
            if current_shared and (
                not current_rationale or not other_rationale or current_rationale != other_rationale
            ):
                issues.append(
                    f"{label} matches {other_env} but sharedRationale is missing or inconsistent"
                )
                continue
            if not current_shared:
                issues.append(f"{label} matches {other_env} without explicit shared binding approval")
    return issues


def metadata_name(document: dict[str, Any]) -> str | None:
    metadata = document.get("metadata") or {}
    return metadata.get("name")


def rendered_has_resource(documents: list[dict[str, Any]], kind: str, name: str) -> bool:
    return any(document.get("kind") == kind and metadata_name(document) == name for document in documents)


def rendered_references_secret(documents: list[dict[str, Any]], name: str) -> bool:
    for document in documents:
        for node in walk(document):
            if not isinstance(node, dict):
                continue
            if node.get("secretName") == name:
                return True
            secret_ref = node.get("secretRef")
            if isinstance(secret_ref, dict) and secret_ref.get("name") == name:
                return True
            secret_key_ref = node.get("secretKeyRef")
            if isinstance(secret_key_ref, dict) and secret_key_ref.get("name") == name:
                return True
    return False


def parse_binding_ref(ref: Any) -> tuple[str, str, list[str]] | None:
    if not isinstance(ref, str):
        return None
    match = re.match(r"^(?P<scheme>[a-z][a-z0-9-]*)://(?P<namespace>[^/]+)/(?P<path>.+)$", ref)
    if not match:
        return None
    segments = [segment for segment in match.group("path").split("/") if segment]
    if not segments:
        return None
    return match.group("scheme"), match.group("namespace"), segments


def binding_ref_format_error(
    label: str,
    ref: Any,
    *,
    allowed_schemes: set[str] | None = None,
    exact_segment_count: int | None = None,
    allowed_leading_segments: set[str] | None = None,
) -> str | None:
    parsed = parse_binding_ref(ref)
    if parsed is None:
        return f"{label} must use <scheme>://<namespace>/<binding> format"
    scheme, namespace, segments = parsed
    if allowed_schemes and scheme not in allowed_schemes:
        allowed = ", ".join(sorted(allowed_schemes))
        return f"{label} must use one of the allowed schemes: {allowed}"
    if exact_segment_count is not None and len(segments) != exact_segment_count:
        return f"{label} must include exactly {exact_segment_count} binding path segment(s)"
    if allowed_leading_segments is not None:
        if len(segments) < 2 or segments[0] not in allowed_leading_segments:
            allowed = ", ".join(sorted(allowed_leading_segments))
            return f"{label} must use one of the allowed binding kinds: {allowed}"
    return None


def secret_binding_name(ref: Any) -> str | None:
    parsed = parse_binding_ref(ref)
    if parsed is None:
        return None
    scheme, _, segments = parsed
    if scheme != "secret" or len(segments) != 1:
        return None
    return segments[0] or None


def rendered_references_image_pull_secret(documents: list[dict[str, Any]], name: str) -> bool:
    for document in documents:
        if document.get("kind") == "ServiceAccount":
            for entry in document.get("imagePullSecrets") or []:
                if isinstance(entry, dict) and entry.get("name") == name:
                    return True
        for node in walk(document):
            if not isinstance(node, dict):
                continue
            for entry in node.get("imagePullSecrets") or []:
                if isinstance(entry, dict) and entry.get("name") == name:
                    return True
    return False


def config_value(documents: list[dict[str, Any]], name: str) -> str | None:
    for document in documents:
        if document.get("kind") != "ConfigMap":
            continue
        data = document.get("data") or {}
        if name in data:
            return str(data[name])
    return None


def primary_containers(document: dict[str, Any]) -> list[tuple[str | None, dict[str, Any], dict[str, str | None]]]:
    if document.get("kind") not in {"Deployment", "StatefulSet", "DaemonSet"}:
        return []
    spec = (((document.get("spec") or {}).get("template") or {}).get("spec") or {})
    volumes = {
        volume.get("name"): ((volume.get("secret") or {}).get("secretName"))
        for volume in spec.get("volumes") or []
        if isinstance(volume, dict)
    }
    containers: list[tuple[str | None, dict[str, Any], dict[str, str | None]]] = []
    for container in spec.get("containers") or []:
        name = container.get("name") or ""
        if name.endswith("-service") or name == "spring-cloud-gateway":
            containers.append((metadata_name(document), container, volumes))
    return containers


def env_value(container: dict[str, Any], name: str) -> str | None:
    for entry in container.get("env") or []:
        if entry.get("name") == name:
            return entry.get("value")
    return None


def has_secret_mount(
    container: dict[str, Any],
    volumes: dict[str, str | None],
    secret_name: str,
    required_mount: str,
) -> bool:
    for mount in container.get("volumeMounts") or []:
        mounted_secret = volumes.get(mount.get("name"))
        mount_path = str(mount.get("mountPath") or "")
        if mounted_secret == secret_name and mount_path == required_mount:
            return True
    return False


def has_secret_reference(documents: list[dict[str, Any]], name: str) -> bool:
    for document in documents:
        for _, container, volumes in primary_containers(document):
            for mounted_secret in volumes.values():
                if mounted_secret == name:
                    return True
            for entry in container.get("envFrom") or []:
                secret_ref = entry.get("secretRef")
                if isinstance(secret_ref, dict) and secret_ref.get("name") == name:
                    return True
    return False


def extract_service_images(rendered_text: str) -> list[str]:
    pattern = re.compile(r"^[ \t]*image:[ \t]*(ghcr\.io/benhook1013/.+-service\S*)", re.MULTILINE)
    return sorted(set(pattern.findall(rendered_text)))


def append_result(
    check_results: list[CheckResult],
    waived_ids: set[str],
    waiver_approver: str,
    waiver_ticket: str,
    policy_id: str,
    required: bool,
    status: str,
    message: str,
) -> bool:
    effective_status = status
    effective_message = message
    if status == "fail" and policy_id in waived_ids:
        if policy_id in NON_WAIVABLE_READINESS_GATES:
            effective_message = f"waiver not permitted for {policy_id}: {message}"
        else:
            effective_status = "pass"
            effective_message = f"waived by {waiver_approver or 'unknown'} ({waiver_ticket or 'no-ticket'}): {message}"
    check_results.append(CheckResult(policy_id, required, effective_status, effective_message))
    return required and effective_status == "fail"


def expected_binding_checks(
    expected_bindings_path: Path,
    expected_bindings_ref: str,
    env_class: str,
    documents: list[dict[str, Any]],
) -> list[CheckResult]:
    try:
        data = load_yaml(expected_bindings_path) or {}
    except Exception as exc:
        return [
            CheckResult("PREFLIGHT-SECRETS-002", True, "fail", f"Expected-bindings manifest is unreadable: {exc}"),
            CheckResult("PREFLIGHT-BOOTSTRAP-001", True, "fail", "Expected-bindings manifest is unreadable"),
            CheckResult("PREFLIGHT-EXTERNAL-001", True, "fail", "Expected-bindings manifest is unreadable"),
            CheckResult("PREFLIGHT-SERVICES-001", True, "fail", "Expected-bindings manifest is unreadable"),
        ]

    results: list[CheckResult] = []
    if data.get("environment") != env_class:
        results.append(
            CheckResult("PREFLIGHT-SECRETS-002", True, "fail", f"Expected-bindings environment mismatch in {expected_bindings_ref}")
        )
    else:
        required_internal = [
            "internalBindings.postgres.endpoint",
            "internalBindings.postgres.credentialsRef",
            "internalBindings.redis.coordination.endpoint",
            "internalBindings.redis.cache.endpoint",
            "internalBindings.jwt.signingKeysRef",
            "internalBindings.jwt.jwksRef",
            "internalBindings.certificates.issuerRef",
            "internalBindings.certificates.workloadMtlsRef",
            "internalBindings.certificates.gatewayInternalWsListenerRef",
            "internalBindings.certificates.tcpProxyBridgeClientRef",
            "internalBindings.registry.imagePullSecretRef",
        ]
        exceptional_pause_enabled = get(data, "backupMaintenancePause.enabled") is True
        if exceptional_pause_enabled:
            required_internal.append("internalBindings.certificates.backupControlPlaneClientRef")
        missing = [key for key in required_internal if not get(data, key)]
        secret_refs = [
            get(data, "internalBindings.postgres.credentialsRef"),
            get(data, "internalBindings.jwt.signingKeysRef"),
            get(data, "internalBindings.jwt.jwksRef"),
        ]
        invalid_internal_refs = [
            error
            for error in [
                binding_ref_format_error(
                    "internalBindings.postgres.credentialsRef",
                    get(data, "internalBindings.postgres.credentialsRef"),
                    allowed_schemes={"secret"},
                    exact_segment_count=1,
                ),
                binding_ref_format_error(
                    "internalBindings.jwt.signingKeysRef",
                    get(data, "internalBindings.jwt.signingKeysRef"),
                    allowed_schemes={"secret"},
                    exact_segment_count=1,
                ),
                binding_ref_format_error(
                    "internalBindings.jwt.jwksRef",
                    get(data, "internalBindings.jwt.jwksRef"),
                    allowed_schemes={"secret"},
                    exact_segment_count=1,
                ),
                binding_ref_format_error(
                    "internalBindings.certificates.issuerRef",
                    get(data, "internalBindings.certificates.issuerRef"),
                    allowed_schemes={"cert-manager"},
                    exact_segment_count=2,
                    allowed_leading_segments={"clusterissuers", "issuers"},
                ),
                binding_ref_format_error(
                    "internalBindings.certificates.workloadMtlsRef",
                    get(data, "internalBindings.certificates.workloadMtlsRef"),
                    allowed_schemes={"cert-manager"},
                    exact_segment_count=1,
                ),
                binding_ref_format_error(
                    "internalBindings.certificates.gatewayInternalWsListenerRef",
                    get(data, "internalBindings.certificates.gatewayInternalWsListenerRef"),
                    allowed_schemes={"cert-manager"},
                    exact_segment_count=1,
                ),
                binding_ref_format_error(
                    "internalBindings.certificates.tcpProxyBridgeClientRef",
                    get(data, "internalBindings.certificates.tcpProxyBridgeClientRef"),
                    allowed_schemes={"cert-manager"},
                    exact_segment_count=1,
                ),
                (
                    binding_ref_format_error(
                        "internalBindings.certificates.backupControlPlaneClientRef",
                        get(data, "internalBindings.certificates.backupControlPlaneClientRef"),
                        allowed_schemes={"cert-manager"},
                        exact_segment_count=1,
                    )
                    if exceptional_pause_enabled
                    else None
                ),
                binding_ref_format_error(
                    "internalBindings.registry.imagePullSecretRef",
                    get(data, "internalBindings.registry.imagePullSecretRef"),
                    allowed_schemes={"secret"},
                    exact_segment_count=1,
                ),
            ]
            if error
        ]
        missing_rendered_refs = []
        for ref_value in secret_refs:
            name = secret_binding_name(ref_value)
            if name and not rendered_references_secret(documents, name):
                missing_rendered_refs.append(name)
        registry_pull_secret = secret_binding_name(get(data, "internalBindings.registry.imagePullSecretRef"))
        if missing:
            results.append(
                CheckResult(
                    "PREFLIGHT-SECRETS-002",
                    True,
                    "fail",
                    "Expected-bindings missing internal keys: " + ", ".join(missing),
                )
            )
        elif invalid_internal_refs:
            results.append(
                CheckResult(
                    "PREFLIGHT-SECRETS-002",
                    True,
                    "fail",
                    "Expected-bindings internal binding refs are invalid: " + "; ".join(invalid_internal_refs),
                )
            )
        elif missing_rendered_refs:
            results.append(
                CheckResult(
                    "PREFLIGHT-SECRETS-002",
                    True,
                    "fail",
                    "Rendered workloads do not reference expected Secret bindings: " + ", ".join(missing_rendered_refs),
                )
            )
        elif registry_pull_secret and not rendered_references_image_pull_secret(documents, registry_pull_secret):
            results.append(
                CheckResult(
                    "PREFLIGHT-SECRETS-002",
                    True,
                    "fail",
                    f"Rendered workloads do not reference expected image pull Secret binding: {registry_pull_secret}",
                )
            )
        else:
            results.append(
                CheckResult(
                    "PREFLIGHT-SECRETS-002",
                    True,
                    "pass",
                    f"Internal state/trust bindings match {expected_bindings_ref}",
                )
            )

    bootstrap_names = [
        name
        for name in (
            secret_binding_name(get(data, "internalBindings.postgres.credentialsRef")),
            secret_binding_name(get(data, "internalBindings.jwt.signingKeysRef")),
            secret_binding_name(get(data, "internalBindings.jwt.jwksRef")),
        )
        if name
    ]
    missing_bootstrap = [name for name in bootstrap_names if not rendered_references_secret(documents, name)]
    if missing_bootstrap:
        results.append(
            CheckResult(
                "PREFLIGHT-BOOTSTRAP-001",
                True,
                "fail",
                "Rendered workloads do not reference bootstrap bindings: " + ", ".join(missing_bootstrap),
            )
        )
    else:
        results.append(
            CheckResult(
                "PREFLIGHT-BOOTSTRAP-001",
                True,
                "pass",
                "Rendered workloads reference the minimum bootstrap secret bindings",
            )
        )

    external_requirements = [
        ("backupStorage.bucket", None),
        ("backupStorage.bindingRef", "backupStorage.fingerprint"),
        ("assetStorage.bucket", None),
        ("assetStorage.endpoint", None),
        ("assetStorage.bindingRef", "assetStorage.fingerprint"),
        ("outboundComms.smtpHost", None),
        ("operatorCredentials.bindingRef", "operatorCredentials.fingerprint"),
    ]
    missing_external = []
    for primary, alternate in external_requirements:
        if not get(data, primary) and (alternate is None or not get(data, alternate)):
            missing_external.append(primary if alternate is None else f"{primary} or {alternate}")
    webhook_targets = get(data, "outboundComms.webhookTargets")
    invalid_external_refs = [
        error
        for error in [
            binding_ref_format_error("backupStorage.bindingRef", get(data, "backupStorage.bindingRef")),
            binding_ref_format_error("assetStorage.bindingRef", get(data, "assetStorage.bindingRef")),
            binding_ref_format_error(
                "operatorCredentials.bindingRef", get(data, "operatorCredentials.bindingRef")
            ),
        ]
        if error
    ]
    if missing_external:
        results.append(
            CheckResult(
                "PREFLIGHT-EXTERNAL-001",
                True,
                "fail",
                "Expected-bindings missing external binding keys: " + ", ".join(missing_external),
            )
        )
    elif invalid_external_refs:
        results.append(
            CheckResult(
                "PREFLIGHT-EXTERNAL-001",
                True,
                "fail",
                "Expected-bindings external binding refs are invalid: " + "; ".join(invalid_external_refs),
            )
        )
    elif not isinstance(webhook_targets, dict) or not webhook_targets:
        results.append(
            CheckResult(
                "PREFLIGHT-EXTERNAL-001",
                True,
                "fail",
                "Expected-bindings missing outboundComms.webhookTargets entries",
            )
        )
    else:
        uniqueness_issues = external_binding_uniqueness_issues(
            expected_bindings_path.parent.parent, env_class, data
        )
        if uniqueness_issues:
            results.append(
                CheckResult(
                    "PREFLIGHT-EXTERNAL-001",
                    True,
                    "fail",
                    "External binding isolation check failed: " + "; ".join(uniqueness_issues),
                )
            )
        else:
            results.append(
                CheckResult(
                    "PREFLIGHT-EXTERNAL-001",
                    True,
                    "pass",
                    f"External bindings are environment-scoped in {expected_bindings_ref}",
                )
            )

    mode = get(data, "serviceDiscovery.mode")
    rendered_text = yaml.safe_dump_all(documents, sort_keys=False)
    override_lines = extract_service_discovery_overrides(rendered_text)
    if mode == "kubernetes-dns-default" and override_lines:
        results.append(
            CheckResult(
                "PREFLIGHT-SERVICES-001",
                True,
                "fail",
                "Rendered manifests contain FIREMUD_SERVICES_* overrides while expected bindings require Kubernetes DNS defaults",
            )
        )
    elif mode == "explicit-overrides":
        allowed = get(data, "serviceDiscovery.allowedOverrides")
        if not isinstance(allowed, dict) or not allowed:
            results.append(
                CheckResult(
                    "PREFLIGHT-SERVICES-001",
                    True,
                    "fail",
                    "serviceDiscovery.allowedOverrides is required for explicit-overrides mode",
                )
            )
        else:
            failures: list[str] = []
            for override_name, rendered_value in sorted(override_lines.items()):
                if override_name not in allowed:
                    failures.append(
                        f"Rendered override {override_name} is not declared in serviceDiscovery.allowedOverrides"
                    )
                    continue
                expected_value = allowed.get(override_name)
                if not isinstance(expected_value, str):
                    failures.append(f"serviceDiscovery.allowedOverrides[{override_name}] must be a string")
                    continue
                if expected_value != rendered_value:
                    failures.append(
                        f"Rendered {override_name}='{rendered_value}' does not match allowed value '{expected_value}'"
                    )
            if failures:
                results.append(
                    CheckResult(
                        "PREFLIGHT-SERVICES-001",
                        True,
                        "fail",
                        "Explicit service-discovery override values do not match expected contract: "
                        + "; ".join(failures),
                    )
                )
            elif not override_lines:
                results.append(
                    CheckResult(
                        "PREFLIGHT-SERVICES-001",
                        True,
                        "fail",
                        "No FIREMUD_SERVICES_* overrides were rendered for explicit-overrides mode",
                    )
                )
            else:
                results.append(
                    CheckResult(
                        "PREFLIGHT-SERVICES-001",
                        True,
                        "pass",
                        "Rendered FIREMUD_SERVICES_* overrides match expected explicit contract",
                    )
                )
    elif mode == "kubernetes-dns-default":
        results.append(
            CheckResult(
                "PREFLIGHT-SERVICES-001",
                True,
                "pass",
                "Rendered manifests use default in-environment service discovery",
            )
        )
    else:
        results.append(
            CheckResult(
                "PREFLIGHT-SERVICES-001",
                True,
                "fail",
                "serviceDiscovery.mode must be kubernetes-dns-default or explicit-overrides",
            )
        )
    return results


def jwt_jwks_checks(documents: list[dict[str, Any]]) -> list[CheckResult]:
    inline_secret = False
    missing_secret_path: list[str] = []
    missing_signing_mount: list[str] = []
    missing_jwks_mount: list[str] = []
    global_secret_path = config_value(documents, "FIREMUD_AUTH_JWT_SECRET_PATH")
    global_jwks_path = config_value(documents, "FIREMUD_AUTH_JWKS_PATH")
    for workload_name, container, volumes in [item for document in documents for item in primary_containers(document)]:
        container_name = container.get("name") or "<unknown>"
        if env_value(container, "FIREMUD_AUTH_JWT_SECRET") is not None:
            inline_secret = True
        secret_path = env_value(container, "FIREMUD_AUTH_JWT_SECRET_PATH") or global_secret_path
        if not secret_path:
            missing_secret_path.append(f"{workload_name}/{container_name}")
        elif str(secret_path).startswith("/var/run/secrets/firemud/jwt/") and not has_secret_mount(
            container,
            volumes,
            "jwt-signing-keys",
            "/var/run/secrets/firemud/jwt",
        ):
            missing_signing_mount.append(f"{workload_name}/{container_name}")
        jwks_path = env_value(container, "FIREMUD_AUTH_JWKS_PATH") or global_jwks_path
        if (
            container_name == "account-service"
            and jwks_path
            and str(jwks_path).startswith("/var/run/secrets/firemud/jwks/")
            and not has_secret_mount(container, volumes, "jwt-jwks", "/var/run/secrets/firemud/jwks")
        ):
            missing_jwks_mount.append(f"{workload_name}/{container_name}")

    results: list[CheckResult] = []
    if inline_secret:
        results.append(CheckResult("PREFLIGHT-JWT-001", True, "fail", "Inline JWT secret material detected in rendered workloads"))
    elif missing_secret_path:
        results.append(
            CheckResult(
                "PREFLIGHT-JWT-001",
                True,
                "fail",
                "FIREMUD_AUTH_JWT_SECRET_PATH is missing for workloads: " + ", ".join(missing_secret_path),
            )
        )
    elif missing_signing_mount:
        results.append(
            CheckResult(
                "PREFLIGHT-JWT-001",
                True,
                "fail",
                "JWT signing Secret is not mounted at the configured path for workloads: " + ", ".join(missing_signing_mount),
            )
        )
    else:
        results.append(
            CheckResult(
                "PREFLIGHT-JWT-001",
                True,
                "pass",
                "JWT file-path contract and signing Secret mounts are satisfied",
            )
        )

    if rendered_has_resource(documents, "ConfigMap", "jwt-jwks"):
        results.append(CheckResult("PREFLIGHT-JWKS-001", True, "fail", "jwt-jwks is configured as a ConfigMap in player-facing context"))
    elif not rendered_has_resource(documents, "Secret", "jwt-jwks") and not has_secret_reference(documents, "jwt-jwks"):
        results.append(CheckResult("PREFLIGHT-JWKS-001", True, "fail", "Rendered workloads do not reference jwt-jwks as a Secret"))
    elif missing_jwks_mount:
        results.append(
            CheckResult(
                "PREFLIGHT-JWKS-001",
                True,
                "fail",
                "Account Service does not mount jwt-jwks at the configured JWKS path: " + ", ".join(missing_jwks_mount),
            )
        )
    else:
        results.append(
            CheckResult(
                "PREFLIGHT-JWKS-001",
                True,
                "pass",
                "jwt-jwks Secret contract and Account Service mount are satisfied",
            )
        )
    return results


def promotion_check(attestation_path: Path, images: list[str], root_dir: Path) -> tuple[str, str, str]:
    try:
        att = load_json(attestation_path)
    except Exception as exc:
        return ("fail", "unknown", f"Attestation unreadable: {exc}")

    if att.get("environment") != "staging":
        return ("fail", "unknown", "Attestation environment must be staging")

    rollback_mode = str(att.get("rollbackMode", "unknown"))
    if rollback_mode not in {"rollback-compatible", "roll-forward-only"}:
        return ("fail", "unknown", "Attestation rollbackMode is missing or invalid")

    service_digests = att.get("serviceDigests", {})
    for image in images:
        name = image.split("/")[-1].split("@")[0].split(":")[0]
        expected = service_digests.get(name)
        if not expected:
            return ("fail", rollback_mode, f"Missing digest for service {name} in attestation")
        if expected != image:
            return ("fail", rollback_mode, f"Digest mismatch for service {name}")

    staging_sha = att.get("stagingOverlayCommitSha", "")
    if not staging_sha:
        return ("fail", rollback_mode, "Attestation missing stagingOverlayCommitSha")

    record_path = root_dir / "design" / "operations" / "deployments" / "staging" / "deployments" / f"{staging_sha}.json"
    if not record_path.exists():
        return ("fail", rollback_mode, f"Staging deployment record not found: {record_path}")

    try:
        record = load_json(record_path)
    except Exception as exc:
        return ("fail", rollback_mode, f"Staging deployment record unreadable: {exc}")

    if record.get("environment") != "staging":
        return ("fail", rollback_mode, "Staging deployment record has wrong environment")
    if record.get("overlayCommitSha") != staging_sha:
        return ("fail", rollback_mode, "Staging deployment record overlayCommitSha mismatch")

    record_digests = record.get("serviceDigests", {})
    for name, expected in service_digests.items():
        if record_digests.get(name) != expected:
            return ("fail", rollback_mode, f"Staging deployment record digest mismatch for {name}")

    if record.get("deployStatus") != "pass":
        return ("fail", rollback_mode, "Staging deployment record deployStatus must be pass")
    if record.get("smokeStatus") != "pass":
        return ("fail", rollback_mode, "Staging deployment record smokeStatus must be pass")
    if not record.get("smokeEvidence"):
        return ("fail", rollback_mode, "Staging deployment record missing smokeEvidence")
    preflight_ref = record.get("preflightReportPath")
    if not preflight_ref:
        return ("fail", rollback_mode, "Staging deployment record missing preflightReportPath")
    preflight_path = root_dir / str(preflight_ref)
    if not preflight_path.exists():
        return ("fail", rollback_mode, f"Staging preflight report not found: {preflight_ref}")
    try:
        preflight_report = load_json(preflight_path)
    except Exception as exc:
        return ("fail", rollback_mode, f"Staging preflight report unreadable: {exc}")
    if preflight_report.get("environment") != "staging":
        return ("fail", rollback_mode, "Staging preflight report has wrong environment")
    if preflight_report.get("expectedBindingsRef") != "design/operations/environments/staging/expected-bindings.yaml":
        return ("fail", rollback_mode, "Staging preflight report expectedBindingsRef mismatch")
    preflight_results = preflight_report.get("checkResults")
    if not isinstance(preflight_results, list) or not preflight_results:
        return ("fail", rollback_mode, "Staging preflight report missing checkResults")
    required_failures = [
        check.get("policyId")
        for check in preflight_results
        if isinstance(check, dict)
        and check.get("status") == "fail"
        and check.get("policyId") != "PREFLIGHT-DIGEST-002"
    ]
    if required_failures:
        return (
            "fail",
            rollback_mode,
            "Staging preflight report contains failing required checks: " + ", ".join(required_failures),
        )
    if not record.get("secretComplianceSnapshotAt"):
        return ("fail", rollback_mode, "Staging deployment record missing secretComplianceSnapshotAt")

    live_state = record.get("liveStateEvidence")
    if not isinstance(live_state, dict):
        return ("fail", rollback_mode, "Staging deployment record missing liveStateEvidence")
    if live_state.get("status") != "pass":
        return ("fail", rollback_mode, "Staging deployment record liveStateEvidence must be pass")
    if not live_state.get("observedOverlaySha") or live_state.get("observedOverlaySha") != staging_sha:
        return ("fail", rollback_mode, "Staging deployment record liveStateEvidence overlay SHA mismatch")
    if not live_state.get("observedDigests"):
        return ("fail", rollback_mode, "Staging deployment record missing observedDigests")
    observed_digests = live_state.get("observedDigests", {})
    for name, expected in service_digests.items():
        if observed_digests.get(name) != expected:
            return ("fail", rollback_mode, f"Staging live-state evidence digest mismatch for {name}")

    secret_status = record.get("secretComplianceStatus")
    secret_ref = record.get("secretComplianceEvidenceRef")
    if secret_status != "pass":
        return ("fail", rollback_mode, "Staging deployment record secretComplianceStatus must be pass")
    if not secret_ref:
        return ("fail", rollback_mode, "Staging deployment record missing secretComplianceEvidenceRef")
    secret_path = root_dir / secret_ref
    if not secret_path.exists():
        return ("fail", rollback_mode, f"Staging secret compliance evidence not found: {secret_ref}")
    try:
        secret_evidence = load_json(secret_path)
    except Exception as exc:
        return ("fail", rollback_mode, f"Staging secret compliance evidence unreadable: {exc}")
    required_secret_classes = {
        "jwt-signing-keys-jwks",
        "postgres-application-credentials",
        "backup-object-store-credentials",
        "operator-credentials",
    }
    records = (secret_evidence or {}).get("records", {})
    for key in required_secret_classes:
        rec = records.get(key)
        if not isinstance(rec, dict):
            return ("fail", rollback_mode, f"Staging secret compliance evidence missing record: {key}")
        immutable_id = str(rec.get("immutableArtifactId", ""))
        if "sha256:" not in immutable_id:
            return ("fail", rollback_mode, f"Staging secret compliance evidence record is not immutable: {key}")

    return ("pass", rollback_mode, "Production promotion attestation and staging deployment evidence are valid")


def backup_readiness_check(path: Path, now: str, deployment_ref: str, root_dir: Path) -> tuple[str, str]:
    try:
        data = load_json(path)
    except Exception as exc:
        return ("fail", f"Backup-readiness evidence unreadable: {exc}")

    if data.get("environment") != "production":
        return ("fail", "Backup-readiness evidence must target production")
    if data.get("rollbackMode") != "roll-forward-only":
        return ("fail", "Backup-readiness evidence rollbackMode must be roll-forward-only")
    if not data.get("deploymentRef"):
        return ("fail", "Backup-readiness evidence missing deploymentRef")
    if deployment_ref and str(data.get("deploymentRef")) != str(deployment_ref):
        return ("fail", "Backup-readiness evidence deploymentRef does not match the current deployment")
    attestation_ref = str(data.get("promotionAttestationRef", ""))
    if not attestation_ref:
        return ("fail", "Backup-readiness evidence missing promotionAttestationRef")
    attestation_path = (root_dir / attestation_ref).resolve()
    if not attestation_path.exists():
        return ("fail", "Backup-readiness evidence references missing promotionAttestationRef")
    if not data.get("restorePlanRef"):
        return ("fail", "Backup-readiness evidence missing restorePlanRef")
    if not data.get("evidenceRefs"):
        return ("fail", "Backup-readiness evidence missing evidenceRefs")
    service_digests = data.get("serviceDigests")
    if not isinstance(service_digests, dict) or not service_digests:
        return ("fail", "Backup-readiness evidence missing serviceDigests")

    def parse_ts(name: str) -> dt.datetime:
        value = data.get(name)
        if not value:
            raise ValueError(f"missing {name}")
        return dt.datetime.fromisoformat(str(value).replace("Z", "+00:00"))

    try:
        now_dt = dt.datetime.fromisoformat(now.replace("Z", "+00:00"))
        backup_ts = parse_ts("backupLastSuccessAt")
        verify_ts = parse_ts("backupVerifyLastSuccessAt")
        drill_ts = parse_ts("restoreDrillLastSuccessAt")
    except Exception as exc:
        return ("fail", str(exc))

    if (now_dt - backup_ts).total_seconds() > 90 * 60:
        return ("fail", "Backup-readiness evidence is stale: backupLastSuccessAt older than 90 minutes")
    if (now_dt - verify_ts).total_seconds() > 36 * 60 * 60:
        return ("fail", "Backup-readiness evidence is stale: backupVerifyLastSuccessAt older than 36 hours")
    if (now_dt - drill_ts).total_seconds() > 30 * 24 * 60 * 60:
        return ("fail", "Backup-readiness evidence is stale: restoreDrillLastSuccessAt older than 30 days")

    try:
        attestation = load_json(attestation_path)
    except Exception as exc:
        return ("fail", f"Backup-readiness attestation unreadable: {exc}")
    if attestation.get("rollbackMode") != "roll-forward-only":
        return ("fail", "Backup-readiness evidence does not match a roll-forward-only attestation")
    if attestation.get("serviceDigests") != service_digests:
        return ("fail", "Backup-readiness evidence serviceDigests do not match the attestation")

    return ("pass", "Backup-readiness evidence is valid for roll-forward-only promotion")


def production_traffic_check() -> tuple[str, str]:
    return (
        "fail",
        "Production traffic-open gate unavailable: durable environment-wide "
        "recovery-controller authority is not implemented; checked-in projections "
        "and caller-supplied tenant/region/timestamp evidence cannot authorize traffic",
    )


def hobby_traffic_check(compliance_path: Path, traffic_path: Path, event: str, deployment_ref: str, root_dir: Path) -> tuple[str, str]:
    if not compliance_path.exists():
        return ("fail", f"Hobby backup-compliance record not found: {compliance_path}")
    if not traffic_path.exists():
        return ("fail", f"Hobby traffic-open evidence not found: {traffic_path}")
    try:
        compliance = load_yaml(compliance_path) or {}
        traffic = load_json(traffic_path)
    except Exception as exc:
        return ("fail", f"Hobby traffic-open evidence unreadable: {exc}")
    if traffic.get("schemaVersion") != "traffic-open-record/v1":
        return ("fail", "Hobby traffic-open evidence schemaVersion mismatch")
    if compliance.get("environment") != "hobby-self-hosted" or traffic.get("environment") != "hobby-self-hosted":
        return ("fail", "Hobby traffic-open evidence must target hobby-self-hosted")
    if traffic.get("eventType") != event:
        return ("fail", "Hobby traffic-open evidence eventType mismatch")
    if str(traffic.get("deploymentRef", "")) != str(deployment_ref):
        return ("fail", "Hobby traffic-open evidence deploymentRef mismatch")
    if not traffic.get("assessedAt"):
        return ("fail", "Hobby traffic-open evidence missing assessedAt")
    if not traffic.get("assessedBy"):
        return ("fail", "Hobby traffic-open evidence missing assessedBy")
    evidence_refs = traffic.get("evidenceRefs")
    if not isinstance(evidence_refs, list) or not evidence_refs:
        return ("fail", "Hobby traffic-open evidence missing evidenceRefs")
    if traffic.get("backupComplianceRef") != "design/operations/deployments/hobby-self-hosted/backup-compliance.yaml":
        return ("fail", "Hobby traffic-open evidence must reference the canonical backup-compliance record")
    if compliance.get("status") != "pass":
        return ("fail", "Hobby backup-compliance status must be pass")
    preflight_status, preflight_message = load_preflight_report(
        str(traffic.get("preflightReportPath", "")),
        "hobby-self-hosted",
        "design/operations/environments/hobby-self-hosted/expected-bindings.yaml",
        deployment_ref,
        root_dir,
    )
    if preflight_status != "pass":
        return ("fail", preflight_message)
    return ("pass", "Hobby traffic-open backup compliance evidence is valid")


def write_report(
    output_path: Path,
    env_class: str,
    deployment_ref: str,
    started_at: str,
    completed_at: str,
    check_results: list[CheckResult],
    context: str,
    waiver_path: str,
    expected_bindings_ref: str,
) -> None:
    if env_class == "hobby-self-hosted":
        deployment_ref_obj = {"manifestRef": deployment_ref}
    else:
        deployment_ref_obj = {"overlayCommitSha": deployment_ref}
    report: dict[str, Any] = {
        "environment": env_class,
        "deploymentRef": deployment_ref_obj,
        "startedAt": started_at,
        "completedAt": completed_at,
        "checkResults": [
            {"policyId": check.policy_id, "status": check.status, "message": check.message}
            for check in check_results
        ],
        "expectedBindingsRef": expected_bindings_ref,
        "toolVersion": "preflight.py-v1",
        "context": context,
    }
    if waiver_path:
        report["waiverPath"] = waiver_path
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    if len(sys.argv) != 2:
        usage()
    env_class = sys.argv[1]
    if env_class not in {"staging", "production", "hobby-self-hosted"}:
        usage()

    root_dir = repo_root()
    context = os.environ.get("FIREMUD_PREFLIGHT_CONTEXT", "operator")
    if context not in {"operator", "ci-static"}:
        fail(f"Invalid FIREMUD_PREFLIGHT_CONTEXT: {context}")
    deployment_ref = os.environ.get("FIREMUD_DEPLOYMENT_REF") or run(["git", "rev-parse", "--short=12", "HEAD"]).strip()
    expected_bindings_ref = f"design/operations/environments/{env_class}/expected-bindings.yaml"
    expected_bindings_path = root_dir / expected_bindings_ref
    if not expected_bindings_path.exists():
        fail(f"Expected-bindings manifest not found: {expected_bindings_path}")

    if env_class == "hobby-self-hosted":
        render_path_env = os.environ.get("FIREMUD_PREFLIGHT_RENDER_PATH")
        if not render_path_env:
            fail("FIREMUD_PREFLIGHT_RENDER_PATH is required for hobby-self-hosted preflight.")
        render_path = Path(render_path_env)
        if not render_path.is_absolute():
            render_path = root_dir / render_path
        if render_path.is_dir():
            rendered = run(["kubectl", "kustomize", str(render_path)])
        elif render_path.is_file():
            rendered = render_path.read_text(encoding="utf-8")
        else:
            fail(f"FIREMUD_PREFLIGHT_RENDER_PATH does not exist: {render_path}")
    else:
        overlay_name = "stage" if env_class == "staging" else "prod"
        rendered = run(["kubectl", "kustomize", str(root_dir / "k8s" / "overlays" / overlay_name)])

    documents = parse_documents(rendered)
    waiver_path = os.environ.get("FIREMUD_PREFLIGHT_WAIVER", "")
    waived_ids: set[str] = set()
    waiver_approver = ""
    waiver_ticket = ""
    if waiver_path:
        waiver_file = Path(waiver_path)
        if not waiver_file.exists():
            fail(f"Waiver path does not exist: {waiver_path}")
        waiver = load_json(waiver_file)
        waived_ids = set(waiver.get("waivedPolicyIds", []))
        waiver_approver = waiver.get("approver", "")
        waiver_ticket = waiver.get("ticket", "")

    started_at = utc_now()
    traffic_open_event = os.environ.get("FIREMUD_TRAFFIC_OPEN_EVENT", "")
    if traffic_open_event not in {"", "first-live", "reopen"}:
        fail(f"Invalid FIREMUD_TRAFFIC_OPEN_EVENT: {traffic_open_event}")

    check_results: list[CheckResult] = []
    has_required_failure = False

    for check in expected_binding_checks(expected_bindings_path, expected_bindings_ref, env_class, documents):
        has_required_failure = append_result(
            check_results,
            waived_ids,
            waiver_approver,
            waiver_ticket,
            check.policy_id,
            check.required,
            check.status,
            check.message,
        ) or has_required_failure

    service_images = extract_service_images(rendered)
    if env_class == "hobby-self-hosted":
        if not service_images:
            has_required_failure = append_result(
                check_results, waived_ids, waiver_approver, waiver_ticket,
                "PREFLIGHT-DIGEST-002", False, "not_applicable", "No workload images found for hobby manifest rendering",
            ) or has_required_failure
        elif any("@sha256:" not in image for image in service_images):
            has_required_failure = append_result(
                check_results, waived_ids, waiver_approver, waiver_ticket,
                "PREFLIGHT-DIGEST-002", False, "fail", "One or more hobby workload images are not digest-pinned",
            ) or has_required_failure
        else:
            has_required_failure = append_result(
                check_results, waived_ids, waiver_approver, waiver_ticket,
                "PREFLIGHT-DIGEST-002", False, "pass", "All hobby workload images are digest-pinned",
            ) or has_required_failure
        has_required_failure = append_result(
            check_results, waived_ids, waiver_approver, waiver_ticket,
            "PREFLIGHT-DIGEST-001", False, "not_applicable", "Overlay digest policy does not apply to hobby deployments",
        ) or has_required_failure
    else:
        if not service_images:
            has_required_failure = append_result(
                check_results, waived_ids, waiver_approver, waiver_ticket,
                "PREFLIGHT-DIGEST-001", True, "fail", "No workload images found in rendered overlay",
            ) or has_required_failure
        elif any("@sha256:" not in image for image in service_images):
            has_required_failure = append_result(
                check_results, waived_ids, waiver_approver, waiver_ticket,
                "PREFLIGHT-DIGEST-001", True, "fail", "Staging/production overlay contains non-digest service image references",
            ) or has_required_failure
        else:
            has_required_failure = append_result(
                check_results, waived_ids, waiver_approver, waiver_ticket,
                "PREFLIGHT-DIGEST-001", True, "pass", "All rendered workload images are digest-pinned",
            ) or has_required_failure
        has_required_failure = append_result(
            check_results, waived_ids, waiver_approver, waiver_ticket,
            "PREFLIGHT-DIGEST-002", False, "not_applicable", "Hobby digest advisory does not apply to overlay deployment",
        ) or has_required_failure

    secret_check_failed = False
    if context == "ci-static":
        for secret_name in ("postgres-credentials", "jwt-signing-keys", "jwt-jwks"):
            if not rendered_references_secret(documents, secret_name):
                has_required_failure = append_result(
                    check_results, waived_ids, waiver_approver, waiver_ticket,
                    "PREFLIGHT-SECRETS-001", True, "fail", f"Rendered workloads do not reference required Secret binding: {secret_name}",
                ) or has_required_failure
                secret_check_failed = True
                break
        if not secret_check_failed:
            has_required_failure = append_result(
                check_results, waived_ids, waiver_approver, waiver_ticket,
                "PREFLIGHT-SECRETS-001", True, "pass", "Rendered workloads reference required player-facing Secret bindings",
            ) or has_required_failure
    else:
        for secret_name in ("postgres-credentials", "jwt-signing-keys", "jwt-jwks"):
            result = subprocess.run(["kubectl", "get", "secret", "-n", "firemud", secret_name], capture_output=True, text=True)
            if result.returncode != 0:
                has_required_failure = append_result(
                    check_results, waived_ids, waiver_approver, waiver_ticket,
                    "PREFLIGHT-SECRETS-001", True, "fail", f"Missing required Secret in cluster: firemud/{secret_name}",
                ) or has_required_failure
                secret_check_failed = True
                break
        if not secret_check_failed:
            has_required_failure = append_result(
                check_results, waived_ids, waiver_approver, waiver_ticket,
                "PREFLIGHT-SECRETS-001", True, "pass", "Required player-facing Secrets exist in the target cluster",
            ) or has_required_failure

    for check in jwt_jwks_checks(documents):
        has_required_failure = append_result(
            check_results, waived_ids, waiver_approver, waiver_ticket,
            check.policy_id, check.required, check.status, check.message,
        ) or has_required_failure

    gw_value = None
    for document in documents:
        for _, container, _ in primary_containers(document):
            value = env_value(container, "GATEWAY_WS_URL")
            if value:
                gw_value = value
                break
        if gw_value:
            break
    if not gw_value:
        has_required_failure = append_result(check_results, waived_ids, waiver_approver, waiver_ticket, "PREFLIGHT-BRIDGE-001", True, "fail", "GATEWAY_WS_URL is not explicitly configured") or has_required_failure
    elif not gw_value.startswith("wss://"):
        has_required_failure = append_result(check_results, waived_ids, waiver_approver, waiver_ticket, "PREFLIGHT-BRIDGE-001", True, "fail", "GATEWAY_WS_URL must use wss:// in player-facing environments") or has_required_failure
    elif "spring-cloud-gateway-mtls" not in gw_value:
        has_required_failure = append_result(check_results, waived_ids, waiver_approver, waiver_ticket, "PREFLIGHT-BRIDGE-001", True, "fail", "GATEWAY_WS_URL does not target the internal gateway mTLS listener") or has_required_failure
    else:
        has_required_failure = append_result(check_results, waived_ids, waiver_approver, waiver_ticket, "PREFLIGHT-BRIDGE-001", True, "pass", "Gateway bridge alignment is valid") or has_required_failure

    coord_host = config_value(documents, "FIREMUD_REDIS_COORD_HOST")
    coord_port = config_value(documents, "FIREMUD_REDIS_COORD_PORT")
    cache_host = config_value(documents, "FIREMUD_REDIS_CACHE_HOST")
    cache_port = config_value(documents, "FIREMUD_REDIS_CACHE_PORT")
    if not coord_host or not cache_host:
        has_required_failure = append_result(check_results, waived_ids, waiver_approver, waiver_ticket, "PREFLIGHT-REDIS-001", True, "fail", "Could not resolve both Coordination and Cache Redis endpoints") or has_required_failure
    elif f"{coord_host}:{coord_port}" == f"{cache_host}:{cache_port}":
        has_required_failure = append_result(check_results, waived_ids, waiver_approver, waiver_ticket, "PREFLIGHT-REDIS-001", True, "fail", "Coordination and Cache Redis endpoints resolve to the same host:port") or has_required_failure
    else:
        has_required_failure = append_result(check_results, waived_ids, waiver_approver, waiver_ticket, "PREFLIGHT-REDIS-001", True, "pass", "Redis role split contract is satisfied") or has_required_failure

    rollback_mode = ""
    promotion_attestation = os.environ.get("FIREMUD_PROMOTION_ATTESTATION", "")
    backup_readiness_evidence = os.environ.get("FIREMUD_BACKUP_READINESS_EVIDENCE", "")
    if env_class != "production":
        has_required_failure = append_result(check_results, waived_ids, waiver_approver, waiver_ticket, "PREFLIGHT-PROMOTION-001", False, "not_applicable", "Promotion attestation applies only to production") or has_required_failure
        has_required_failure = append_result(check_results, waived_ids, waiver_approver, waiver_ticket, "PREFLIGHT-BACKUP-001", False, "not_applicable", "Backup readiness applies only to production roll-forward-only promotions") or has_required_failure
    elif context == "ci-static" and not promotion_attestation:
        has_required_failure = append_result(check_results, waived_ids, waiver_approver, waiver_ticket, "PREFLIGHT-PROMOTION-001", False, "not_applicable", "Static CI validation without production attestation context") or has_required_failure
        has_required_failure = append_result(check_results, waived_ids, waiver_approver, waiver_ticket, "PREFLIGHT-BACKUP-001", False, "not_applicable", "Static CI validation without production attestation context") or has_required_failure
    else:
        if not promotion_attestation:
            has_required_failure = append_result(check_results, waived_ids, waiver_approver, waiver_ticket, "PREFLIGHT-PROMOTION-001", True, "fail", "FIREMUD_PROMOTION_ATTESTATION is required for production operator preflight") or has_required_failure
            has_required_failure = append_result(check_results, waived_ids, waiver_approver, waiver_ticket, "PREFLIGHT-BACKUP-001", False, "not_applicable", "Promotion attestation missing") or has_required_failure
        elif not Path(promotion_attestation).exists():
            has_required_failure = append_result(check_results, waived_ids, waiver_approver, waiver_ticket, "PREFLIGHT-PROMOTION-001", True, "fail", f"Attestation file not found: {promotion_attestation}") or has_required_failure
            has_required_failure = append_result(check_results, waived_ids, waiver_approver, waiver_ticket, "PREFLIGHT-BACKUP-001", False, "not_applicable", "Promotion attestation missing") or has_required_failure
        else:
            promotion_status, rollback_mode, promotion_message = promotion_check(Path(promotion_attestation), service_images, root_dir)
            has_required_failure = append_result(check_results, waived_ids, waiver_approver, waiver_ticket, "PREFLIGHT-PROMOTION-001", True, promotion_status, promotion_message) or has_required_failure
            if rollback_mode != "roll-forward-only":
                has_required_failure = append_result(check_results, waived_ids, waiver_approver, waiver_ticket, "PREFLIGHT-BACKUP-001", False, "not_applicable", "Backup readiness is required only for roll-forward-only promotions") or has_required_failure
            elif not backup_readiness_evidence:
                has_required_failure = append_result(check_results, waived_ids, waiver_approver, waiver_ticket, "PREFLIGHT-BACKUP-001", True, "fail", "FIREMUD_BACKUP_READINESS_EVIDENCE is required for roll-forward-only promotions") or has_required_failure
            elif not Path(backup_readiness_evidence).exists():
                has_required_failure = append_result(check_results, waived_ids, waiver_approver, waiver_ticket, "PREFLIGHT-BACKUP-001", True, "fail", f"Backup-readiness evidence file not found: {backup_readiness_evidence}") or has_required_failure
            else:
                backup_status, backup_message = backup_readiness_check(Path(backup_readiness_evidence), utc_now(), deployment_ref, root_dir)
                has_required_failure = append_result(check_results, waived_ids, waiver_approver, waiver_ticket, "PREFLIGHT-BACKUP-001", True, backup_status, backup_message) or has_required_failure

    traffic_open_evidence = os.environ.get("FIREMUD_TRAFFIC_OPEN_EVIDENCE", "")
    if env_class == "production":
        if not traffic_open_event:
            has_required_failure = append_result(check_results, waived_ids, waiver_approver, waiver_ticket, "PREFLIGHT-BACKUP-002", False, "not_applicable", "Production traffic-open backup gate applies only to first-live or reopen events") or has_required_failure
        else:
            traffic_status, traffic_message = production_traffic_check()
            has_required_failure = append_result(check_results, waived_ids, waiver_approver, waiver_ticket, "PREFLIGHT-BACKUP-002", True, traffic_status, traffic_message) or has_required_failure
    else:
        has_required_failure = append_result(check_results, waived_ids, waiver_approver, waiver_ticket, "PREFLIGHT-BACKUP-002", False, "not_applicable", "Production traffic-open backup gate applies only to production") or has_required_failure

    if env_class == "hobby-self-hosted":
        if not traffic_open_event:
            has_required_failure = append_result(check_results, waived_ids, waiver_approver, waiver_ticket, "PREFLIGHT-BACKUP-003", False, "not_applicable", "Hobby traffic-open backup gate applies only to first-live or reopen events") or has_required_failure
        else:
            traffic_evidence_path = Path(traffic_open_evidence) if traffic_open_evidence else root_dir / "design" / "operations" / "deployments" / "hobby-self-hosted" / "traffic-open" / f"{deployment_ref}.json"
            compliance_path = root_dir / "design" / "operations" / "deployments" / "hobby-self-hosted" / "backup-compliance.yaml"
            hobby_status, hobby_message = hobby_traffic_check(compliance_path, traffic_evidence_path, traffic_open_event, deployment_ref, root_dir)
            has_required_failure = append_result(check_results, waived_ids, waiver_approver, waiver_ticket, "PREFLIGHT-BACKUP-003", True, hobby_status, hobby_message) or has_required_failure
    else:
        has_required_failure = append_result(check_results, waived_ids, waiver_approver, waiver_ticket, "PREFLIGHT-BACKUP-003", False, "not_applicable", "Hobby traffic-open backup gate applies only to hobby-self-hosted") or has_required_failure

    completed_at = utc_now()
    default_output = root_dir / "design" / "operations" / "deployments" / env_class / "preflight" / f"{deployment_ref}.json"
    output_path = Path(os.environ.get("FIREMUD_PREFLIGHT_OUTPUT", str(default_output)))
    write_report(
        output_path,
        env_class,
        deployment_ref,
        started_at,
        completed_at,
        check_results,
        context,
        waiver_path,
        expected_bindings_ref,
    )

    if has_required_failure:
        print(f"Preflight failed; report written to: {output_path}", file=sys.stderr)
        return 1

    print(f"Preflight passed; report written to: {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
