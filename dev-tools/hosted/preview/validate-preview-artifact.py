#!/usr/bin/env python3
"""Validate a PR render in the trusted default-branch workflow."""

from __future__ import annotations

import copy
import hashlib
import json
import re
import sys
import typing
from pathlib import Path

import yaml


EXPECTED_KINDS = {
    ("apps/v1", "Deployment"),
    ("v1", "ConfigMap"),
    ("v1", "PersistentVolumeClaim"),
    ("v1", "Service"),
    ("batch/v1", "Job"),
    ("networking.k8s.io/v1", "Ingress"),
    ("networking.k8s.io/v1", "NetworkPolicy"),
}
SERVICE_IMAGES = {
    "account-service",
    "automation-scripting-service",
    "entity-management-service",
    "game-design-service",
    "game-logic-service",
    "game-session-service",
    "logging-admin-service",
    "social-groups-service",
    "spring-cloud-gateway",
    "tcp-proxy-service",
    "world-management-service",
}
EXPECTED_NAMES = {
    "Deployment": SERVICE_IMAGES | {"postgres", "redis-coord", "redis-cache", "minio"},
    "Service": SERVICE_IMAGES | {"postgres", "redis-coord", "redis-cache", "minio"},
    "ConfigMap": {"firemud-config", "firemud-seed-sql"},
    "PersistentVolumeClaim": {"postgres-data", "redis-coord-data", "redis-cache-data", "minio-data"},
    "Job": {"firemud-seed"},
    "Ingress": {"firemud-preview"},
    "NetworkPolicy": {
        "internal-services",
        "internal-services-egress",
        "account-service-controller-ingress",
    },
}
EXPECTED_OBJECTS = {
    (kind, name)
    for kind, names in EXPECTED_NAMES.items()
    for name in names
}
INFRASTRUCTURE_IMAGES = {
    "postgres:16",
    "redis:7.4.3",
    "minio/minio:RELEASE.2024-05-10T01-41-38Z",
}
EXPECTED_SECRET_REFS = {
    "firemud-secret",
    "jwt-signing-keys",
    "minio-credentials",
    "firemud-grpc-tls",
}
EXPECTED_SERVICE_PORTS = {
    **{
        service: [("tcp-8080", 8080, 8080), ("tcp-6565", 6565, 6565)]
        for service in SERVICE_IMAGES - {"spring-cloud-gateway", "tcp-proxy-service"}
    },
    "spring-cloud-gateway": [("tcp-80", 80, 8080), ("tcp-6565", 6565, 6565)],
    "tcp-proxy-service": [("tcp-2323", 2323, 2323)],
    "postgres": [("tcp-5432", 5432, 5432)],
    "redis-coord": [("tcp-6379", 6379, 6379)],
    "redis-cache": [("tcp-6379", 6379, 6379)],
    "minio": [("tcp-9000", 9000, 9000)],
}
NAME_RE = re.compile(r"^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$")
SANITIZER_FORBIDDEN_KINDS = {
    "Certificate",
    "CertificateRequest",
    "ClusterIssuer",
    "CustomResourceDefinition",
    "HostedEnvironmentIdentity",
    "Role",
    "RoleBinding",
    "ClusterRole",
    "ClusterRoleBinding",
    "ServiceAccount",
    "Secret",
}
SANITIZER_SECRET_REFERENCE_SUFFIXES = ("-tls", "-telnet-tls")
SANITIZER_SENSITIVE_KEY = re.compile(r"(?:PASSWORD|TOKEN|PRIVATE|ACCESS_KEY|SECRET_KEY)$")
MIN_PREVIEW_TELNET_PORT = 32000
MAX_PREVIEW_TELNET_PORT = 32015
RESTRICTED_VOLUME_KEYS = {
    "configMap",
    "csi",
    "downwardAPI",
    "emptyDir",
    "ephemeral",
    "persistentVolumeClaim",
    "projected",
    "secret",
}
RESTRICTED_CAPABILITY_ADDITIONS = {"NET_BIND_SERVICE"}
RESTRICTED_SELINUX_TYPES = {
    "container_t",
    "container_init_t",
    "container_kvm_t",
    "container_engine_t",
}


def fail(message: str) -> typing.NoReturn:
    raise ValueError(message)


def _is_expected_secret_reference(value: object) -> bool:
    return isinstance(value, str) and value in EXPECTED_SECRET_REFS


def _is_sanitized_secret_reference(value: object) -> bool:
    return isinstance(value, str) and (
        _is_expected_secret_reference(value)
        or value.endswith(SANITIZER_SECRET_REFERENCE_SUFFIXES)
    )


def _is_manifest_secret_reference(value: object, expected_namespace: str) -> bool:
    return isinstance(value, str) and (
        _is_expected_secret_reference(value)
        or value in {
            f"{expected_namespace}-tls",
            f"{expected_namespace}-telnet-tls",
        }
    )


def _validate_image_reference(
    location: str,
    value: str,
    expected_image_tag: str,
) -> None:
    if value in INFRASTRUCTURE_IMAGES:
        return

    repository, separator, tag = value.rpartition(":")
    if not separator or not repository or not tag:
        fail(f"{location} uses an untagged image")
    service = repository.rsplit("/", 1)[-1]
    if service in SERVICE_IMAGES:
        if repository != f"ghcr.io/benhook1013/{service}":
            fail(f"{location} uses an unapproved service image repository")
        if tag != expected_image_tag:
            fail(f"{location} uses image tag {tag!r}, expected {expected_image_tag!r}")
    elif value not in INFRASTRUCTURE_IMAGES:
        fail(f"{location} uses an unapproved image")


def _clean_config_map(document: dict) -> dict | None:
    metadata = document.get("metadata") or {}
    if metadata.get("name") == "jwt-jwks":
        return None
    if metadata.get("name") != "firemud-config":
        return document
    data = document.get("data") or {}
    document["data"] = {
        key: value
        for key, value in data.items()
        if not SANITIZER_SENSITIVE_KEY.search(key)
    }
    return document


def _validate_sanitized_secret_refs(value: object, path: str = "object") -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "secretName":
                if not _is_sanitized_secret_reference(child):
                    fail(f"{path}.{key} contains an unapproved Secret reference")
            if key in {"secretRef", "secretKeyRef"} and isinstance(child, dict):
                name = child.get("name")
                if not _is_sanitized_secret_reference(name):
                    fail(f"{path}.{key}.name contains an unapproved Secret reference")
            _validate_sanitized_secret_refs(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _validate_sanitized_secret_refs(child, f"{path}[{index}]")


def _validate_restricted_pod_security(pod: object, path: str) -> None:
    """Require the explicit fields enforced by the runtime restricted PSA policy."""

    if not isinstance(pod, dict):
        fail(f"{path} is not a pod specification")
    for field in ("hostNetwork", "hostPID", "hostIPC"):
        if pod.get(field) is True:
            fail(f"{path}.{field} is forbidden by restricted Pod Security Admission")
    if pod.get("sysctls"):
        fail(f"{path}.sysctls are not allowed in the preview runtime")

    pod_security = pod.get("securityContext")
    if not isinstance(pod_security, dict):
        fail(f"{path}.securityContext is required by restricted Pod Security Admission")
    if pod_security.get("runAsNonRoot") is not True:
        fail(f"{path}.securityContext.runAsNonRoot must be true")
    for field in ("runAsUser", "runAsGroup", "fsGroup"):
        value = pod_security.get(field)
        if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
            fail(f"{path}.securityContext.{field} must be a positive numeric identity")
    seccomp = pod_security.get("seccompProfile")
    if not isinstance(seccomp, dict) or seccomp.get("type") != "RuntimeDefault":
        fail(f"{path}.securityContext.seccompProfile.type must be RuntimeDefault")

    def validate_extra_security_fields(security: dict, security_path: str) -> None:
        apparmor = security.get("appArmorProfile")
        if apparmor is not None and (
            not isinstance(apparmor, dict)
            or apparmor.get("type") not in {"RuntimeDefault", "Localhost"}
        ):
            fail(f"{security_path}.appArmorProfile is not restricted")
        selinux = security.get("seLinuxOptions")
        if selinux is not None:
            if not isinstance(selinux, dict):
                fail(f"{security_path}.seLinuxOptions is not restricted")
            if selinux.get("user") or selinux.get("role"):
                fail(f"{security_path}.seLinuxOptions user/role are forbidden")
            if selinux.get("type") not in (None, *RESTRICTED_SELINUX_TYPES):
                fail(f"{security_path}.seLinuxOptions.type is not restricted")

    validate_extra_security_fields(pod_security, f"{path}.securityContext")

    volumes = pod.get("volumes") or []
    if not isinstance(volumes, list):
        fail(f"{path}.volumes must be a list")
    for index, volume in enumerate(volumes):
        if not isinstance(volume, dict):
            fail(f"{path}.volumes[{index}] is not an object")
        if "hostPath" in volume:
            fail(f"{path}.volumes[{index}].hostPath is forbidden")
        unsupported = set(volume) - {"name"} - RESTRICTED_VOLUME_KEYS
        if unsupported:
            fail(
                f"{path}.volumes[{index}] contains unsupported restricted volume fields: "
                f"{sorted(unsupported)}"
            )

    containers = []
    for field in ("initContainers", "containers", "ephemeralContainers"):
        values = pod.get(field) or []
        if not isinstance(values, list):
            fail(f"{path}.{field} must be a list")
        containers.extend((field, index, value) for index, value in enumerate(values))
    if not any(field == "containers" for field, _, _ in containers):
        fail(f"{path}.containers must contain at least one container")
    for field, index, container in containers:
        container_path = f"{path}.{field}[{index}]"
        if not isinstance(container, dict):
            fail(f"{container_path} is not an object")
        security = container.get("securityContext")
        if not isinstance(security, dict):
            fail(f"{container_path}.securityContext is required by restricted Pod Security Admission")
        if security.get("privileged") is True:
            fail(f"{container_path}.securityContext.privileged is forbidden")
        if security.get("allowPrivilegeEscalation") is not False:
            fail(f"{container_path}.securityContext.allowPrivilegeEscalation must be false")
        if security.get("runAsNonRoot") is False:
            fail(f"{container_path}.securityContext.runAsNonRoot must not be false")
        validate_extra_security_fields(security, f"{container_path}.securityContext")
        for field, inherited in (
            ("runAsUser", pod_security["runAsUser"]),
            ("runAsGroup", pod_security["runAsGroup"]),
        ):
            value = security.get(field, inherited)
            if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
                fail(
                    f"{container_path}.securityContext.{field} must be a positive numeric identity"
                )
        capabilities = security.get("capabilities")
        if not isinstance(capabilities, dict) or "ALL" not in (capabilities.get("drop") or []):
            fail(f"{container_path}.securityContext.capabilities must drop ALL")
        additions = set(capabilities.get("add") or [])
        if not additions <= RESTRICTED_CAPABILITY_ADDITIONS:
            fail(
                f"{container_path}.securityContext.capabilities adds unsafe capabilities: "
                f"{sorted(additions - RESTRICTED_CAPABILITY_ADDITIONS)}"
            )
        if "procMount" in security and security["procMount"] != "Default":
            fail(f"{container_path}.securityContext.procMount must be Default")
        for mount_index, mount in enumerate(container.get("volumeMounts") or []):
            if isinstance(mount, dict) and mount.get("mountPropagation") not in (None, "None"):
                fail(
                    f"{container_path}.volumeMounts[{mount_index}].mountPropagation is forbidden"
                )
        for port_index, port in enumerate(container.get("ports") or []):
            if isinstance(port, dict) and "hostPort" in port:
                fail(f"{container_path}.ports[{port_index}].hostPort is forbidden")
        for field in ("livenessProbe", "readinessProbe", "startupProbe", "lifecycle"):
            action = container.get(field)
            if not isinstance(action, dict):
                continue
            for nested_path, nested in walk(action, f"{container_path}.{field}"):
                if nested_path.endswith(".host"):
                    fail(f"{nested_path} is forbidden in a probe/lifecycle action")
        windows = security.get("windowsOptions")
        if isinstance(windows, dict) and windows.get("hostProcess") is True:
            fail(f"{container_path}.securityContext.windowsOptions.hostProcess is forbidden")
    pod_windows = pod_security.get("windowsOptions")
    if isinstance(pod_windows, dict) and pod_windows.get("hostProcess") is True:
        fail(f"{path}.securityContext.windowsOptions.hostProcess is forbidden")


def _strip_annotations(value: object) -> None:
    if isinstance(value, dict):
        metadata = value.get("metadata")
        if isinstance(metadata, dict):
            metadata.pop("annotations", None)
        for child in value.values():
            _strip_annotations(child)
    elif isinstance(value, list):
        for child in value:
            _strip_annotations(child)


def _validate_no_annotations(value: object, path: str = "object") -> None:
    if isinstance(value, dict):
        metadata = value.get("metadata")
        if isinstance(metadata, dict) and metadata.get("annotations"):
            fail(f"{path}.metadata retains untrusted annotations")
        for key, child in value.items():
            _validate_no_annotations(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _validate_no_annotations(child, f"{path}[{index}]")


def sanitize(source: Path, destination: Path) -> None:
    """Produce the credential-free artifact consumed by the trusted workflow."""

    documents = []
    for raw in yaml.safe_load_all(source.read_text(encoding="utf-8")):
        if raw is None:
            continue
        if not isinstance(raw, dict):
            fail("render contains a non-object document")
        api_version = raw.get("apiVersion")
        kind = raw.get("kind")
        if kind in SANITIZER_FORBIDDEN_KINDS:
            fail(f"render contains forbidden kind {kind}")
        if (api_version, kind) not in EXPECTED_KINDS:
            fail(f"render contains unsupported object {api_version}/{kind}")
        metadata = raw.get("metadata")
        if not isinstance(metadata, dict) or not metadata.get("name"):
            fail(f"{kind} has no metadata.name")
        if kind == "NetworkPolicy" and metadata["name"] not in EXPECTED_NAMES[kind]:
            fail(f"NetworkPolicy/{metadata['name']} is not an approved runtime policy")
        if metadata.get("namespace") in {"firemud-system", "kube-system"}:
            fail(f"{kind}/{metadata['name']} targets a control namespace")
        if kind in {"Deployment", "Job"}:
            pod = (raw.get("spec") or {}).get("template", {}).get("spec")
            _validate_restricted_pod_security(
                pod,
                f"{kind}/{metadata['name']}.spec.template.spec",
            )
        sanitized = _clean_config_map(copy.deepcopy(raw))
        if sanitized is None:
            continue
        if sanitized.get("kind") == "Service":
            for port in sanitized.get("spec", {}).get("ports", []):
                if isinstance(port, dict):
                    port.pop("nodePort", None)
        _strip_annotations(sanitized)
        _validate_sanitized_secret_refs(sanitized)
        documents.append(sanitized)
    if not documents:
        fail("render produced no deployable objects")
    destination.write_text(
        "---\n".join(yaml.safe_dump(document, sort_keys=False) for document in documents),
        encoding="utf-8",
    )


def inject_telnet_port(source: Path, destination: Path, port: int) -> None:
    """Add only the trusted allocator result after artifact validation."""

    if not MIN_PREVIEW_TELNET_PORT <= port <= MAX_PREVIEW_TELNET_PORT:
        fail(
            "preview telnet port must be between "
            f"{MIN_PREVIEW_TELNET_PORT} and {MAX_PREVIEW_TELNET_PORT}"
        )
    documents = list(yaml.safe_load_all(source.read_text(encoding="utf-8")))
    matches = []
    for document in documents:
        if not isinstance(document, dict) or document.get("kind") != "Service":
            continue
        metadata = document.get("metadata") or {}
        if metadata.get("name") != "tcp-proxy-service":
            continue
        for service_port in (document.get("spec") or {}).get("ports", []):
            if isinstance(service_port, dict) and service_port.get("port") == 2323:
                matches.append(service_port)
    if len(matches) != 1:
        fail("validated preview render must contain exactly one TCP Proxy Telnet port")
    if "nodePort" in matches[0]:
        fail("validated preview render already contains a NodePort")
    matches[0]["nodePort"] = port
    destination.write_text(
        "---\n".join(yaml.safe_dump(document, sort_keys=False) for document in documents),
        encoding="utf-8",
    )


def walk(value: object, path: str = "object"):
    yield path, value
    if isinstance(value, dict):
        for key, child in value.items():
            yield from walk(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from walk(child, f"{path}[{index}]")


def validate_service_consumers(documents: list[dict], expected_namespace: str) -> None:
    """Keep identity-managed TLS references limited to the chart consumers."""

    deployments = {
        document.get("metadata", {}).get("name"): document
        for document in documents
        if document.get("kind") == "Deployment"
    }
    for service in SERVICE_IMAGES:
        deployment = deployments[service]
        pod = deployment.get("spec", {}).get("template", {}).get("spec", {})
        if pod.get("serviceAccountName") != "firemud-app":
            fail(f"Deployment/{service} uses an unapproved ServiceAccount")
        containers = pod.get("containers") or []
        if len(containers) != 1 or containers[0].get("name") != service:
            fail(f"Deployment/{service} has an unexpected container layout")

        expected_mounts = {
            "grpc-tls": ("/tls", "firemud-grpc-tls"),
            "jwt-signing-keys": ("/var/run/secrets/firemud/jwt", "jwt-signing-keys"),
        }
        if service == "account-service":
            expected_mounts["jwt-jwks"] = ("/var/run/secrets/firemud/jwks", "jwt-jwks")
        if service == "tcp-proxy-service":
            expected_mounts["telnet-tls"] = (
                "/telnet-tls",
                f"{expected_namespace}-telnet-tls",
            )
        container = containers[0]
        raw_mounts = container.get("volumeMounts", [])
        raw_volumes = pod.get("volumes", [])
        if len(raw_mounts) != len(expected_mounts) or len(raw_volumes) != len(expected_mounts):
            fail(f"Deployment/{service} has duplicate or unexpected identity consumers")
        mounts = {
            mount.get("name"): mount
            for mount in raw_mounts
            if isinstance(mount, dict)
        }
        volumes = {
            volume.get("name"): volume
            for volume in raw_volumes
            if isinstance(volume, dict)
        }
        if set(mounts) != set(expected_mounts) or set(volumes) != set(expected_mounts):
            fail(f"Deployment/{service} has an unexpected identity consumer set")

        for volume_name, (mount_path, source_name) in expected_mounts.items():
            mount = mounts[volume_name]
            if mount.get("mountPath") != mount_path or mount.get("readOnly") is not True:
                fail(f"Deployment/{service} has an unsafe {volume_name} mount")
            volume = volumes[volume_name]
            if volume_name == "jwt-jwks":
                source = volume.get("configMap") or {}
                if source.get("name") != source_name:
                    fail(f"Deployment/{service} has an unexpected jwt-jwks source")
            else:
                source = volume.get("secret") or {}
                if source.get("secretName") != source_name:
                    fail(f"Deployment/{service} has an unexpected {volume_name} source")


def validate_network_policies(documents: list[dict]) -> None:
    """Keep runtime policy additions closed and the controller exception exact."""

    policies = {
        document.get("metadata", {}).get("name"): document
        for document in documents
        if document.get("kind") == "NetworkPolicy"
    }
    expected_names = EXPECTED_NAMES["NetworkPolicy"]
    if set(policies) != expected_names:
        fail(
            "runtime NetworkPolicy set is not closed "
            f"(missing={sorted(expected_names - set(policies))}, "
            f"extra={sorted(set(policies) - expected_names)})"
        )
    controller_policy = policies["account-service-controller-ingress"]
    spec = controller_policy.get("spec") or {}
    if spec.get("podSelector") != {"matchLabels": {"app": "account-service"}}:
        fail("NetworkPolicy/account-service-controller-ingress selects an unsafe workload")
    if spec.get("policyTypes") != ["Ingress"]:
        fail("NetworkPolicy/account-service-controller-ingress must only govern ingress")
    expected_from = {
        "namespaceSelector": {
            "matchLabels": {"kubernetes.io/metadata.name": "firemud-system"}
        },
        "podSelector": {
            "matchLabels": {
                "app.kubernetes.io/name": "hosted-environment-identity-controller",
                "app.kubernetes.io/component": "controller",
            }
        },
    }
    expected_ingress = [{"from": [expected_from], "ports": [{"protocol": "TCP", "port": 6565}]}]
    if spec.get("ingress") != expected_ingress:
        fail("NetworkPolicy/account-service-controller-ingress has an unsafe exception")


def validate_manifest(
    path: Path,
    expected_namespace: str,
    expected_image_tag: str,
    expected_hostname: str,
) -> None:
    documents = list(yaml.safe_load_all(path.read_text(encoding="utf-8")))
    if not documents:
        fail("manifest is empty")
    seen: set[tuple[str, str]] = set()
    for index, document in enumerate(documents):
        if not isinstance(document, dict):
            fail(f"manifest document {index} is not an object")
        identity = (document.get("apiVersion"), document.get("kind"))
        if identity not in EXPECTED_KINDS:
            fail(f"manifest contains unsupported object {identity}")
        metadata = document.get("metadata") or {}
        name = metadata.get("name")
        if not isinstance(name, str) or not NAME_RE.fullmatch(name):
            fail(f"manifest object has unsafe name: {name!r}")
        if name not in EXPECTED_NAMES[document["kind"]]:
            fail(f"manifest contains unexpected {document['kind']}/{name}")
        object_key = (document["kind"], name)
        if object_key in seen:
            fail(f"manifest contains duplicate {document['kind']}/{name}")
        seen.add(object_key)
        namespace = metadata.get("namespace")
        if namespace is not None and namespace != expected_namespace:
            fail(f"{document['kind']}/{name} targets namespace {namespace!r}")
        _validate_no_annotations(document)
        for location, value in walk(document):
            if location.endswith(".nodePort"):
                fail(f"{location} retains a PR-selected nodePort")
            if location.endswith(".secretName"):
                if not _is_manifest_secret_reference(value, expected_namespace):
                    fail(f"{location} contains an unapproved Secret reference")
            if location.endswith((".secretRef.name", ".secretKeyRef.name")):
                if not _is_expected_secret_reference(value):
                    fail(f"{location} contains an unapproved Secret reference")
        for location, value in walk(document):
            if location.endswith(".image") and isinstance(value, str):
                _validate_image_reference(location, value, expected_image_tag)
        if document["kind"] == "Service":
            spec = document.get("spec") or {}
            expected_type = "NodePort" if name == "tcp-proxy-service" else "ClusterIP"
            if spec.get("type", "ClusterIP") != expected_type:
                fail(f"Service/{name} has an unsafe service type")
            if spec.get("selector") != {"app": name}:
                fail(f"Service/{name} has an unsafe selector")
            ports = [
                (
                    item.get("name"),
                    item.get("port"),
                    item.get("targetPort"),
                )
                for item in spec.get("ports", [])
                if isinstance(item, dict)
            ]
            if ports != EXPECTED_SERVICE_PORTS[name]:
                fail(f"Service/{name} has an unexpected port set")
        if document["kind"] in {"Deployment", "Job"}:
            pod = (document.get("spec") or {}).get("template", {}).get("spec")
            _validate_restricted_pod_security(
                pod,
                f"{document['kind']}/{name}.spec.template.spec",
            )
        if document["kind"] == "Ingress":
            spec = document.get("spec") or {}
            tls = spec.get("tls") or []
            rules = spec.get("rules") or []
            if len(tls) != 1 or tls[0].get("hosts") != [expected_hostname] or tls[0].get(
                "secretName"
            ) != f"{expected_namespace}-tls":
                fail("Ingress/firemud-preview has an unsafe TLS consumer")
            if len(rules) != 1 or rules[0].get("host") != expected_hostname:
                fail("Ingress/firemud-preview has an unsafe host")
            paths = ((rules[0].get("http") or {}).get("paths") or [])
            if len(paths) != 1:
                fail("Ingress/firemud-preview has an unexpected route set")
            route = paths[0]
            backend = route.get("backend") or {}
            service_backend = backend.get("service") or {}
            if (
                route.get("path") != "/"
                or route.get("pathType") != "Prefix"
                or service_backend.get("name") != "spring-cloud-gateway"
                or (service_backend.get("port") or {}).get("number") != 80
            ):
                fail("Ingress/firemud-preview has an unsafe backend")
    if seen != EXPECTED_OBJECTS:
        missing = sorted(EXPECTED_OBJECTS - seen)
        extra = sorted(seen - EXPECTED_OBJECTS)
        fail(f"manifest object set is not closed (missing={missing}, extra={extra})")
    validate_network_policies(documents)
    validate_service_consumers(documents, expected_namespace)


def validate_metadata(
    path: Path,
    manifest: Path,
    repository: str,
    source_run_id: str,
    pr_number: str,
    base_sha: str,
    head_sha: str,
    merge_sha: str,
    image_tag: str,
    hostname: str,
) -> None:
    metadata = json.loads(path.read_text(encoding="utf-8"))
    expected = {
        "schemaVersion": 1,
        "event": "pull_request",
        "repository": repository,
        "sourceWorkflow": ".github/workflows/preview.yml",
        "sourceRunId": int(source_run_id),
        "prNumber": int(pr_number),
        "baseSha": base_sha,
        "headSha": head_sha,
        "mergeSha": merge_sha,
        "hostname": hostname,
        "imageTag": image_tag,
    }
    for key, expected_value in expected.items():
        if metadata.get(key) != expected_value:
            fail(f"metadata {key} does not match trusted event data")
    digest = hashlib.sha256(manifest.read_bytes()).hexdigest()
    if metadata.get("manifestSha256") != digest:
        fail("manifest checksum does not match metadata")
    validate_manifest(manifest, f"pr-{pr_number}", image_tag, hostname)


def main() -> int:
    if len(sys.argv) == 4 and sys.argv[1] == "sanitize":
        try:
            sanitize(Path(sys.argv[2]), Path(sys.argv[3]))
        except (OSError, ValueError, TypeError, yaml.YAMLError) as exc:
            print(f"preview artifact rejected: {exc}", file=sys.stderr)
            return 1
        return 0
    if len(sys.argv) == 5 and sys.argv[1] == "inject":
        try:
            inject_telnet_port(Path(sys.argv[2]), Path(sys.argv[3]), int(sys.argv[4]))
        except (OSError, ValueError, TypeError, yaml.YAMLError) as exc:
            print(f"preview Telnet port injection rejected: {exc}", file=sys.stderr)
            return 1
        return 0
    if len(sys.argv) != 11:
        print(
            "usage: validate-preview-artifact.py <metadata> <manifest> <repository> "
            "<source-run-id> <pr-number> <base-sha> <head-sha> <merge-sha> "
            "<image-tag> <hostname>\n"
            "       validate-preview-artifact.py sanitize <render> <output>\n"
            "       validate-preview-artifact.py inject <render> <output> <port>",
            file=sys.stderr,
        )
        return 2
    try:
        validate_metadata(
            Path(sys.argv[1]),
            Path(sys.argv[2]),
            *sys.argv[3:],
        )
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError, yaml.YAMLError) as exc:
        print(f"preview artifact rejected: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
