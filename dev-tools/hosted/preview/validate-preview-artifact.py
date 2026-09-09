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
    "Service": SERVICE_IMAGES
    | {"spring-cloud-gateway-mtls", "postgres", "redis-coord", "redis-cache", "minio"},
    "ConfigMap": {"firemud-config", "firemud-seed-sql"},
    "PersistentVolumeClaim": {"postgres-data", "redis-coord-data", "redis-cache-data", "minio-data"},
    "Job": {"firemud-seed"},
    "Ingress": {"firemud-preview"},
    "NetworkPolicy": {
        "internal-services",
        "internal-services-egress",
        "account-service-controller-ingress",
        "spring-cloud-gateway-ingress",
        "tcp-proxy-service-egress",
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
EXPECTED_TOP_LEVEL_LABELS = {
    "app.kubernetes.io/name": "firemud",
    "app.kubernetes.io/managed-by": "Helm",
    "helm.sh/chart": "firemud-0.1.0",
}


def _application_service_spec(
    name: str,
    ports: tuple[tuple[str, int, int], ...],
    service_type: str = "ClusterIP",
) -> dict:
    return {
        "selector": {"app": name},
        "ports": [
            {
                "port": port,
                "name": port_name,
                "targetPort": target_port,
                "protocol": "TCP",
            }
            for port_name, port, target_port in ports
        ],
        "type": service_type,
    }


EXPECTED_SERVICE_SPECS = {
    **{
        service: _application_service_spec(
            service,
            (("tcp-8080", 8080, 8080), ("tcp-6565", 6565, 6565)),
        )
        for service in SERVICE_IMAGES - {"spring-cloud-gateway", "tcp-proxy-service"}
    },
    "spring-cloud-gateway": _application_service_spec(
        "spring-cloud-gateway",
        (("tcp-80", 80, 8080), ("tcp-6565", 6565, 6565)),
    ),
    "spring-cloud-gateway-mtls": {
        "selector": {"app": "spring-cloud-gateway"},
        "ports": [
            {
                "name": "wss-mtls",
                "port": 443,
                "targetPort": 8443,
                "protocol": "TCP",
            }
        ],
        "type": "ClusterIP",
    },
    "tcp-proxy-service": _application_service_spec(
        "tcp-proxy-service",
        (("tcp-2323", 2323, 2323),),
        "NodePort",
    ),
    "postgres": {
        "selector": {"app": "postgres"},
        "ports": [{"port": 5432, "targetPort": 5432}],
    },
    "redis-coord": {
        "selector": {"app": "redis-coord"},
        "ports": [{"port": 6379, "targetPort": 6379}],
    },
    "redis-cache": {
        "selector": {"app": "redis-cache"},
        "ports": [{"port": 6379, "targetPort": 6379}],
    },
    "minio": {
        "selector": {"app": "minio"},
        "ports": [{"port": 9000, "targetPort": 9000}],
    },
}


def _infrastructure_container_security(uid: int) -> dict:
    return {
        "allowPrivilegeEscalation": False,
        "readOnlyRootFilesystem": False,
        "runAsUser": uid,
        "runAsGroup": uid,
        "capabilities": {"drop": ["ALL"]},
    }


def _infrastructure_deployment_spec(
    name: str,
    uid: int,
    image: str,
    args: list[str],
    port: int,
    volume_name: str,
    mount_path: str,
    env: list[dict] | None = None,
) -> dict:
    container = {
        "name": name,
        "securityContext": _infrastructure_container_security(uid),
        "image": image,
        "args": args,
        "ports": [{"containerPort": port}],
        "volumeMounts": [{"name": volume_name, "mountPath": mount_path}],
    }
    if env is not None:
        container["env"] = env
    return {
        "replicas": 1,
        "selector": {"matchLabels": {"app": name}},
        "template": {
            "metadata": {"labels": {"app": name}},
            "spec": {
                "securityContext": {
                    "runAsNonRoot": True,
                    "runAsUser": uid,
                    "runAsGroup": uid,
                    "fsGroup": uid,
                    "seccompProfile": {"type": "RuntimeDefault"},
                },
                "containers": [container],
                "volumes": [
                    {
                        "name": volume_name,
                        "persistentVolumeClaim": {"claimName": volume_name},
                    }
                ],
            },
        },
    }


EXPECTED_INFRASTRUCTURE_DEPLOYMENT_SPECS = {
    "postgres": _infrastructure_deployment_spec(
        "postgres",
        999,
        "postgres:16",
        ["postgres", "-c", "max_connections=200"],
        5432,
        "postgres-data",
        "/var/lib/postgresql/data",
        [
            {"name": "PGDATA", "value": "/var/lib/postgresql/data/pgdata"},
            {"name": "POSTGRES_DB", "value": "firemud"},
            {
                "name": "POSTGRES_USER",
                "valueFrom": {
                    "secretKeyRef": {
                        "name": "firemud-secret",
                        "key": "FIREMUD_POSTGRES_USER",
                    }
                },
            },
            {
                "name": "POSTGRES_PASSWORD",
                "valueFrom": {
                    "secretKeyRef": {
                        "name": "firemud-secret",
                        "key": "FIREMUD_POSTGRES_PASSWORD",
                    }
                },
            },
        ],
    ),
    "redis-coord": _infrastructure_deployment_spec(
        "redis-coord",
        999,
        "redis:7.4.3",
        ["redis-server", "--appendonly", "yes"],
        6379,
        "redis-coord-data",
        "/data",
    ),
    "redis-cache": _infrastructure_deployment_spec(
        "redis-cache",
        999,
        "redis:7.4.3",
        ["redis-server", "--appendonly", "yes"],
        6379,
        "redis-cache-data",
        "/data",
    ),
    "minio": _infrastructure_deployment_spec(
        "minio",
        1000,
        "minio/minio:RELEASE.2024-05-10T01-41-38Z",
        ["server", "/data"],
        9000,
        "minio-data",
        "/data",
        [
            {
                "name": "MINIO_ROOT_USER",
                "valueFrom": {
                    "secretKeyRef": {
                        "name": "minio-credentials",
                        "key": "accessKey",
                    }
                },
            },
            {
                "name": "MINIO_ROOT_PASSWORD",
                "valueFrom": {
                    "secretKeyRef": {
                        "name": "minio-credentials",
                        "key": "secretKey",
                    }
                },
            },
        ],
    ),
}
INTERNAL_SERVICE_APPS = [
    "account-service",
    "automation-scripting-service",
    "entity-management-service",
    "game-design-service",
    "game-logic-service",
    "game-session-service",
    "logging-admin-service",
    "social-groups-service",
    "world-management-service",
]
INTERNAL_SERVICES_SELECTOR = {
    "matchExpressions": [
        {
            "key": "app",
            "operator": "In",
            "values": INTERNAL_SERVICE_APPS,
        }
    ]
}
INTERNAL_SERVICES_EGRESS = [
    {
        "to": [
            {
                "namespaceSelector": {
                    "matchLabels": {
                        "kubernetes.io/metadata.name": "kube-system"
                    }
                },
                "podSelector": {"matchLabels": {"k8s-app": "kube-dns"}},
            }
        ],
        "ports": [
            {"protocol": "UDP", "port": 53},
            {"protocol": "TCP", "port": 53},
        ],
    },
    {
        "to": [{"podSelector": INTERNAL_SERVICES_SELECTOR}],
        "ports": [
            {"protocol": "TCP", "port": 8080},
            {"protocol": "TCP", "port": 6565},
            {"protocol": "TCP", "port": 4317},
        ],
    },
    {
        "to": [{"podSelector": {"matchLabels": {"app": "postgres"}}}],
        "ports": [{"protocol": "TCP", "port": 5432}],
    },
    {
        "to": [
            {"podSelector": {"matchLabels": {"app": "redis-coord"}}},
            {"podSelector": {"matchLabels": {"app": "redis-cache"}}},
        ],
        "ports": [{"protocol": "TCP", "port": 6379}],
    },
    {
        "to": [{"podSelector": {"matchLabels": {"app": "minio"}}}],
        "ports": [{"protocol": "TCP", "port": 9000}],
    },
]
EXPECTED_INTERNAL_NETWORK_POLICY_SPECS = {
    "internal-services": {
        "podSelector": INTERNAL_SERVICES_SELECTOR,
        "policyTypes": ["Ingress", "Egress"],
        "ingress": [
            {
                "from": [{"podSelector": {}}],
                "ports": [
                    {"protocol": "TCP", "port": 8080},
                    {"protocol": "TCP", "port": 6565},
                    {"protocol": "TCP", "port": 4317},
                ],
            }
        ],
        "egress": INTERNAL_SERVICES_EGRESS,
    },
    "internal-services-egress": {
        "podSelector": INTERNAL_SERVICES_SELECTOR,
        "policyTypes": ["Egress"],
        "egress": INTERNAL_SERVICES_EGRESS,
    },
}
NAME_RE = re.compile(r"^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$")
PROJECTED_SECRET_SOURCE_PATH = re.compile(r"\.projected\.sources\[\d+\]$")
CSI_VOLUME_PATH = re.compile(r"\.csi$")
PROJECTED_SECRET_NAME_LOCATION = re.compile(
    r"\.projected\.sources\[\d+\]\.secret\.name$"
)
CSI_NODE_PUBLISH_SECRET_NAME_LOCATION = re.compile(
    r"\.csi\.nodePublishSecretRef\.name$"
)
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
SANITIZER_SECRET_REFERENCE_SUFFIXES = (
    "-tls",
    "-telnet-tls",
    "-gateway-internal-ws",
    "-tcp-proxy-bridge",
)
SANITIZER_SENSITIVE_KEY = re.compile(
    r"(?:PASSWORD|TOKEN|PRIVATE|ACCESS_KEY|SECRET_KEY|CREDENTIAL)", re.IGNORECASE
)
FIREMUD_CONFIG_FIELDS = frozenset({"apiVersion", "kind", "metadata", "data"})
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


def _require_mapping(value: object, path: str) -> dict:
    if not isinstance(value, dict):
        fail(f"{path} is not an object")
    return value


def _require_mapping_list(value: object, path: str) -> list[dict]:
    if not isinstance(value, list) or not all(
        isinstance(item, dict) for item in value
    ):
        fail(f"{path} is not a list of objects")
    return value


def _validate_object_metadata(document: dict, expected_namespace: str) -> dict:
    """Require the exact Helm-authored metadata admitted into the trusted apply."""

    kind = document.get("kind", "object")
    metadata = _require_mapping(document.get("metadata"), f"{kind}.metadata")
    name = metadata.get("name")
    allowed_fields = {"name", "namespace", "labels"}
    unexpected_fields = set(metadata) - allowed_fields
    if unexpected_fields:
        fail(
            f"{kind}/{name} metadata contains unsupported fields: "
            f"{sorted(unexpected_fields)}"
        )
    expected_labels = {
        **EXPECTED_TOP_LEVEL_LABELS,
        "app.kubernetes.io/instance": expected_namespace,
    }
    if metadata.get("labels") != expected_labels:
        fail(f"{kind}/{name} has unsafe Helm metadata labels")
    namespace = metadata.get("namespace")
    if namespace is not None and namespace != expected_namespace:
        fail(f"{kind}/{name} targets namespace {namespace!r}")
    return metadata


def _validate_workload_selector_metadata(document: dict) -> None:
    """Keep workload labels exact so Services and policies cannot be retargeted."""

    kind = document["kind"]
    name = document["metadata"]["name"]
    spec = _require_mapping(document.get("spec"), f"{kind}/{name}.spec")
    template = _require_mapping(spec.get("template"), f"{kind}/{name}.spec.template")
    template_metadata = _require_mapping(
        template.get("metadata"), f"{kind}/{name}.spec.template.metadata"
    )
    expected_app = "firemud-seed" if kind == "Job" else name
    if template_metadata != {"labels": {"app": expected_app}}:
        fail(f"{kind}/{name} has unsafe pod-template metadata")
    if kind == "Deployment" and spec.get("selector") != {
        "matchLabels": {"app": expected_app}
    }:
        fail(f"Deployment/{name} has an unsafe selector")


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
            f"{expected_namespace}-gateway-internal-ws",
            f"{expected_namespace}-tcp-proxy-bridge",
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
    _validate_firemud_config_shape(document)
    data = _require_mapping(
        document.get("data"),
        "ConfigMap/firemud-config.data",
    )
    document["data"] = {
        key: value
        for key, value in data.items()
        if not SANITIZER_SENSITIVE_KEY.search(key)
    }
    return document


def _validate_firemud_config_shape(document: dict) -> None:
    if document.get("kind") != "ConfigMap":
        return
    metadata = document.get("metadata")
    if not isinstance(metadata, dict) or metadata.get("name") != "firemud-config":
        return
    actual_fields = set(document)
    if actual_fields != FIREMUD_CONFIG_FIELDS:
        fail(
            "ConfigMap/firemud-config has unsafe top-level fields "
            f"(missing={sorted(FIREMUD_CONFIG_FIELDS - actual_fields)}, "
            f"extra={sorted(actual_fields - FIREMUD_CONFIG_FIELDS)})"
        )
    _require_mapping(document.get("data"), "ConfigMap/firemud-config.data")


def _validate_sanitized_secret_refs(value: object, path: str = "object") -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "secretName" and not _is_sanitized_secret_reference(child):
                fail(f"{path}.{key} contains an unapproved Secret reference")
            if key in {"secretRef", "secretKeyRef"} and isinstance(child, dict):
                name = child.get("name")
                if not _is_sanitized_secret_reference(name):
                    fail(f"{path}.{key}.name contains an unapproved Secret reference")
            if (
                key == "secret"
                and isinstance(child, dict)
                and "name" in child
                and PROJECTED_SECRET_SOURCE_PATH.search(path)
            ):
                name = child["name"]
                if not _is_sanitized_secret_reference(name):
                    fail(f"{path}.{key}.name contains an unapproved Secret reference")
            if (
                key == "nodePublishSecretRef"
                and isinstance(child, dict)
                and CSI_VOLUME_PATH.search(path)
            ):
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
            pod = ((raw.get("spec") or {}).get("template") or {}).get("spec")
            _validate_restricted_pod_security(
                pod,
                f"{kind}/{metadata['name']}.spec.template.spec",
            )
        sanitized = _clean_config_map(copy.deepcopy(raw))
        if sanitized is None:
            continue
        if sanitized.get("kind") == "Service":
            name = sanitized["metadata"]["name"]
            spec = _require_mapping(sanitized.get("spec"), f"Service/{name}.spec")
            ports = _require_mapping_list(
                spec.get("ports"), f"Service/{name}.spec.ports"
            )
            for port in ports:
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


def inject_telnet_port(
    source: Path,
    destination: Path,
    port: int,
    expected_namespace: str | None = None,
) -> None:
    """Add only trusted runtime target data after artifact validation."""

    if not MIN_PREVIEW_TELNET_PORT <= port <= MAX_PREVIEW_TELNET_PORT:
        fail(
            "preview telnet port must be between "
            f"{MIN_PREVIEW_TELNET_PORT} and {MAX_PREVIEW_TELNET_PORT}"
        )
    documents = list(yaml.safe_load_all(source.read_text(encoding="utf-8")))
    matches = []
    for document in documents:
        if not isinstance(document, dict):
            fail("validated preview render contains a non-object document")
        metadata = (
            _validate_object_metadata(document, expected_namespace)
            if expected_namespace is not None
            else _require_mapping(
                document.get("metadata"),
                f"{document.get('kind', 'object')}.metadata",
            )
        )
        if expected_namespace is not None:
            namespace = metadata.get("namespace")
            if namespace not in (None, expected_namespace):
                fail(
                    f"{document.get('kind')}/{metadata.get('name')} targets namespace {namespace!r}"
                )
            metadata["namespace"] = expected_namespace
        if document.get("kind") != "Service":
            continue
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


def validate_runtime_target(path: Path, expected_namespace: str, expected_port: int) -> None:
    """Verify the only trusted mutations made after closed artifact validation."""

    if not re.fullmatch(r"pr-[1-9][0-9]*", expected_namespace):
        fail(f"runtime namespace is not canonical: {expected_namespace!r}")
    if not MIN_PREVIEW_TELNET_PORT <= expected_port <= MAX_PREVIEW_TELNET_PORT:
        fail(
            "preview telnet port must be between "
            f"{MIN_PREVIEW_TELNET_PORT} and {MAX_PREVIEW_TELNET_PORT}"
        )
    documents = list(yaml.safe_load_all(path.read_text(encoding="utf-8")))
    if not documents:
        fail("prepared preview render is empty")
    node_ports: list[tuple[str, object, object]] = []
    for index, document in enumerate(documents):
        if not isinstance(document, dict):
            fail(f"prepared preview document {index} is not an object")
        metadata = _validate_object_metadata(document, expected_namespace)
        name = metadata.get("name")
        if metadata.get("namespace") != expected_namespace:
            fail(
                f"{document.get('kind')}/{name} must explicitly target namespace "
                f"{expected_namespace!r}"
            )
        if document.get("kind") in {"Deployment", "Job"}:
            _validate_workload_selector_metadata(document)
        for location, value in walk(document):
            if location.endswith(".nodePort"):
                node_ports.append((f"{document.get('kind')}/{name}", location, value))
    expected = (
        "Service/tcp-proxy-service",
        "object.spec.ports[0].nodePort",
        expected_port,
    )
    if node_ports != [expected]:
        fail(
            "prepared preview render must contain only the exact allocated TCP Proxy "
            f"NodePort {expected_port}; observed {node_ports!r}"
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
            expected_mounts["gateway-ws-client-tls"] = (
                "/gateway-ws-client-tls",
                f"{expected_namespace}-tcp-proxy-bridge",
            )
        if service == "spring-cloud-gateway":
            expected_mounts["gateway-ws-server-tls"] = (
                "/gateway-ws-server-tls",
                f"{expected_namespace}-gateway-internal-ws",
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
            if volume_name in {"gateway-ws-server-tls", "gateway-ws-client-tls"} and mount != {
                "name": volume_name,
                "mountPath": mount_path,
                "readOnly": True,
            }:
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
                if volume_name in {"gateway-ws-server-tls", "gateway-ws-client-tls"}:
                    expected_source = {
                        "secretName": source_name,
                        "items": [
                            {"key": "tls.crt", "path": "tls.crt"},
                            {"key": "tls.key", "path": "tls.key"},
                            {"key": "ca.crt", "path": "ca.crt"},
                        ],
                    }
                    if source != expected_source:
                        fail(f"Deployment/{service} has an unsafe {volume_name} projection")


def validate_services(documents: list[dict]) -> None:
    """Require the exact trusted preview Service specs."""

    for document in documents:
        if document.get("kind") != "Service":
            continue
        name = document.get("metadata", {}).get("name")
        spec = _require_mapping(document.get("spec"), f"Service/{name}.spec")
        expected_spec = EXPECTED_SERVICE_SPECS.get(name)
        if expected_spec is None:
            fail(f"Service/{name} is not an approved preview Service")
        expected_type = expected_spec.get("type", "ClusterIP")
        if spec.get("type", "ClusterIP") != expected_type:
            fail(f"Service/{name} has an unsafe service type")
        if spec.get("selector") != expected_spec["selector"]:
            fail(f"Service/{name} has an unsafe selector")
        service_ports = _require_mapping_list(
            spec.get("ports"), f"Service/{name}.spec.ports"
        )
        ports = [
            (
                item.get("name"),
                item.get("port"),
                item.get("targetPort"),
            )
            for item in service_ports
        ]
        expected_ports = [
            (
                item.get("name"),
                item.get("port"),
                item.get("targetPort"),
            )
            for item in expected_spec["ports"]
        ]
        if ports != expected_ports:
            fail(f"Service/{name} has an unexpected port set")
        if spec != expected_spec:
            fail(f"Service/{name} has an unsafe spec")


def _validate_internal_network_policies(policies: dict[str, dict]) -> None:
    for name, expected_spec in EXPECTED_INTERNAL_NETWORK_POLICY_SPECS.items():
        if policies[name].get("spec") != expected_spec:
            fail(f"NetworkPolicy/{name} has an unsafe spec")


def validate_network_policies(documents: list[dict]) -> None:
    """Keep the runtime policy set and every allowed traffic exception exact."""

    raw_policies = [
        document for document in documents if document.get("kind") == "NetworkPolicy"
    ]
    policies = {
        document.get("metadata", {}).get("name"): document
        for document in raw_policies
    }
    expected_names = EXPECTED_NAMES["NetworkPolicy"]
    if len(raw_policies) != len(policies) or set(policies) != expected_names:
        fail(
            "runtime NetworkPolicy set is not closed "
            f"(missing={sorted(expected_names - set(policies))}, "
            f"extra={sorted(set(policies) - expected_names)})"
        )
    _validate_internal_network_policies(policies)

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

    gateway_ingress = (policies["spring-cloud-gateway-ingress"].get("spec") or {})
    expected_gateway_ingress = {
        "podSelector": {"matchLabels": {"app": "spring-cloud-gateway"}},
        "policyTypes": ["Ingress"],
        "ingress": [
            {
                "from": [{"podSelector": {"matchLabels": {"app": "tcp-proxy-service"}}}],
                "ports": [{"protocol": "TCP", "port": 8443}],
            },
            {
                "from": [
                    {
                        "namespaceSelector": {
                            "matchLabels": {"kubernetes.io/metadata.name": "kube-system"}
                        },
                        "podSelector": {
                            "matchLabels": {"app.kubernetes.io/name": "traefik"}
                        },
                    }
                ],
                "ports": [{"protocol": "TCP", "port": 8080}],
            },
            {
                "from": [{"podSelector": {}}],
                "ports": [
                    {"protocol": "TCP", "port": 8080},
                    {"protocol": "TCP", "port": 6565},
                ],
            },
        ],
    }
    if gateway_ingress != expected_gateway_ingress:
        fail("NetworkPolicy/spring-cloud-gateway-ingress has an unsafe exception")

    proxy_egress = (policies["tcp-proxy-service-egress"].get("spec") or {})
    expected_proxy_egress = {
        "podSelector": {"matchLabels": {"app": "tcp-proxy-service"}},
        "policyTypes": ["Egress"],
        "egress": [
            {
                "to": [
                    {
                        "namespaceSelector": {
                            "matchLabels": {"kubernetes.io/metadata.name": "kube-system"}
                        },
                        "podSelector": {"matchLabels": {"k8s-app": "kube-dns"}},
                    }
                ],
                "ports": [
                    {"protocol": "UDP", "port": 53},
                    {"protocol": "TCP", "port": 53},
                ],
            },
            {
                "to": [
                    {"podSelector": {"matchLabels": {"app": "spring-cloud-gateway"}}}
                ],
                "ports": [{"protocol": "TCP", "port": 8443}],
            },
            {
                "to": [
                    {"podSelector": {"matchLabels": {"app": "game-session-service"}}}
                ],
                "ports": [{"protocol": "TCP", "port": 6565}],
            },
        ],
    }
    if proxy_egress != expected_proxy_egress:
        fail("NetworkPolicy/tcp-proxy-service-egress has an unsafe exception")


def validate_infrastructure_deployments(documents: list[dict]) -> None:
    """Require exact trusted specs for fixed-image infrastructure pods."""

    deployments = {
        document.get("metadata", {}).get("name"): document
        for document in documents
        if document.get("kind") == "Deployment"
        and document.get("metadata", {}).get("name")
        in EXPECTED_INFRASTRUCTURE_DEPLOYMENT_SPECS
    }
    if set(deployments) != set(EXPECTED_INFRASTRUCTURE_DEPLOYMENT_SPECS):
        fail("preview infrastructure Deployment set is incomplete")
    for name, expected_spec in EXPECTED_INFRASTRUCTURE_DEPLOYMENT_SPECS.items():
        if deployments[name].get("spec") != expected_spec:
            fail(f"Deployment/{name} has an unsafe infrastructure spec")


def validate_ingress(
    document: dict,
    expected_namespace: str,
    expected_hostname: str,
) -> None:
    """Require the complete trusted preview Ingress routing contract."""

    ingress_path = "Ingress/firemud-preview.spec"
    spec = _require_mapping(document.get("spec"), ingress_path)
    tls = _require_mapping_list(spec.get("tls"), f"{ingress_path}.tls")
    rules = _require_mapping_list(spec.get("rules"), f"{ingress_path}.rules")
    if (
        len(tls) != 1
        or tls[0].get("hosts") != [expected_hostname]
        or tls[0].get("secretName") != f"{expected_namespace}-tls"
    ):
        fail("Ingress/firemud-preview has an unsafe TLS consumer")
    if len(rules) != 1 or rules[0].get("host") != expected_hostname:
        fail("Ingress/firemud-preview has an unsafe host")
    http = _require_mapping(rules[0].get("http"), f"{ingress_path}.rules[0].http")
    paths = _require_mapping_list(
        http.get("paths"), f"{ingress_path}.rules[0].http.paths"
    )
    if len(paths) != 1:
        fail("Ingress/firemud-preview has an unexpected route set")
    route = paths[0]
    backend = _require_mapping(
        route.get("backend"),
        f"{ingress_path}.rules[0].http.paths[0].backend",
    )
    service_backend = _require_mapping(
        backend.get("service"),
        f"{ingress_path}.rules[0].http.paths[0].backend.service",
    )
    service_port = _require_mapping(
        service_backend.get("port"),
        f"{ingress_path}.rules[0].http.paths[0].backend.service.port",
    )
    if (
        route.get("path") != "/"
        or route.get("pathType") != "Prefix"
        or service_backend.get("name") != "spring-cloud-gateway"
        or service_port.get("number") != 80
    ):
        fail("Ingress/firemud-preview has an unsafe backend")

    expected_spec = {
        "ingressClassName": "traefik",
        "tls": [
            {
                "hosts": [expected_hostname],
                "secretName": f"{expected_namespace}-tls",
            }
        ],
        "rules": [
            {
                "host": expected_hostname,
                "http": {
                    "paths": [
                        {
                            "path": "/",
                            "pathType": "Prefix",
                            "backend": {
                                "service": {
                                    "name": "spring-cloud-gateway",
                                    "port": {"number": 80},
                                }
                            },
                        }
                    ]
                },
            }
        ],
    }
    if spec != expected_spec:
        fail("Ingress/firemud-preview has an unsafe spec")


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
        metadata = _require_mapping(
            document.get("metadata"), f"{document.get('kind', 'object')}.metadata"
        )
        name = metadata.get("name")
        if not isinstance(name, str) or not NAME_RE.fullmatch(name):
            fail(f"manifest object has unsafe name: {name!r}")
        if name not in EXPECTED_NAMES[document["kind"]]:
            fail(f"manifest contains unexpected {document['kind']}/{name}")
        _validate_firemud_config_shape(document)
        if document["kind"] in {"Deployment", "Job"}:
            pod = ((document.get("spec") or {}).get("template") or {}).get("spec")
            _validate_restricted_pod_security(
                pod,
                f"{document['kind']}/{name}.spec.template.spec",
            )
        if document["kind"] == "Ingress":
            validate_ingress(document, expected_namespace, expected_hostname)
        metadata = _validate_object_metadata(document, expected_namespace)
        object_key = (document["kind"], name)
        if object_key in seen:
            fail(f"manifest contains duplicate {document['kind']}/{name}")
        seen.add(object_key)
        _validate_no_annotations(document)
        for location, value in walk(document):
            if location.endswith(".nodePort"):
                fail(f"{location} retains a PR-selected nodePort")
            if location.endswith(".secretName") and not _is_manifest_secret_reference(
                value, expected_namespace
            ):
                fail(f"{location} contains an unapproved Secret reference")
            if location.endswith(
                (".secretRef.name", ".secretKeyRef.name")
            ) and not _is_expected_secret_reference(value):
                fail(f"{location} contains an unapproved Secret reference")
            if (
                PROJECTED_SECRET_NAME_LOCATION.search(location)
                or CSI_NODE_PUBLISH_SECRET_NAME_LOCATION.search(location)
            ) and not _is_manifest_secret_reference(value, expected_namespace):
                fail(f"{location} contains an unapproved Secret reference")
        for location, value in walk(document):
            if location.endswith(".image") and isinstance(value, str):
                _validate_image_reference(location, value, expected_image_tag)
        if document["kind"] in {"Deployment", "Job"}:
            _validate_workload_selector_metadata(document)
    if seen != EXPECTED_OBJECTS:
        missing = sorted(EXPECTED_OBJECTS - seen)
        extra = sorted(seen - EXPECTED_OBJECTS)
        fail(f"manifest object set is not closed (missing={missing}, extra={extra})")
    validate_services(documents)
    validate_network_policies(documents)
    validate_infrastructure_deployments(documents)
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
    metadata = _require_mapping(
        json.loads(path.read_text(encoding="utf-8")), "metadata"
    )
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
    if len(sys.argv) == 6 and sys.argv[1] == "inject":
        try:
            inject_telnet_port(
                Path(sys.argv[2]),
                Path(sys.argv[3]),
                int(sys.argv[5]),
                sys.argv[4],
            )
        except (OSError, ValueError, TypeError, yaml.YAMLError) as exc:
            print(f"preview Telnet port injection rejected: {exc}", file=sys.stderr)
            return 1
        return 0
    if len(sys.argv) == 5 and sys.argv[1] == "runtime-target":
        try:
            validate_runtime_target(Path(sys.argv[2]), sys.argv[3], int(sys.argv[4]))
        except (OSError, ValueError, TypeError, yaml.YAMLError) as exc:
            print(f"preview runtime target rejected: {exc}", file=sys.stderr)
            return 1
        return 0
    if len(sys.argv) != 11:
        print(
            "usage: validate-preview-artifact.py <metadata> <manifest> <repository> "
            "<source-run-id> <pr-number> <base-sha> <head-sha> <merge-sha> "
            "<image-tag> <hostname>\n"
            "       validate-preview-artifact.py sanitize <render> <output>\n"
            "       validate-preview-artifact.py inject <render> <output> <namespace> <port>\n"
            "       validate-preview-artifact.py runtime-target <render> <namespace> <port>",
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
