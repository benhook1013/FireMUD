#!/usr/bin/env python3
"""Validate a PR render in the trusted default-branch workflow."""

from __future__ import annotations

import copy
import functools
import hashlib
import json
import re
import sys
import typing
from pathlib import Path

import yaml


@functools.lru_cache(maxsize=1)
def _expected_chart_label(chart_metadata_path: Path) -> str:
    try:
        chart_metadata = yaml.safe_load(chart_metadata_path.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as exc:
        raise ValueError(
            f"could not load trusted chart metadata: {chart_metadata_path}"
        ) from exc
    if not isinstance(chart_metadata, dict):
        raise TypeError("trusted chart metadata must be a mapping")
    name = chart_metadata.get("name")
    version = chart_metadata.get("version")
    if not isinstance(name, str) or not name or name.strip() != name:
        raise ValueError("trusted chart metadata name must be a nonempty string")
    if not isinstance(version, str) or not version or version.strip() != version:
        raise ValueError("trusted chart metadata version must be a nonempty string")
    return f"{name}-{version.replace('+', '_')}"


TRUSTED_CHART_METADATA = (
    Path(__file__).resolve().parents[3] / "k8s/helm/firemud/Chart.yaml"
)
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
PUBLICATION_GRPC_WORKLOADS = {
    "game-design-service",
    "world-management-service",
    "entity-management-service",
    "game-logic-service",
    "automation-scripting-service",
}
ACCOUNT_GRPC_WORKLOADS = {"account-service"}
GAME_SESSION_GRPC_WORKLOADS = {"game-session-service"}
DISTINCT_GRPC_WORKLOADS = (
    PUBLICATION_GRPC_WORKLOADS | ACCOUNT_GRPC_WORKLOADS | GAME_SESSION_GRPC_WORKLOADS
)
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
        "spring-cloud-gateway-egress",
        "tcp-proxy-service-egress",
    },
}
EXPECTED_PVC_SPECS = {
    "postgres-data": {
        "accessModes": ["ReadWriteOnce"],
        "storageClassName": "local-path",
        "resources": {"requests": {"storage": "5Gi"}},
    },
    "redis-coord-data": {
        "accessModes": ["ReadWriteOnce"],
        "storageClassName": "local-path",
        "resources": {"requests": {"storage": "1Gi"}},
    },
    "redis-cache-data": {
        "accessModes": ["ReadWriteOnce"],
        "storageClassName": "local-path",
        "resources": {"requests": {"storage": "1Gi"}},
    },
    "minio-data": {
        "accessModes": ["ReadWriteOnce"],
        "storageClassName": "local-path",
        "resources": {"requests": {"storage": "5Gi"}},
    },
}


def walk(value: object, path: str = "object"):
    yield path, value
    if isinstance(value, dict):
        for key, child in value.items():
            yield from walk(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from walk(child, f"{path}[{index}]")


EXPECTED_OBJECTS = {
    (kind, name)
    for kind, names in EXPECTED_NAMES.items()
    for name in names
}
INFRASTRUCTURE_IMAGES = {
    "postgres:16",
    "redis:7.4.3",
    "quay.io/minio/minio:RELEASE.2024-05-10T01-41-38Z",
}
EXPECTED_SECRET_REFS = {
    "firemud-secret",
    "jwt-signing-keys",
    "minio-credentials",
    "firemud-grpc-tls",
} | {f"firemud-grpc-{service}" for service in DISTINCT_GRPC_WORKLOADS}
CANONICAL_INGRESS_ISSUER = "letsencrypt-prod"
ALLOCATED_TELNET_PORT_ANNOTATION = "firemud.dev/allocated-telnet-port"
CERTIFICATE_IDENTITY_MODES = {"standalone", "hosted-controller"}
EXPOSURE_MODES = {"private", "public"}
CERTIFICATE_IDENTITY_LABEL = "firemud.dev/certificate-identity-mode"
TCP_PROXY_IDENTITY_MODE_LABEL = CERTIFICATE_IDENTITY_LABEL
TRUSTED_HOSTED_VALUES = (
    Path(__file__).resolve().parents[3]
    / "k8s/helm/firemud/values-hosted-shared.example.yaml"
)
HOSTED_REDACTED_CONFIG_KEYS = frozenset(
    {"ASSET_STORE_ACCESS_KEY", "ASSET_STORE_SECRET_KEY"}
)
GATEWAY_HTTP_ROUTE_APPS = (
    "game-session-service",
    "logging-admin-service",
    "game-design-service",
    "account-service",
    "social-groups-service",
)
EXPECTED_TOP_LEVEL_LABELS = {
    "app.kubernetes.io/name": "firemud",
    "app.kubernetes.io/managed-by": "Helm",
}


def _expected_top_level_labels() -> dict[str, str]:
    return {
        **EXPECTED_TOP_LEVEL_LABELS,
        "helm.sh/chart": _expected_chart_label(TRUSTED_CHART_METADATA),
    }


def _expected_object_labels(expected_namespace: str) -> dict[str, str]:
    return {
        **_expected_top_level_labels(),
        "app.kubernetes.io/instance": expected_namespace,
    }


def _validate_certificate_identity_mode(certificate_identity_mode: str) -> None:
    if certificate_identity_mode not in CERTIFICATE_IDENTITY_MODES:
        fail("preview certificate identity mode is not canonical")


def _resolve_exposure_mode(
    certificate_identity_mode: str | None,
    exposure_mode: str | None,
    service_type: str | None = None,
) -> str:
    """Resolve exposure from the validated TCP Proxy Service shape.

    Certificate identity and transport exposure are independent contracts.  An
    explicit exposure argument is therefore only checked against the Service
    shape, never inferred from the certificate identity mode.
    """

    if certificate_identity_mode is not None:
        _validate_certificate_identity_mode(certificate_identity_mode)
    if exposure_mode is not None and exposure_mode not in EXPOSURE_MODES:
        fail("preview exposure mode is not canonical")
    if service_type is None:
        if exposure_mode is None:
            fail("preview exposure mode requires a validated TCP Proxy Service")
        return exposure_mode
    expected_mode = _exposure_mode_for_service_type(service_type)
    if exposure_mode is not None and exposure_mode != expected_mode:
        fail(
            "preview exposure mode does not match TCP Proxy Service type: "
            f"expected {expected_mode!r}, actual {exposure_mode!r}"
        )
    return expected_mode


def _exposure_mode_for_service_type(service_type: object) -> str:
    if service_type == "ClusterIP":
        return "private"
    if service_type == "NodePort":
        return "public"
    fail("validated TCP Proxy Service has no canonical exposure mode")


def _expected_names_for_mode(certificate_identity_mode: str) -> dict[str, set[str]]:
    _validate_certificate_identity_mode(certificate_identity_mode)
    expected_names = {
        kind: set(names) for kind, names in EXPECTED_NAMES.items()
    }
    if certificate_identity_mode == "standalone":
        expected_names["NetworkPolicy"].discard(
            "account-service-controller-ingress"
        )
    return expected_names


def _is_tcp_proxy_identity_object(document: dict) -> bool:
    return document.get("kind") in {"Deployment", "Service"} and (
        isinstance(document.get("metadata"), dict)
        and document["metadata"].get("name") == "tcp-proxy-service"
    )


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


POSTGRES_DATA_LAYOUT_CHECK_INIT_CONTAINER = {
    "name": "postgres-data-layout-check",
    "securityContext": {
        "allowPrivilegeEscalation": False,
        "readOnlyRootFilesystem": True,
        "runAsUser": 999,
        "runAsGroup": 999,
        "capabilities": {"drop": ["ALL"]},
    },
    "image": "postgres:16",
    "command": ["sh", "-ec"],
    "args": [
        """data_root="/var/lib/postgresql/data"
if [ ! -d "$data_root" ] || [ ! -r "$data_root" ] || [ ! -x "$data_root" ]; then
  echo "refusing to start PostgreSQL: cannot inspect mounted data directory ${data_root}" >&2
  exit 1
fi
if [ -e "${data_root}/PG_VERSION" ]; then
  echo "refusing to start PostgreSQL: legacy root PG_VERSION found at ${data_root}/PG_VERSION; migrate the PVC before using nested PGDATA=/var/lib/postgresql/data/pgdata" >&2
  exit 1
fi
"""
    ],
    "volumeMounts": [
        {
            "name": "postgres-data",
            "mountPath": "/var/lib/postgresql/data",
            "readOnly": True,
        }
    ],
}


POSTGRES_INFRASTRUCTURE_DEPLOYMENT_SPEC = _infrastructure_deployment_spec(
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
)
POSTGRES_INFRASTRUCTURE_DEPLOYMENT_SPEC["template"]["spec"]["initContainers"] = [
    copy.deepcopy(POSTGRES_DATA_LAYOUT_CHECK_INIT_CONTAINER)
]


EXPECTED_INFRASTRUCTURE_DEPLOYMENT_SPECS = {
    "postgres": POSTGRES_INFRASTRUCTURE_DEPLOYMENT_SPEC,
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
        "quay.io/minio/minio:RELEASE.2024-05-10T01-41-38Z",
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
GATEWAY_EGRESS = [
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
            {
                "podSelector": {
                    "matchExpressions": [
                        {
                            "key": "app",
                            "operator": "In",
                            "values": list(GATEWAY_HTTP_ROUTE_APPS),
                        }
                    ]
                }
            }
        ],
        "ports": [{"protocol": "TCP", "port": 8080}],
    },
    {
        "to": [{"podSelector": {"matchLabels": {"app": "redis-cache"}}}],
        "ports": [{"protocol": "TCP", "port": 6379}],
    },
    {
        "to": [{"podSelector": {"matchLabels": {"app": "otel-collector"}}}],
        "ports": [{"protocol": "TCP", "port": 4317}],
    },
]
EXPECTED_GATEWAY_NETWORK_POLICY_SPEC = {
    "podSelector": {"matchLabels": {"app": "spring-cloud-gateway"}},
    "policyTypes": ["Egress"],
    "egress": GATEWAY_EGRESS,
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
SANITIZER_SECRET_REFERENCE = re.compile(
    r"^pr-[1-9][0-9]{0,50}-(?:tls|telnet-tls|gateway-internal-ws|tcp-proxy-bridge)$"
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


def _validate_object_metadata(
    document: dict,
    expected_namespace: str,
    allow_trusted_ingress_annotation: bool = False,
    allow_trusted_allocated_telnet_port: bool = False,
    certificate_identity_mode: str | None = None,
) -> dict:
    """Require the exact Helm-authored metadata admitted into the trusted apply."""

    kind = document.get("kind", "object")
    metadata = _require_mapping(document.get("metadata"), f"{kind}.metadata")
    name = metadata.get("name")
    allowed_fields = {"name", "namespace", "labels"}
    if allow_trusted_ingress_annotation or allow_trusted_allocated_telnet_port:
        allowed_fields.add("annotations")
    unexpected_fields = set(metadata) - allowed_fields
    if unexpected_fields:
        fail(
            f"{kind}/{name} metadata contains unsupported fields: "
            f"{sorted(unexpected_fields)}"
        )
    expected_labels = _expected_object_labels(expected_namespace)
    if certificate_identity_mode is not None:
        _validate_certificate_identity_mode(certificate_identity_mode)
        if _is_tcp_proxy_identity_object(document):
            expected_labels[CERTIFICATE_IDENTITY_LABEL] = certificate_identity_mode
    actual_labels = metadata.get("labels")
    if isinstance(actual_labels, dict):
        # Identify the first missing or mismatched required label; exact equality below
        # separately rejects additional labels.
        for label, expected_value in expected_labels.items():
            actual_value = actual_labels.get(label)
            if actual_value != expected_value:
                fail(
                    f"{kind}/{name} label {label!r} mismatch: "
                    f"expected {expected_value!r}, actual {actual_value!r}"
                )
    if actual_labels != expected_labels:
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


def _validate_persistent_volume_claim(document: dict) -> None:
    name = document["metadata"]["name"]
    spec = _require_mapping(document.get("spec"), f"PersistentVolumeClaim/{name}.spec")
    if spec != EXPECTED_PVC_SPECS[name]:
        fail(f"PersistentVolumeClaim/{name} has an unsafe spec")


def _is_expected_secret_reference(value: object) -> bool:
    return isinstance(value, str) and value in EXPECTED_SECRET_REFS


def _is_sanitized_secret_reference(value: object) -> bool:
    return isinstance(value, str) and (
        _is_expected_secret_reference(value)
        or SANITIZER_SECRET_REFERENCE.fullmatch(value) is not None
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
    if "@" in value:
        fail(f"{location} uses a digest image reference; tagged images are required")

    repository, separator, tag = value.rpartition(":")
    if not separator or not repository or not tag:
        fail(f"{location} uses an untagged image")
    service = repository.rsplit("/", 1)[-1]
    if service in SERVICE_IMAGES:
        if repository != f"ghcr.io/benhook1013/{service}":
            fail(f"{location} uses an unapproved service image repository")
        if tag != expected_image_tag:
            fail(f"{location} uses image tag {tag!r}, expected {expected_image_tag!r}")
    else:
        fail(f"{location} uses an unapproved image")


def _expected_gateway_container_env(expected_namespace: str) -> list[dict[str, str]]:
    if re.fullmatch(r"pr-[1-9][0-9]{0,50}", expected_namespace) is None:
        fail(f"runtime namespace is not canonical: {expected_namespace!r}")
    return [
        {"name": "SPRING_PROFILES_ACTIVE", "value": "prod"},
        {"name": "SPRING_FLYWAY_TABLE", "value": "flyway_schema_history_gateway"},
        {"name": "SERVICE_SCHEMA", "value": "gateway"},
        {"name": "FIREMUD_GRPC_CERT_CHAIN_PATH", "value": "/tls/client.crt"},
        {"name": "FIREMUD_GRPC_PRIVATE_KEY_PATH", "value": "/tls/client.key"},
        {"name": "FIREMUD_GRPC_CA_CERT_PATH", "value": "/tls/ca.crt"},
        {"name": "FIREMUD_GATEWAY_TCP_PROXY_TLS_ENABLED", "value": "true"},
        {"name": "FIREMUD_GATEWAY_TCP_PROXY_TLS_BIND_ADDRESS", "value": "0.0.0.0"},
        {"name": "FIREMUD_GATEWAY_TCP_PROXY_TLS_PORT", "value": "8443"},
        {
            "name": "FIREMUD_GATEWAY_TCP_PROXY_TLS_CERT_CHAIN_PATH",
            "value": "/gateway-ws-server-tls/tls.crt",
        },
        {
            "name": "FIREMUD_GATEWAY_TCP_PROXY_TLS_PRIVATE_KEY_PATH",
            "value": "/gateway-ws-server-tls/tls.key",
        },
        {
            "name": "FIREMUD_GATEWAY_TCP_PROXY_TLS_CLIENT_CA_PATH",
            "value": "/gateway-ws-server-tls/ca.crt",
        },
        {"name": "FIREMUD_GATEWAY_TCP_PROXY_TRUST_ENVIRONMENT", "value": "pr-preview"},
        {"name": "FIREMUD_GATEWAY_TCP_PROXY_TRUST_PROFILE", "value": "production_uri"},
        {
            "name": "FIREMUD_GATEWAY_TCP_PROXY_TRUST_URI_SAN",
            "value": f"spiffe://firemud/ns/{expected_namespace}/sa/tcp-proxy-service",
        },
    ]


EXPECTED_GATEWAY_ENV_FROM = [
    {"configMapRef": {"name": "firemud-config"}},
    {"secretRef": {"name": "firemud-secret"}},
]


def _validate_gateway_container_environment(
    container: dict, expected_namespace: str
) -> None:
    if container.get("env") != _expected_gateway_container_env(expected_namespace):
        fail("Deployment/spring-cloud-gateway has an unsafe container env")
    if container.get("envFrom") != EXPECTED_GATEWAY_ENV_FROM:
        fail("Deployment/spring-cloud-gateway has an unsafe envFrom contract")


@functools.lru_cache(maxsize=1)
def _trusted_hosted_shared_config() -> dict[str, str]:
    try:
        values = yaml.safe_load(TRUSTED_HOSTED_VALUES.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as exc:
        raise ValueError(
            f"could not load trusted hosted values: {TRUSTED_HOSTED_VALUES}"
        ) from exc
    if not isinstance(values, dict):
        raise TypeError("trusted hosted values must be a mapping")
    preview_stack = values.get("previewStack")
    if not isinstance(preview_stack, dict):
        raise TypeError("trusted hosted values previewStack must be a mapping")
    shared_config = preview_stack.get("sharedConfig")
    if not isinstance(shared_config, dict) or not shared_config:
        raise ValueError("trusted hosted values sharedConfig must be a non-empty mapping")
    if any(not isinstance(key, str) for key in shared_config):
        raise ValueError("trusted hosted values sharedConfig keys must be strings")
    if any(not isinstance(value, str) for value in shared_config.values()):
        raise ValueError("trusted hosted values sharedConfig values must be strings")
    if not HOSTED_REDACTED_CONFIG_KEYS <= set(shared_config):
        raise ValueError(
            "trusted hosted values sharedConfig is missing its canonical redacted keys"
        )
    return dict(shared_config)


def _validate_firemud_config_data(
    data: dict, *, allow_redacted: bool = False
) -> None:
    trusted = _trusted_hosted_shared_config()
    trusted_keys = set(trusted)
    actual_keys = set(data)
    unexpected_keys = actual_keys - trusted_keys
    required_keys = (
        trusted_keys if allow_redacted else trusted_keys - HOSTED_REDACTED_CONFIG_KEYS
    )
    missing_keys = required_keys - actual_keys
    if unexpected_keys or missing_keys:
        fail(
            "ConfigMap/firemud-config.data has unsafe keys "
            f"(missing={sorted(missing_keys)}, "
            f"extra={sorted(unexpected_keys, key=str)})"
        )
    if not allow_redacted:
        unexpected_redacted_keys = actual_keys & HOSTED_REDACTED_CONFIG_KEYS
        if unexpected_redacted_keys:
            fail(
                "ConfigMap/firemud-config.data contains source-only credential keys: "
                f"{sorted(unexpected_redacted_keys)}"
            )
    for key in actual_keys:
        value = data[key]
        if not isinstance(key, str) or not isinstance(value, str):
            fail(
                "ConfigMap/firemud-config.data must contain only string key/value pairs"
            )
        if value != trusted[key]:
            fail(
                f"ConfigMap/firemud-config.data.{key} differs from the trusted hosted value"
            )


def _clean_config_map(document: dict) -> dict:
    metadata = document.get("metadata") or {}
    if metadata.get("name") != "firemud-config":
        return document
    _validate_firemud_config_shape(document, allow_redacted=True)
    _require_mapping(document.get("data"), "ConfigMap/firemud-config.data")
    trusted = _trusted_hosted_shared_config()
    document["data"] = {
        key: trusted[key]
        for key in trusted
        if key not in HOSTED_REDACTED_CONFIG_KEYS
    }
    return document


def _validate_firemud_config_shape(
    document: dict, *, allow_redacted: bool = False
) -> None:
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
    data = _require_mapping(document.get("data"), "ConfigMap/firemud-config.data")
    _validate_firemud_config_data(data, allow_redacted=allow_redacted)


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
    pod_security = pod.get("securityContext")
    if not isinstance(pod_security, dict):
        fail(f"{path}.securityContext is required by restricted Pod Security Admission")
    if pod_security.get("sysctls"):
        fail(f"{path}.securityContext.sysctls are not allowed in the preview runtime")
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
        for security_field, inherited in (
            ("runAsUser", pod_security["runAsUser"]),
            ("runAsGroup", pod_security["runAsGroup"]),
        ):
            value = security.get(security_field, inherited)
            if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
                fail(
                    f"{container_path}.securityContext.{security_field} must be a positive numeric identity"
                )
        capabilities = security.get("capabilities")
        dropped_capabilities = (
            capabilities.get("drop") if isinstance(capabilities, dict) else None
        )
        if (
            not isinstance(dropped_capabilities, list)
            or not all(isinstance(value, str) for value in dropped_capabilities)
            or "ALL" not in dropped_capabilities
        ):
            fail(f"{container_path}.securityContext.capabilities must drop ALL")
        additions = set(capabilities.get("add") or [])
        if not additions <= RESTRICTED_CAPABILITY_ADDITIONS:
            fail(
                f"{container_path}.securityContext.capabilities adds unsafe capabilities: "
                f"{sorted(additions - RESTRICTED_CAPABILITY_ADDITIONS)}"
            )
        if "procMount" in security and security["procMount"] != "Default":
            fail(f"{container_path}.securityContext.procMount must be Default")
        volume_mounts = _require_mapping_list(
            container.get("volumeMounts", []), f"{container_path}.volumeMounts"
        )
        for mount_index, mount in enumerate(volume_mounts):
            if mount.get("mountPropagation") not in (None, "None"):
                fail(
                    f"{container_path}.volumeMounts[{mount_index}].mountPropagation is forbidden"
                )
        ports = _require_mapping_list(
            container.get("ports", []), f"{container_path}.ports"
        )
        for port_index, port in enumerate(ports):
            if "hostPort" in port:
                fail(f"{container_path}.ports[{port_index}].hostPort is forbidden")
        for probe_field in ("livenessProbe", "readinessProbe", "startupProbe", "lifecycle"):
            action = container.get(probe_field)
            if not isinstance(action, dict):
                continue
            for nested_path, nested in walk(action, f"{container_path}.{probe_field}"):
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
        name = metadata["name"]
        if not isinstance(name, str) or name not in EXPECTED_NAMES[kind]:
            fail(f"{kind}/{name} is not an approved preview object")
        if metadata.get("namespace") in {"firemud-system", "kube-system"}:
            fail(f"{kind}/{metadata['name']} targets a control namespace")
        if kind in {"Deployment", "Job"}:
            spec = _require_mapping(raw.get("spec"), f"{kind}/{metadata['name']}.spec")
            template = _require_mapping(
                spec.get("template"), f"{kind}/{metadata['name']}.spec.template"
            )
            pod = _require_mapping(
                template.get("spec"),
                f"{kind}/{metadata['name']}.spec.template.spec",
            )
            _validate_restricted_pod_security(
                pod,
                f"{kind}/{metadata['name']}.spec.template.spec",
            )
        sanitized = _clean_config_map(copy.deepcopy(raw))
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
    certificate_identity_mode: str | None = None,
    exposure_mode: str | None = None,
) -> None:
    """Add only trusted runtime target data after artifact validation.

    Public previews receive the allocator-owned NodePort.  Private previews
    deliberately receive no port mutation; the zero argument is only the
    controller's internal sentinel and is never written to the Service.
    """

    if expected_namespace is None:
        fail("preview runtime namespace is required")
    if not re.fullmatch(r"pr-[1-9][0-9]{0,50}", expected_namespace):
        fail(f"runtime namespace is not canonical: {expected_namespace!r}")
    if certificate_identity_mode is not None:
        _validate_certificate_identity_mode(certificate_identity_mode)
    if exposure_mode is not None and exposure_mode not in EXPOSURE_MODES:
        fail("preview exposure mode is not canonical")
    documents = list(yaml.safe_load_all(source.read_text(encoding="utf-8")))
    matches = []
    ingress_matches = 0
    for document in documents:
        if not isinstance(document, dict):
            fail("validated preview render contains a non-object document")
        metadata = _validate_object_metadata(
            document,
            expected_namespace,
            certificate_identity_mode=certificate_identity_mode,
        )
        namespace = metadata.get("namespace")
        if namespace not in (None, expected_namespace):
            fail(
                f"{document.get('kind')}/{metadata.get('name')} targets namespace {namespace!r}"
            )
        if "annotations" in metadata:
            fail("validated preview render retains untrusted annotations")
        metadata["namespace"] = expected_namespace
        if document.get("kind") == "Ingress" and metadata.get("name") == "firemud-preview":
            ingress_matches += 1
            if certificate_identity_mode == "standalone":
                metadata["annotations"] = {
                    "cert-manager.io/cluster-issuer": CANONICAL_INGRESS_ISSUER
                }
        if document.get("kind") != "Service":
            continue
        if metadata.get("name") != "tcp-proxy-service":
            continue
        spec = _require_mapping(document.get("spec"), "Service/tcp-proxy-service.spec")
        for service_port in _require_mapping_list(
            spec.get("ports"), "Service/tcp-proxy-service.spec.ports"
        ):
            if service_port.get("port") == 2323:
                matches.append(service_port)
    if len(matches) != 1:
        fail("validated preview render must contain exactly one TCP Proxy Telnet port")
    if certificate_identity_mode == "standalone" and ingress_matches != 1:
        fail("validated preview render must contain exactly one preview Ingress")
    services = [
        document
        for document in documents
        if document.get("kind") == "Service"
        and document.get("metadata", {}).get("name") == "tcp-proxy-service"
    ]
    if len(services) != 1:
        fail("validated preview render must contain exactly one TCP Proxy Service")
    service = services[0]
    service_spec = _require_mapping(
        service.get("spec"), "Service/tcp-proxy-service.spec"
    )
    service_type = service_spec.get("type", "ClusterIP")
    exposure_mode = _resolve_exposure_mode(
        certificate_identity_mode,
        exposure_mode,
        service_type,
    )
    if exposure_mode == "private" and port != 0:
        fail("private preview runtime target requires sentinel Telnet port 0")
    if exposure_mode == "public" and not (
        MIN_PREVIEW_TELNET_PORT <= port <= MAX_PREVIEW_TELNET_PORT
    ):
        fail(
            "preview telnet port must be between "
            f"{MIN_PREVIEW_TELNET_PORT} and {MAX_PREVIEW_TELNET_PORT}"
        )
    if exposure_mode == "private":
        if "nodePort" in matches[0]:
            fail("private preview render must not contain a NodePort")
    else:
        if "nodePort" in matches[0]:
            fail("validated preview render already contains a NodePort")
        matches[0]["nodePort"] = port
        service["metadata"]["annotations"] = {
            ALLOCATED_TELNET_PORT_ANNOTATION: str(port)
        }
    destination.write_text(
        "---\n".join(yaml.safe_dump(document, sort_keys=False) for document in documents),
        encoding="utf-8",
    )


def validate_runtime_target(
    path: Path,
    expected_namespace: str,
    expected_port: int,
    certificate_identity_mode: str | None = None,
    exposure_mode: str | None = None,
) -> None:
    """Verify the only trusted mutations made after closed artifact validation."""

    if not re.fullmatch(r"pr-[1-9][0-9]{0,50}", expected_namespace):
        fail(f"runtime namespace is not canonical: {expected_namespace!r}")
    if certificate_identity_mode is not None:
        _validate_certificate_identity_mode(certificate_identity_mode)
    if exposure_mode is not None and exposure_mode not in EXPOSURE_MODES:
        fail("preview exposure mode is not canonical")
    documents = list(yaml.safe_load_all(path.read_text(encoding="utf-8")))
    if not documents:
        fail("prepared preview render is empty")
    node_ports: list[tuple[str, object, object]] = []
    ingress_matches = 0
    for index, document in enumerate(documents):
        if not isinstance(document, dict):
            fail(f"prepared preview document {index} is not an object")
        metadata = _validate_object_metadata(
            document,
            expected_namespace,
            allow_trusted_ingress_annotation=(
                certificate_identity_mode == "standalone"
                and document.get("kind") == "Ingress"
                and isinstance(document.get("metadata"), dict)
                and document["metadata"].get("name") == "firemud-preview"
            ),
            allow_trusted_allocated_telnet_port=(
                document.get("kind") == "Service"
                and isinstance(document.get("metadata"), dict)
                and document["metadata"].get("name") == "tcp-proxy-service"
            ),
            certificate_identity_mode=certificate_identity_mode,
        )
        name = metadata.get("name")
        if metadata.get("namespace") != expected_namespace:
            fail(
                f"{document.get('kind')}/{name} must explicitly target namespace "
                f"{expected_namespace!r}"
            )
        annotations = metadata.get("annotations")
        if document.get("kind") == "Ingress" and name == "firemud-preview":
            ingress_matches += 1
            expected_annotations = (
                {"cert-manager.io/cluster-issuer": CANONICAL_INGRESS_ISSUER}
                if certificate_identity_mode == "standalone"
                else None
            )
            if annotations != expected_annotations:
                fail("prepared preview Ingress has an unsafe certificate issuer")
        elif (
            document.get("kind") == "Service"
            and name == "tcp-proxy-service"
        ):
            # The allocator annotation is trusted only after the runtime
            # injector adds it, and only for the public TCP Proxy Service.
            pass
        elif annotations is not None:
            fail(f"prepared {document.get('kind')}/{name} has untrusted annotations")
        if document.get("kind") in {"Deployment", "Job"}:
            _validate_workload_selector_metadata(document)
        for location, value in walk(document):
            if location.endswith(".nodePort"):
                node_ports.append((f"{document.get('kind')}/{name}", location, value))
    tcp_proxy_services = [
        document
        for document in documents
        if document.get("kind") == "Service"
        and document.get("metadata", {}).get("name") == "tcp-proxy-service"
    ]
    if len(tcp_proxy_services) != 1:
        fail(
            "prepared preview render must contain exactly one Service/tcp-proxy-service"
        )
    if certificate_identity_mode == "standalone" and ingress_matches != 1:
        fail("prepared preview render must contain exactly one preview Ingress")
    service_spec = _require_mapping(
        tcp_proxy_services[0].get("spec"),
        "Service/tcp-proxy-service.spec",
    )
    service_ports = _require_mapping_list(
        service_spec.get("ports"),
        "Service/tcp-proxy-service.spec.ports",
    )
    declared_ports = [
        (index, port)
        for index, port in enumerate(service_ports)
        if port.get("port") == 2323
    ]
    if len(declared_ports) != 1:
        fail(
            "Service/tcp-proxy-service must contain exactly one declared TCP port 2323"
        )
    service_type = service_spec.get("type", "ClusterIP")
    exposure_mode = _resolve_exposure_mode(
        certificate_identity_mode,
        exposure_mode,
        service_type,
    )
    if exposure_mode == "private" and expected_port != 0:
        fail("private preview runtime target requires sentinel Telnet port 0")
    if exposure_mode == "public" and not (
        MIN_PREVIEW_TELNET_PORT <= expected_port <= MAX_PREVIEW_TELNET_PORT
    ):
        fail(
            "preview telnet port must be between "
            f"{MIN_PREVIEW_TELNET_PORT} and {MAX_PREVIEW_TELNET_PORT}"
        )
    port_index, _declared_port = declared_ports[0]
    if exposure_mode == "private":
        if service_type != "ClusterIP":
            fail("private preview TCP Proxy Service must remain ClusterIP")
        if tcp_proxy_services[0].get("metadata", {}).get("annotations") is not None:
            fail("private preview TCP Proxy Service must not contain annotations")
        if node_ports:
            fail(
                "private preview render must not contain a NodePort; "
                f"observed {node_ports!r}"
            )
    else:
        expected_annotations = {
            ALLOCATED_TELNET_PORT_ANNOTATION: str(expected_port)
        }
        actual_annotations = tcp_proxy_services[0].get("metadata", {}).get(
            "annotations"
        )
        if actual_annotations != expected_annotations:
            fail(
                "public preview TCP Proxy Service must contain only the allocator "
                f"annotation bound to port {expected_port}; observed {actual_annotations!r}"
            )
        expected = (
            "Service/tcp-proxy-service",
            f"object.spec.ports[{port_index}].nodePort",
            expected_port,
        )
        if node_ports != [expected]:
            fail(
                "prepared preview render must contain only the exact allocated TCP Proxy "
                f"NodePort {expected_port}; observed {node_ports!r}"
            )


def determine_exposure_mode(
    path: Path, certificate_identity_mode: str
) -> str:
    """Derive the trusted public/private proof mode from the validated Service shape."""

    _validate_certificate_identity_mode(certificate_identity_mode)
    documents = list(yaml.safe_load_all(path.read_text(encoding="utf-8")))
    if not documents:
        fail("validated preview render is empty")
    validate_services(documents, certificate_identity_mode)
    services = [
        document
        for document in documents
        if document.get("kind") == "Service"
        and document.get("metadata", {}).get("name") == "tcp-proxy-service"
    ]
    if len(services) != 1:
        fail("validated preview render must contain exactly one TCP Proxy Service")
    service_spec = _require_mapping(
        services[0].get("spec"), "Service/tcp-proxy-service.spec"
    )
    return _exposure_mode_for_service_type(service_spec.get("type", "ClusterIP"))


def validate_service_consumers(
    documents: list[dict],
    expected_namespace: str,
    certificate_identity_mode: str = "hosted-controller",
    exposure_mode: str = "public",
) -> None:
    """Keep identity-managed TLS references limited to the chart consumers."""

    _validate_certificate_identity_mode(certificate_identity_mode)
    if exposure_mode not in EXPOSURE_MODES:
        fail("preview exposure mode is not canonical")
    deployments = {
        document.get("metadata", {}).get("name"): document
        for document in documents
        if document.get("kind") == "Deployment"
    }
    missing_deployments = sorted(SERVICE_IMAGES - deployments.keys())
    if missing_deployments:
        fail(
            "preview application Deployment set is incomplete; missing: "
            + ", ".join(f"Deployment/{name}" for name in missing_deployments)
        )
    for service in SERVICE_IMAGES:
        deployment = deployments[service]
        pod = deployment.get("spec", {}).get("template", {}).get("spec", {})
        if pod.get("serviceAccountName") != "firemud-app":
            fail(f"Deployment/{service} uses an unapproved ServiceAccount")
        containers = pod.get("containers") or []
        if len(containers) != 1 or containers[0].get("name") != service:
            fail(f"Deployment/{service} has an unexpected container layout")

        container = containers[0]
        expected_grpc_paths = (
            {
                "FIREMUD_GRPC_CERT_CHAIN_PATH": "/tls/tls.crt",
                "FIREMUD_GRPC_PRIVATE_KEY_PATH": "/tls/tls.key",
                "FIREMUD_GRPC_CA_CERT_PATH": "/tls/ca.crt",
            }
            if service in DISTINCT_GRPC_WORKLOADS
            else {
                "FIREMUD_GRPC_CERT_CHAIN_PATH": "/tls/client.crt",
                "FIREMUD_GRPC_PRIVATE_KEY_PATH": "/tls/client.key",
                "FIREMUD_GRPC_CA_CERT_PATH": "/tls/ca.crt",
            }
        )
        declared_grpc_paths = {
            entry.get("name"): entry.get("value")
            for entry in container.get("env", [])
            if isinstance(entry, dict)
        }
        if service in DISTINCT_GRPC_WORKLOADS:
            namespace_identity = next(
                (
                    entry
                    for entry in container.get("env", [])
                    if isinstance(entry, dict)
                    and entry.get("name") == "FIREMUD_GRPC_WORKLOAD_NAMESPACE"
                ),
                None,
            )
            if namespace_identity != {
                "name": "FIREMUD_GRPC_WORKLOAD_NAMESPACE",
                "valueFrom": {"fieldRef": {"fieldPath": "metadata.namespace"}},
            }:
                fail(
                    f"Deployment/{service} must derive FIREMUD_GRPC_WORKLOAD_NAMESPACE "
                    "from metadata.namespace"
                )
        for env_name, expected_path in expected_grpc_paths.items():
            if declared_grpc_paths.get(env_name) != expected_path:
                fail(
                    f"Deployment/{service} must configure {env_name} as {expected_path}"
                )

        grpc_secret_name = (
            f"firemud-grpc-{service}"
            if service in DISTINCT_GRPC_WORKLOADS
            else "firemud-grpc-tls"
        )
        expected_mounts = {
            "grpc-tls": ("/tls", grpc_secret_name),
            "jwt-signing-keys": ("/var/run/secrets/firemud/jwt", "jwt-signing-keys"),
        }
        if service == "account-service":
            expected_mounts["jwt-jwks"] = ("/var/run/secrets/firemud/jwks", "jwt-jwks")
        if service == "tcp-proxy-service":
            if exposure_mode == "public":
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
        if service == "spring-cloud-gateway":
            _validate_gateway_container_environment(container, expected_namespace)
        raw_mounts = _require_mapping_list(
            container.get("volumeMounts", []),
            f"Deployment/{service}.spec.template.spec.containers[0].volumeMounts",
        )
        raw_volumes = _require_mapping_list(
            pod.get("volumes", []), f"Deployment/{service}.spec.template.spec.volumes"
        )
        if len(raw_mounts) != len(expected_mounts) or len(raw_volumes) != len(expected_mounts):
            fail(f"Deployment/{service} has duplicate or unexpected identity consumers")
        mounts = {
            mount.get("name"): mount
            for mount in raw_mounts
        }
        volumes = {
            volume.get("name"): volume
            for volume in raw_volumes
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
                source = _require_mapping(
                    volume.get("configMap") or {},
                    f"Deployment/{service}.spec.template.spec.volumes[{volume_name}].configMap",
                )
                if source.get("name") != source_name:
                    fail(f"Deployment/{service} has an unexpected jwt-jwks source")
            else:
                source = _require_mapping(
                    volume.get("secret") or {},
                    f"Deployment/{service}.spec.template.spec.volumes[{volume_name}].secret",
                )
                if source.get("secretName") != source_name:
                    if (
                        service in DISTINCT_GRPC_WORKLOADS
                        and volume_name == "grpc-tls"
                        and source.get("secretName") == "firemud-grpc-tls"
                    ):
                        fail(
                            f"Deployment/{service} distinct workload falls back to "
                            "shared firemud-grpc-tls"
                        )
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


def validate_services(
    documents: list[dict], certificate_identity_mode: str = "standalone"
) -> None:
    """Require the exact trusted preview Service specs."""

    _validate_certificate_identity_mode(certificate_identity_mode)
    for document in documents:
        if document.get("kind") != "Service":
            continue
        name = document.get("metadata", {}).get("name")
        spec = _require_mapping(document.get("spec"), f"Service/{name}.spec")
        expected_spec = EXPECTED_SERVICE_SPECS.get(name)
        if expected_spec is None:
            fail(f"Service/{name} is not an approved preview Service")
        if name == "tcp-proxy-service":
            service_type = spec.get("type")
            if service_type not in {"ClusterIP", "NodePort"}:
                fail(f"Service/{name} has an unsafe service type")
            expected_spec = {**expected_spec, "type": service_type}
        else:
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


def _validate_gateway_egress_policy(policy: dict) -> None:
    if policy.get("spec") != EXPECTED_GATEWAY_NETWORK_POLICY_SPEC:
        fail("NetworkPolicy/spring-cloud-gateway-egress has an unsafe spec")


def validate_network_policies(
    documents: list[dict],
    certificate_identity_mode: str = "hosted-controller",
) -> None:
    """Keep the runtime policy set and every allowed traffic exception exact."""

    _validate_certificate_identity_mode(certificate_identity_mode)
    raw_policies = [
        document for document in documents if document.get("kind") == "NetworkPolicy"
    ]
    policies = {
        document.get("metadata", {}).get("name"): document
        for document in raw_policies
    }
    expected_names = _expected_names_for_mode(certificate_identity_mode)[
        "NetworkPolicy"
    ]
    if len(raw_policies) != len(policies) or set(policies) != expected_names:
        fail(
            "runtime NetworkPolicy set is not closed "
            f"(missing={sorted(expected_names - set(policies))}, "
            f"extra={sorted(set(policies) - expected_names)})"
    )
    _validate_internal_network_policies(policies)

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
    if certificate_identity_mode == "hosted-controller":
        controller_policy = policies["account-service-controller-ingress"]
        spec = _require_mapping(
            controller_policy.get("spec"),
            "NetworkPolicy/account-service-controller-ingress.spec",
        )
        if spec.get("podSelector") != {"matchLabels": {"app": "account-service"}}:
            fail(
                "NetworkPolicy/account-service-controller-ingress selects an unsafe workload"
            )
        if spec.get("policyTypes") != ["Ingress"]:
            fail(
                "NetworkPolicy/account-service-controller-ingress must only govern ingress"
            )
        expected_ingress = [
            {"from": [expected_from], "ports": [{"protocol": "TCP", "port": 6565}]}
        ]
        if spec.get("ingress") != expected_ingress:
            fail(
                "NetworkPolicy/account-service-controller-ingress has an unsafe exception"
            )

    gateway_ingress = _require_mapping(
        policies["spring-cloud-gateway-ingress"].get("spec"),
        "NetworkPolicy/spring-cloud-gateway-ingress.spec",
    )
    expected_gateway_ingress_rules = [
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
    ]
    if certificate_identity_mode == "hosted-controller":
        expected_gateway_ingress_rules.append(
            {
                "from": [expected_from],
                "ports": [{"protocol": "TCP", "port": 8443}],
            }
        )
    expected_gateway_ingress = {
        "podSelector": {"matchLabels": {"app": "spring-cloud-gateway"}},
        "policyTypes": ["Ingress"],
        "ingress": expected_gateway_ingress_rules,
    }
    if gateway_ingress != expected_gateway_ingress:
        fail("NetworkPolicy/spring-cloud-gateway-ingress has an unsafe exception")

    gateway_egress = _require_mapping(
        policies["spring-cloud-gateway-egress"].get("spec"),
        "NetworkPolicy/spring-cloud-gateway-egress.spec",
    )
    _validate_gateway_egress_policy({"spec": gateway_egress})

    proxy_egress = _require_mapping(
        policies["tcp-proxy-service-egress"].get("spec"),
        "NetworkPolicy/tcp-proxy-service-egress.spec",
    )
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
            {
                "to": [{"podSelector": {"matchLabels": {"app": "otel-collector"}}}],
                "ports": [{"protocol": "TCP", "port": 4317}],
            },
            {
                "to": [{"podSelector": {"matchLabels": {"app": "elasticsearch"}}}],
                "ports": [{"protocol": "TCP", "port": 9200}],
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
    certificate_identity_mode: str = "hosted-controller",
) -> None:
    _validate_certificate_identity_mode(certificate_identity_mode)
    expected_names = _expected_names_for_mode(certificate_identity_mode)
    expected_objects = {
        (kind, name)
        for kind, names in expected_names.items()
        for name in names
    }
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
        metadata = _validate_object_metadata(
            document,
            expected_namespace,
            certificate_identity_mode=certificate_identity_mode,
        )
        name = metadata.get("name")
        if not isinstance(name, str) or not NAME_RE.fullmatch(name):
            fail(f"manifest object has unsafe name: {name!r}")
        if name not in expected_names[document["kind"]]:
            fail(f"manifest contains unexpected {document['kind']}/{name}")
        _validate_firemud_config_shape(document)
        if document["kind"] in {"Deployment", "Job"}:
            spec = _require_mapping(document.get("spec"), f"{document['kind']}/{name}.spec")
            template = _require_mapping(
                spec.get("template"), f"{document['kind']}/{name}.spec.template"
            )
            pod = _require_mapping(
                template.get("spec"),
                f"{document['kind']}/{name}.spec.template.spec",
            )
            _validate_restricted_pod_security(
                pod,
                f"{document['kind']}/{name}.spec.template.spec",
            )
        if document["kind"] == "Ingress":
            validate_ingress(document, expected_namespace, expected_hostname)
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
        if document["kind"] == "PersistentVolumeClaim":
            _validate_persistent_volume_claim(document)
    if seen != expected_objects:
        missing = sorted(expected_objects - seen)
        extra = sorted(seen - expected_objects)
        fail(f"manifest object set is not closed (missing={missing}, extra={extra})")
    validate_services(documents, certificate_identity_mode)
    validate_network_policies(documents, certificate_identity_mode)
    validate_infrastructure_deployments(documents)
    if ("Service", "tcp-proxy-service") in expected_objects:
        tcp_proxy_service = next(
            document
            for document in documents
            if document["kind"] == "Service"
            and document["metadata"]["name"] == "tcp-proxy-service"
        )
        exposure_mode = _exposure_mode_for_service_type(
            tcp_proxy_service["spec"].get("type", "ClusterIP")
        )
        validate_service_consumers(
            documents, expected_namespace, certificate_identity_mode, exposure_mode
        )


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
    certificate_identity_mode: str = "hosted-controller",
) -> None:
    _validate_certificate_identity_mode(certificate_identity_mode)
    metadata = _require_mapping(
        json.loads(path.read_text(encoding="utf-8")), "metadata"
    )
    if not isinstance(pr_number, str) or re.fullmatch(r"[1-9][0-9]*", pr_number) is None:
        fail("PR number must be a positive canonical decimal string")
    normalized_pr_number = int(pr_number)
    event = metadata.get("event")
    if event not in {"pull_request_target", "repository_dispatch"}:
        fail("metadata event must be pull_request_target or repository_dispatch")
    if event == "repository_dispatch" and metadata.get("action") != "deploy":
        fail("repository_dispatch render metadata action must be deploy")
    expected = {
        "schemaVersion": 1,
        "event": event,
        "repository": repository,
        "sourceWorkflow": ".github/workflows/preview.yml",
        "sourceRunId": int(source_run_id),
        "prNumber": normalized_pr_number,
        "baseSha": base_sha,
        "headSha": head_sha,
        "mergeSha": merge_sha,
        "hostname": hostname,
        "imageTag": image_tag,
    }
    if event == "repository_dispatch":
        expected["action"] = "deploy"
    allowed_fields = set(expected) | {"manifestSha256"}
    unexpected_fields = set(metadata) - allowed_fields
    if unexpected_fields:
        fail(
            "metadata contains unsupported fields: "
            f"{sorted(unexpected_fields)}"
        )
    for key, expected_value in expected.items():
        if metadata.get(key) != expected_value:
            fail(f"metadata {key} does not match trusted event data")
    digest = hashlib.sha256(manifest.read_bytes()).hexdigest()
    if metadata.get("manifestSha256") != digest:
        fail("manifest checksum does not match metadata")
    validate_manifest(
        manifest,
        f"pr-{normalized_pr_number}",
        image_tag,
        hostname,
        certificate_identity_mode,
    )


def main(argv: list[str] | None = None) -> int:
    args = sys.argv if argv is None else argv
    command = args[1] if len(args) > 1 else None
    if command == "sanitize" and len(args) == 4:
        try:
            sanitize(Path(args[2]), Path(args[3]))
        except (OSError, ValueError, KeyError, TypeError, yaml.YAMLError) as exc:
            print(f"preview artifact rejected: {exc}", file=sys.stderr)
            return 1
        return 0
    if command == "inject" and len(args) == 8:
        try:
            inject_telnet_port(
                source=Path(args[2]),
                destination=Path(args[3]),
                port=int(args[5]),
                expected_namespace=args[4],
                certificate_identity_mode=args[6],
                exposure_mode=args[7],
            )
        except (OSError, ValueError, KeyError, TypeError, yaml.YAMLError) as exc:
            print(f"preview Telnet port injection rejected: {exc}", file=sys.stderr)
            return 1
        return 0
    if command == "runtime-target" and len(args) == 7:
        try:
            validate_runtime_target(
                Path(args[2]),
                args[3],
                int(args[4]),
                args[5],
                args[6],
            )
        except (OSError, ValueError, KeyError, TypeError, yaml.YAMLError) as exc:
            print(f"preview runtime target rejected: {exc}", file=sys.stderr)
            return 1
        return 0
    if command == "exposure-mode" and len(args) == 4:
        try:
            print(determine_exposure_mode(Path(args[2]), args[3]))
        except (OSError, ValueError, KeyError, TypeError, yaml.YAMLError) as exc:
            print(f"preview exposure mode rejected: {exc}", file=sys.stderr)
            return 1
        return 0
    if len(args) != 12:
        print(
            "usage: validate-preview-artifact.py <metadata> <manifest> <repository> "
            "<source-run-id> <pr-number> <base-sha> <head-sha> <merge-sha> "
            "<image-tag> <hostname> <standalone|hosted-controller>\n"
            "       validate-preview-artifact.py sanitize <render> <output>\n"
            "       validate-preview-artifact.py inject <render> <output> <namespace> <port> <standalone|hosted-controller> <private|public>\n"
            "       validate-preview-artifact.py runtime-target <render> <namespace> <port> <standalone|hosted-controller> <private|public>\n"
            "       validate-preview-artifact.py exposure-mode <render> <standalone|hosted-controller>",
            file=sys.stderr,
        )
        return 2
    try:
        validate_metadata(
            Path(args[1]),
            Path(args[2]),
            *args[3:],
        )
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError, yaml.YAMLError) as exc:
        print(f"preview artifact rejected: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
