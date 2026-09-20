#!/usr/bin/env python3
"""Regression tests for trusted preview artifact Secret-reference validation."""

from __future__ import annotations

import copy
import hashlib
import importlib.util
import io
import json
import re
import tempfile
import unittest
from contextlib import redirect_stderr
from pathlib import Path
from unittest.mock import patch

import yaml

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "dev-tools/hosted/preview/validate-preview-artifact.py"


def load_validator():
    spec = importlib.util.spec_from_file_location("preview_artifact_validator", SCRIPT)
    if spec is None or spec.loader is None:
        raise AssertionError("could not load preview artifact validator")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


VALIDATOR = load_validator()
TRUSTED_CHART_METADATA = ROOT / "k8s/helm/firemud/Chart.yaml"
TRUSTED_CHART = yaml.safe_load(
    TRUSTED_CHART_METADATA.read_text(encoding="utf-8")
)
EXPECTED_HELM_CHART_LABEL = (
    f"{TRUSTED_CHART['name']}-{TRUSTED_CHART['version']}".replace("+", "_")
)


class PreviewArtifactServiceValidationTest(unittest.TestCase):
    validator = VALIDATOR

    def _tcp_proxy_service(
        self, *, service_type="ClusterIP", port=2323, target_port=2323
    ):
        expected_spec = copy.deepcopy(
            self.validator.EXPECTED_SERVICE_SPECS["tcp-proxy-service"]
        )
        expected_spec["type"] = service_type
        expected_spec["ports"][0]["port"] = port
        expected_spec["ports"][0]["targetPort"] = target_port
        return {
            "apiVersion": "v1",
            "kind": "Service",
            "metadata": {"name": "tcp-proxy-service"},
            "spec": expected_spec,
        }

    def test_hosted_controller_accepts_private_tcp_proxy_service(self):
        self.validator.validate_services(
            [self._tcp_proxy_service()], "hosted-controller"
        )

    def test_hosted_controller_accepts_public_tcp_proxy_service(self):
        self.validator.validate_services(
            [self._tcp_proxy_service(service_type="NodePort")],
            "hosted-controller",
        )

    def test_hosted_controller_rejects_wrong_tcp_proxy_port(self):
        with self.assertRaisesRegex(
            ValueError, "Service/tcp-proxy-service has an unexpected port set"
        ):
            self.validator.validate_services(
                [self._tcp_proxy_service(port=2324)], "hosted-controller"
            )

    def _tcp_proxy_deployment(self, *, include_telnet_tls):
        mounts = [
            {"name": "grpc-tls", "mountPath": "/tls", "readOnly": True},
            {
                "name": "jwt-signing-keys",
                "mountPath": "/var/run/secrets/firemud/jwt",
                "readOnly": True,
            },
        ]
        volumes = [
            {"name": "grpc-tls", "secret": {"secretName": "firemud-grpc-tls"}},
            {
                "name": "jwt-signing-keys",
                "secret": {"secretName": "jwt-signing-keys"},
            },
        ]
        if include_telnet_tls:
            mounts.append(
                {
                    "name": "telnet-tls",
                    "mountPath": "/telnet-tls",
                    "readOnly": True,
                }
            )
            volumes.append(
                {
                    "name": "telnet-tls",
                    "secret": {"secretName": "pr-42-telnet-tls"},
                }
            )
        mounts.append(
            {
                "name": "gateway-ws-client-tls",
                "mountPath": "/gateway-ws-client-tls",
                "readOnly": True,
            }
        )
        volumes.append(
            {
                "name": "gateway-ws-client-tls",
                "secret": {
                    "secretName": "pr-42-tcp-proxy-bridge",
                    "items": [
                        {"key": "tls.crt", "path": "tls.crt"},
                        {"key": "tls.key", "path": "tls.key"},
                        {"key": "ca.crt", "path": "ca.crt"},
                    ],
                },
            }
        )
        return {
            "kind": "Deployment",
            "metadata": {"name": "tcp-proxy-service"},
            "spec": {
                "template": {
                    "spec": {
                        "serviceAccountName": "firemud-app",
                        "containers": [
                            {
                                "name": "tcp-proxy-service",
                                "volumeMounts": mounts,
                            }
                        ],
                        "volumes": volumes,
                    }
                }
            },
        }

    def test_hosted_controller_accepts_private_tcp_proxy_consumers(self):
        with patch.object(self.validator, "SERVICE_IMAGES", {"tcp-proxy-service"}):
            self.validator.validate_service_consumers(
                [self._tcp_proxy_deployment(include_telnet_tls=False)],
                "pr-42",
                "hosted-controller",
                "private",
            )

    def test_hosted_controller_public_requires_telnet_consumer(self):
        with patch.object(self.validator, "SERVICE_IMAGES", {"tcp-proxy-service"}):
            self.validator.validate_service_consumers(
                [self._tcp_proxy_deployment(include_telnet_tls=True)],
                "pr-42",
                "hosted-controller",
                "public",
            )
        with (
            patch.object(self.validator, "SERVICE_IMAGES", {"tcp-proxy-service"}),
            self.assertRaisesRegex(
                ValueError,
                "Deployment/tcp-proxy-service has duplicate or unexpected identity consumers",
            ),
        ):
            self.validator.validate_service_consumers(
                [self._tcp_proxy_deployment(include_telnet_tls=False)],
                "pr-42",
                "hosted-controller",
                "public",
            )

    def test_private_rejects_public_telnet_consumer(self):
        with (
            patch.object(self.validator, "SERVICE_IMAGES", {"tcp-proxy-service"}),
            self.assertRaisesRegex(
                ValueError,
                "Deployment/tcp-proxy-service has duplicate or unexpected identity consumers",
            ),
        ):
            self.validator.validate_service_consumers(
                [self._tcp_proxy_deployment(include_telnet_tls=True)],
                "pr-42",
                "hosted-controller",
                "private",
            )

    def test_standalone_requires_public_telnet_consumer(self):
        with patch.object(self.validator, "SERVICE_IMAGES", {"tcp-proxy-service"}):
            self.validator.validate_service_consumers(
                [self._tcp_proxy_deployment(include_telnet_tls=True)],
                "pr-42",
                "standalone",
                "public",
            )

    def test_standalone_public_requires_telnet_consumer(self):
        with (
            patch.object(self.validator, "SERVICE_IMAGES", {"tcp-proxy-service"}),
            self.assertRaisesRegex(
                ValueError,
                "Deployment/tcp-proxy-service has duplicate or unexpected identity consumers",
            ),
        ):
            self.validator.validate_service_consumers(
                [self._tcp_proxy_deployment(include_telnet_tls=False)],
                "pr-42",
                "standalone",
                "public",
            )


class PreviewArtifactSecretReferenceTest(unittest.TestCase):
    validator = VALIDATOR

    def test_expected_chart_label_comes_from_trusted_metadata(self):
        trusted_metadata = yaml.safe_load(
            self.validator.TRUSTED_CHART_METADATA.read_text(encoding="utf-8")
        )
        expected_label = (
            f"{trusted_metadata['name']}-{trusted_metadata['version']}".replace("+", "_")
        )
        self.assertEqual(
            expected_label,
            self.validator._expected_top_level_labels()["helm.sh/chart"],
        )
        with tempfile.TemporaryDirectory() as temporary_directory:
            chart_metadata = Path(temporary_directory) / "Chart.yaml"
            chart_metadata.write_text(
                "apiVersion: v2\nname: example\nversion: 1.2.3+build.4\n",
                encoding="utf-8",
            )

            self.assertEqual(
                "example-1.2.3_build.4",
                self.validator._expected_chart_label(chart_metadata),
            )

    def test_expected_chart_label_rejects_malformed_metadata(self):
        malformed_documents = (
            "name: [\n",
            "- not-a-mapping\n",
            "name: ''\nversion: 1.2.3\n",
            "name: firemud\nversion: 1\n",
            "name: firemud\n",
            "name: firemud\nversion: [1, 2, 3]\n",
        )
        with tempfile.TemporaryDirectory() as temporary_directory:
            chart_metadata = Path(temporary_directory) / "Chart.yaml"
            for document in malformed_documents:
                with self.subTest(document=document):
                    chart_metadata.write_text(document, encoding="utf-8")
                    with self.assertRaisesRegex(
                        (TypeError, ValueError), "trusted chart metadata"
                    ):
                        self.validator._expected_chart_label(chart_metadata)

    def test_sanitized_walk_rejects_projected_and_csi_secret_references(self):
        cases = (
            (
                {
                    "projected": {
                        "sources": [{"secret": {"name": "untrusted-secret"}}]
                    }
                },
                "object.spec.template.spec.volumes[0].projected.sources[0].secret.name",
            ),
            (
                {
                    "csi": {
                        "nodePublishSecretRef": {"name": "untrusted-secret"}
                    }
                },
                "object.spec.template.spec.volumes[0].csi.nodePublishSecretRef.name",
            ),
        )
        for volume, location in cases:
            with self.subTest(location=location), self.assertRaisesRegex(
                ValueError,
                re.escape(f"{location} contains an unapproved Secret reference"),
            ):
                self.validator._validate_sanitized_secret_refs(
                    {"spec": {"template": {"spec": {"volumes": [volume]}}}}
                )

    def test_sanitized_walk_accepts_projected_and_csi_identity_references(self):
        for volume in (
            {
                "projected": {
                    "sources": [
                        {"secret": {"name": "pr-42-gateway-internal-ws"}}
                    ]
                }
            },
            {
                "csi": {
                    "nodePublishSecretRef": {"name": "pr-42-tcp-proxy-bridge"}
                }
            },
        ):
            self.validator._validate_sanitized_secret_refs(
                {"spec": {"template": {"spec": {"volumes": [volume]}}}}
            )

    def test_sanitized_walk_rejects_non_preview_namespace_identity_suffixes(self):
        cases = (
            {"secretName": "kube-system-tls"},
            {"secretRef": {"name": "other-gateway-internal-ws"}},
            {"secretKeyRef": {"name": "pr-0-tcp-proxy-bridge"}},
            {
                "projected": {
                    "sources": [{"secret": {"name": "kube-system-telnet-tls"}}]
                }
            },
            {
                "csi": {
                    "nodePublishSecretRef": {"name": "kube-system-tls"}
                }
            },
        )
        for value in cases:
            with self.subTest(value=value), self.assertRaisesRegex(
                ValueError, "contains an unapproved Secret reference"
            ):
                self.validator._validate_sanitized_secret_refs(value)

    def test_sanitized_walk_ignores_unrelated_secret_like_dictionaries(self):
        self.validator._validate_sanitized_secret_refs(
            {
                "spec": {
                    "unrelated": {
                        "secret": {"name": "untrusted-secret"},
                        "nodePublishSecretRef": {"name": "untrusted-secret"},
                    }
                }
            }
        )

    def _manifest_fixture(self, volume):
        return {
            "apiVersion": "apps/v1",
            "kind": "Deployment",
            "metadata": {
                "name": "account-service",
                "namespace": "pr-42",
                "labels": {
                    **self.validator._expected_top_level_labels(),
                    "app.kubernetes.io/instance": "pr-42",
                },
            },
            "spec": {
                "selector": {"matchLabels": {"app": "account-service"}},
                "template": {
                    "metadata": {"labels": {"app": "account-service"}},
                    "spec": {
                        "securityContext": {
                            "runAsNonRoot": True,
                            "runAsUser": 1000,
                            "runAsGroup": 1000,
                            "fsGroup": 1000,
                            "seccompProfile": {"type": "RuntimeDefault"},
                        },
                        "containers": [
                            {
                                "name": "account-service",
                                "image": "ghcr.io/benhook1013/account-service:pr-42-head-42",
                                "securityContext": {
                                    "allowPrivilegeEscalation": False,
                                    "capabilities": {"drop": ["ALL"]},
                                },
                            }
                        ],
                        "volumes": [volume],
                    },
                },
            },
        }

    def _validate_manifest(self, document):
        validator = self.validator
        expected_names = {
            kind: set() for kind in validator.EXPECTED_NAMES
        }
        expected_names["Deployment"] = {"account-service"}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.yaml"
            path.write_text(yaml.safe_dump(document), encoding="utf-8")
            with (
                patch.object(validator, "EXPECTED_NAMES", expected_names),
                patch.object(
                    validator, "EXPECTED_OBJECTS", {("Deployment", "account-service")}
                ),
                patch.object(validator, "validate_services"),
                patch.object(validator, "validate_network_policies"),
                patch.object(validator, "validate_infrastructure_deployments"),
                patch.object(validator, "validate_service_consumers"),
            ):
                validator.validate_manifest(
                    path,
                    "pr-42",
                    "pr-42-head-42",
                    "pr-42.preview.example.test",
                )

    def test_manifest_validates_object_metadata_once_before_kind_specific_checks(self):
        validator = self.validator
        document = self._manifest_fixture({"emptyDir": {}})
        validation_order = []
        original_metadata_validator = validator._validate_object_metadata

        def record_metadata(document, expected_namespace, **kwargs):
            validation_order.append("metadata")
            return original_metadata_validator(
                document, expected_namespace, **kwargs
            )

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.yaml"
            path.write_text(yaml.safe_dump(document), encoding="utf-8")
            with (
                patch.object(
                    validator,
                    "EXPECTED_NAMES",
                    {kind: {"account-service"} if kind == "Deployment" else set() for kind in validator.EXPECTED_NAMES},
                ),
                patch.object(
                    validator, "EXPECTED_OBJECTS", {("Deployment", "account-service")}
                ),
                patch.object(validator, "validate_services"),
                patch.object(validator, "validate_network_policies"),
                patch.object(validator, "validate_infrastructure_deployments"),
                patch.object(validator, "validate_service_consumers"),
                patch.object(
                    validator,
                    "_validate_object_metadata",
                    side_effect=record_metadata,
                ),
                patch.object(
                    validator,
                    "_validate_firemud_config_shape",
                    side_effect=lambda _document: validation_order.append("config"),
                ),
                patch.object(
                    validator,
                    "_validate_restricted_pod_security",
                    side_effect=lambda _pod, _path: validation_order.append("security"),
                ),
                patch.object(
                    validator,
                    "_validate_workload_selector_metadata",
                    side_effect=lambda _document: validation_order.append("selector"),
                ),
            ):
                validator.validate_manifest(
                    path,
                    "pr-42",
                    "pr-42-head-42",
                    "pr-42.preview.example.test",
                )

        self.assertEqual(
            validation_order,
            ["metadata", "config", "security", "selector"],
        )

    def test_manifest_walk_rejects_projected_and_csi_secret_references(self):
        cases = (
            (
                {
                    "projected": {
                        "sources": [{"secret": {"name": "untrusted-secret"}}]
                    }
                },
                "object.spec.template.spec.volumes[0].projected.sources[0].secret.name",
            ),
            (
                {
                    "csi": {
                        "nodePublishSecretRef": {"name": "untrusted-secret"}
                    }
                },
                "object.spec.template.spec.volumes[0].csi.nodePublishSecretRef.name",
            ),
        )
        for volume, location in cases:
            with self.subTest(location=location), self.assertRaisesRegex(
                ValueError,
                re.escape(f"{location} contains an unapproved Secret reference"),
            ):
                self._validate_manifest(self._manifest_fixture(volume))

    def test_manifest_walk_accepts_projected_and_csi_identity_references(self):
        for volume in (
            {
                "projected": {
                    "sources": [
                        {"secret": {"name": "pr-42-gateway-internal-ws"}}
                    ]
                }
            },
            {
                "csi": {
                    "nodePublishSecretRef": {"name": "pr-42-tcp-proxy-bridge"}
                }
            },
        ):
            self._validate_manifest(self._manifest_fixture(copy.deepcopy(volume)))

    def test_manifest_walk_rejects_cross_namespace_identity_references(self):
        cases = (
            {
                "projected": {
                    "sources": [
                        {"secret": {"name": "pr-41-gateway-internal-ws"}}
                    ]
                }
            },
            {
                "csi": {
                    "nodePublishSecretRef": {"name": "pr-41-tcp-proxy-bridge"}
                }
            },
        )
        for volume in cases:
            with self.subTest(volume=volume), self.assertRaisesRegex(
                ValueError,
                "contains an unapproved Secret reference",
            ):
                self._validate_manifest(self._manifest_fixture(volume))

    def test_manifest_walk_ignores_unrelated_secret_like_dictionaries(self):
        document = self._manifest_fixture({"emptyDir": {}})
        document["spec"]["unrelated"] = {
            "secret": {"name": "untrusted-secret"},
            "nodePublishSecretRef": {"name": "untrusted-secret"},
        }
        self._validate_manifest(document)

    def test_manifest_rejects_pod_security_context_sysctls(self):
        document = self._manifest_fixture({"emptyDir": {}})
        document["spec"]["template"]["spec"]["securityContext"]["sysctls"] = [
            {"name": "net.ipv4.ip_unprivileged_port_start", "value": "0"}
        ]
        with self.assertRaisesRegex(
            ValueError,
            re.escape(
                "Deployment/account-service.spec.template.spec.securityContext.sysctls "
                "are not allowed in the preview runtime"
            ),
        ):
            self._validate_manifest(document)

    def test_manifest_rejects_scalar_capability_drop(self):
        document = self._manifest_fixture({"emptyDir": {}})
        document["spec"]["template"]["spec"]["containers"][0]["securityContext"][
            "capabilities"
        ]["drop"] = "NOTALL"
        with self.assertRaisesRegex(
            ValueError,
            re.escape(
                "Deployment/account-service.spec.template.spec.containers[0]."
                "securityContext.capabilities must drop ALL"
            ),
        ):
            self._validate_manifest(document)

    def test_manifest_rejects_non_mapping_container_mounts_and_ports(self):
        for field, malformed in (
            ("volumeMounts", {}),
            ("volumeMounts", ["not-a-mapping"]),
            ("ports", {}),
            ("ports", ["not-a-mapping"]),
        ):
            with self.subTest(field=field, malformed=malformed):
                document = self._manifest_fixture({"emptyDir": {}})
                document["spec"]["template"]["spec"]["containers"][0][field] = malformed
                with self.assertRaisesRegex(
                    ValueError,
                    re.escape(
                        "Deployment/account-service.spec.template.spec.containers[0]."
                        f"{field} is not a list of objects"
                    ),
                ):
                    self._validate_manifest(document)

    def test_consumer_validation_rejects_non_mapping_mounts_and_volumes(self):
        pod = {
            "serviceAccountName": "firemud-app",
            "containers": [
                {
                    "name": "account-service",
                    "volumeMounts": [
                        {"name": "grpc-tls", "mountPath": "/tls", "readOnly": True},
                        {
                            "name": "jwt-signing-keys",
                            "mountPath": "/var/run/secrets/firemud/jwt",
                            "readOnly": True,
                        },
                    ],
                }
            ],
            "volumes": [
                {"name": "grpc-tls", "secret": {"secretName": "firemud-grpc-tls"}},
                {
                    "name": "jwt-signing-keys",
                    "secret": {"secretName": "jwt-signing-keys"},
                },
            ],
        }
        document = {
            "kind": "Deployment",
            "metadata": {"name": "account-service"},
            "spec": {"template": {"spec": pod}},
        }
        for field, malformed in (
            ("volumeMounts", ["not-a-mapping"]),
            ("volumes", ["not-a-mapping"]),
        ):
            with self.subTest(field=field):
                invalid = copy.deepcopy(document)
                if field == "volumeMounts":
                    invalid["spec"]["template"]["spec"]["containers"][0][field] = malformed
                else:
                    invalid["spec"]["template"]["spec"][field] = malformed
                with (
                    patch.object(self.validator, "SERVICE_IMAGES", {"account-service"}),
                    self.assertRaisesRegex(ValueError, f"{field} is not a list of objects"),
                ):
                    self.validator.validate_service_consumers(
                        [invalid], "pr-42", "standalone", "public"
                    )


class PreviewArtifactPersistentVolumeClaimTest(unittest.TestCase):
    validator = VALIDATOR

    def _document(self, name, spec):
        return {
            "apiVersion": "v1",
            "kind": "PersistentVolumeClaim",
            "metadata": {
                "name": name,
                "namespace": "pr-42",
                "labels": {
                    **self.validator._expected_top_level_labels(),
                    "app.kubernetes.io/instance": "pr-42",
                },
            },
            "spec": spec,
        }

    def _validate(self, document):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.yaml"
            path.write_text(yaml.safe_dump(document), encoding="utf-8")
            with (
                patch.object(
                    self.validator,
                    "EXPECTED_NAMES",
                    {"PersistentVolumeClaim": {document["metadata"]["name"]}},
                ),
                patch.object(
                    self.validator,
                    "EXPECTED_OBJECTS",
                    {("PersistentVolumeClaim", document["metadata"]["name"])},
                ),
                patch.object(self.validator, "validate_services"),
                patch.object(self.validator, "validate_network_policies"),
                patch.object(self.validator, "validate_infrastructure_deployments"),
                patch.object(self.validator, "validate_service_consumers"),
            ):
                self.validator.validate_manifest(
                    path,
                    "pr-42",
                    "pr-42-head-42",
                    "pr-42.preview.example.test",
                )

    def test_manifest_accepts_pinned_complete_pvc_specs(self):
        for name, spec in self.validator.EXPECTED_PVC_SPECS.items():
            with self.subTest(name=name):
                self._validate(self._document(name, copy.deepcopy(spec)))

    def test_manifest_rejects_incomplete_or_changed_pvc_specs(self):
        expected = self.validator.EXPECTED_PVC_SPECS["postgres-data"]
        cases = []
        for missing_field in ("accessModes", "storageClassName", "resources"):
            spec = copy.deepcopy(expected)
            spec.pop(missing_field)
            cases.append((missing_field, spec))
        spec = copy.deepcopy(expected)
        spec["resources"]["requests"].pop("storage")
        cases.append(("resources.requests.storage", spec))
        spec = copy.deepcopy(expected)
        spec["accessModes"] = ["ReadWriteMany"]
        cases.append(("accessModes value", spec))

        for case, spec in cases:
            with self.subTest(case=case), self.assertRaisesRegex(
                ValueError,
                re.escape("PersistentVolumeClaim/postgres-data has an unsafe spec"),
            ):
                self._validate(self._document("postgres-data", spec))


class PreviewArtifactInfrastructureDeploymentTest(unittest.TestCase):
    validator = VALIDATOR

    @staticmethod
    def _document(name, spec):
        return {
            "apiVersion": "apps/v1",
            "kind": "Deployment",
            "metadata": {"name": name},
            "spec": copy.deepcopy(spec),
        }

    def _all_documents(self, specs=None):
        if specs is None:
            specs = self.validator.EXPECTED_INFRASTRUCTURE_DEPLOYMENT_SPECS
        return [
            self._document(name, spec) for name, spec in specs.items()
        ]

    def test_accepts_exact_postgres_layout_guard_and_infrastructure_specs(self):
        self.validator.validate_infrastructure_deployments(self._all_documents())

    def test_rejects_missing_postgres_layout_guard(self):
        specs = copy.deepcopy(self.validator.EXPECTED_INFRASTRUCTURE_DEPLOYMENT_SPECS)
        specs["postgres"]["template"]["spec"].pop("initContainers")
        with self.assertRaisesRegex(
            ValueError,
            re.escape("Deployment/postgres has an unsafe infrastructure spec"),
        ):
            self.validator.validate_infrastructure_deployments(self._all_documents(specs))

    def test_rejects_modified_postgres_layout_guard(self):
        for case, mutate in (
            (
                "script",
                lambda container: container["args"].__setitem__(
                    0, container["args"][0] + "echo changed\n"
                ),
            ),
            ("image", lambda container: container.__setitem__("image", "postgres:17")),
            (
                "mount",
                lambda container: container["volumeMounts"][0].__setitem__(
                    "readOnly", False
                ),
            ),
            (
                "security",
                lambda container: container["securityContext"].__setitem__(
                    "readOnlyRootFilesystem", False
                ),
            ),
        ):
            with self.subTest(case=case):
                specs = copy.deepcopy(
                    self.validator.EXPECTED_INFRASTRUCTURE_DEPLOYMENT_SPECS
                )
                mutate(specs["postgres"]["template"]["spec"]["initContainers"][0])
                with self.assertRaisesRegex(
                    ValueError,
                    re.escape("Deployment/postgres has an unsafe infrastructure spec"),
                ):
                    self.validator.validate_infrastructure_deployments(
                        self._all_documents(specs)
                    )


class PreviewArtifactMetadataTest(unittest.TestCase):
    validator = VALIDATOR

    @staticmethod
    def _metadata_fixture(manifest_path):
        return {
            "schemaVersion": 1,
            "event": "pull_request_target",
            "repository": "example/FireMUD",
            "sourceWorkflow": ".github/workflows/preview.yml",
            "sourceRunId": 42,
            "prNumber": 42,
            "baseSha": "base-42",
            "headSha": "head-42",
            "mergeSha": "merge-42",
            "hostname": "pr-42.preview.example.test",
            "imageTag": "pr-42-head-42",
            "manifestSha256": hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
        }

    def test_non_object_metadata_is_rejected_cleanly(self):
        for metadata in ([], "metadata"):
            with self.subTest(metadata=metadata), tempfile.TemporaryDirectory() as directory:
                metadata_path = Path(directory) / "metadata.json"
                manifest_path = Path(directory) / "manifest.yaml"
                metadata_path.write_text(json.dumps(metadata), encoding="utf-8")
                stderr = io.StringIO()
                argv = [
                    str(SCRIPT),
                    str(metadata_path),
                    str(manifest_path),
                    "example/FireMUD",
                    "1",
                    "42",
                    "a" * 40,
                    "b" * 40,
                    "c" * 40,
                    "pr-42-head-42",
                    "pr-42.preview.example.test",
                    "hosted-controller",
                ]

                with redirect_stderr(stderr):
                    self.assertEqual(self.validator.main(argv), 1)

                self.assertEqual(
                    stderr.getvalue(),
                    "preview artifact rejected: metadata is not an object\n",
                )

    def test_unexpected_top_level_metadata_field_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest_path = Path(directory) / "manifest.yaml"
            metadata_path = Path(directory) / "metadata.json"
            manifest_path.write_text("placeholder", encoding="utf-8")
            metadata = self._metadata_fixture(manifest_path)
            metadata["unexpected"] = "value"
            metadata_path.write_text(json.dumps(metadata), encoding="utf-8")

            with patch.object(self.validator, "validate_manifest") as validate_manifest:
                with self.assertRaisesRegex(
                    ValueError,
                    r"metadata contains unsupported fields: \['unexpected'\]",
                ):
                    self.validator.validate_metadata(
                        metadata_path,
                        manifest_path,
                        "example/FireMUD",
                        "42",
                        "42",
                        "base-42",
                        "head-42",
                        "merge-42",
                        "pr-42-head-42",
                        "pr-42.preview.example.test",
                    )
                validate_manifest.assert_not_called()

    def test_metadata_event_specific_action_schema(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest_path = Path(directory) / "manifest.yaml"
            metadata_path = Path(directory) / "metadata.json"
            manifest_path.write_text("placeholder", encoding="utf-8")

            dispatch_metadata = self._metadata_fixture(manifest_path)
            dispatch_metadata.update({"event": "repository_dispatch", "action": "deploy"})
            metadata_path.write_text(json.dumps(dispatch_metadata), encoding="utf-8")
            with patch.object(self.validator, "validate_manifest"):
                self.validator.validate_metadata(
                    metadata_path,
                    manifest_path,
                    "example/FireMUD",
                    "42",
                    "42",
                    "base-42",
                    "head-42",
                    "merge-42",
                    "pr-42-head-42",
                    "pr-42.preview.example.test",
                )

            dispatch_metadata["action"] = "destroy"
            metadata_path.write_text(json.dumps(dispatch_metadata), encoding="utf-8")
            with self.assertRaisesRegex(
                ValueError, "repository_dispatch render metadata action must be deploy"
            ):
                self.validator.validate_metadata(
                    metadata_path,
                    manifest_path,
                    "example/FireMUD",
                    "42",
                    "42",
                    "base-42",
                    "head-42",
                    "merge-42",
                    "pr-42-head-42",
                    "pr-42.preview.example.test",
                )

            pull_request_metadata = self._metadata_fixture(manifest_path)
            pull_request_metadata["action"] = "destroy"
            metadata_path.write_text(
                json.dumps(pull_request_metadata), encoding="utf-8"
            )
            with self.assertRaisesRegex(
                ValueError, r"metadata contains unsupported fields: \['action'\]"
            ):
                self.validator.validate_metadata(
                    metadata_path,
                    manifest_path,
                    "example/FireMUD",
                    "42",
                    "42",
                    "base-42",
                    "head-42",
                    "merge-42",
                    "pr-42-head-42",
                    "pr-42.preview.example.test",
                )

    def test_metadata_uses_canonical_pr_number_for_manifest_namespace(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest_path = Path(directory) / "manifest.yaml"
            metadata_path = Path(directory) / "metadata.json"
            manifest_path.write_text("placeholder", encoding="utf-8")
            metadata = self._metadata_fixture(manifest_path)
            metadata_path.write_text(json.dumps(metadata), encoding="utf-8")

            with patch.object(self.validator, "validate_manifest") as validate_manifest:
                self.validator.validate_metadata(
                    metadata_path,
                    manifest_path,
                    "example/FireMUD",
                    "42",
                    "42",
                    "base-42",
                    "head-42",
                    "merge-42",
                    "pr-42-head-42",
                    "pr-42.preview.example.test",
                )

            validate_manifest.assert_called_once_with(
                manifest_path,
                "pr-42",
                "pr-42-head-42",
                "pr-42.preview.example.test",
                "hosted-controller",
            )

    def test_metadata_rejects_noncanonical_pr_number_forms(self):
        for pr_number in ("0", "-1", "+1", " 1", "1 ", "01"):
            with self.subTest(pr_number=pr_number), tempfile.TemporaryDirectory() as directory:
                manifest_path = Path(directory) / "manifest.yaml"
                metadata_path = Path(directory) / "metadata.json"
                manifest_path.write_text("placeholder", encoding="utf-8")
                metadata_path.write_text(
                    json.dumps(self._metadata_fixture(manifest_path)), encoding="utf-8"
                )
                with self.assertRaisesRegex(ValueError, "positive canonical decimal"):
                    self.validator.validate_metadata(
                        metadata_path,
                        manifest_path,
                        "example/FireMUD",
                        "42",
                        pr_number,
                        "base-42",
                        "head-42",
                        "merge-42",
                        "pr-42-head-42",
                        "pr-42.preview.example.test",
                    )


class PreviewArtifactTelnetInjectionTest(unittest.TestCase):
    validator = VALIDATOR

    def _tcp_proxy_service(self, spec, *, namespace=None, mode=None):
        spec = copy.deepcopy(spec)
        if mode is not None:
            spec.setdefault(
                "type",
                "NodePort" if mode == "standalone" else "ClusterIP",
            )
        metadata = {
            "name": "tcp-proxy-service",
            "labels": VALIDATOR._expected_object_labels("pr-42"),
        }
        if mode is not None:
            metadata["labels"][self.validator.CERTIFICATE_IDENTITY_LABEL] = mode
        if namespace is not None:
            metadata["namespace"] = namespace
        return {
            "apiVersion": "v1",
            "kind": "Service",
            "metadata": metadata,
            "spec": spec,
        }

    def _preview_ingress(self, *, namespace=None):
        metadata = {
            "name": "firemud-preview",
            "labels": {
                **self.validator._expected_top_level_labels(),
                "app.kubernetes.io/instance": "pr-42",
            },
        }
        if namespace is not None:
            metadata["namespace"] = namespace
        return {
            "apiVersion": "networking.k8s.io/v1",
            "kind": "Ingress",
            "metadata": metadata,
            "spec": {},
        }

    def test_injection_rejects_missing_namespace_before_reading_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "missing.yaml"
            destination = Path(directory) / "destination.yaml"
            with self.assertRaisesRegex(
                ValueError, "preview runtime namespace is required"
            ):
                self.validator.inject_telnet_port(source, destination, 32000, None)
            self.assertFalse(destination.exists())

    def test_injection_rejects_noncanonical_namespace_before_reading_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "missing.yaml"
            destination = Path(directory) / "destination.yaml"
            with self.assertRaisesRegex(
                ValueError, "runtime namespace is not canonical: 'dev'"
            ):
                self.validator.inject_telnet_port(source, destination, 32000, "dev")
            self.assertFalse(destination.exists())

    def test_injection_accepts_canonical_namespace(self):
        document = self._tcp_proxy_service(
            {
                "type": "NodePort",
                "ports": [{"name": "tcp-2323", "port": 2323}],
            }
        )
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.yaml"
            destination = Path(directory) / "destination.yaml"
            source.write_text(yaml.safe_dump(document), encoding="utf-8")

            self.validator.inject_telnet_port(source, destination, 32000, "pr-42")

            prepared = yaml.safe_load(destination.read_text(encoding="utf-8"))
            self.assertEqual(prepared["metadata"]["namespace"], "pr-42")
            self.assertEqual(prepared["spec"]["ports"][0]["nodePort"], 32000)
            self.assertEqual(
                prepared["metadata"]["annotations"],
                {"firemud.dev/allocated-telnet-port": "32000"},
            )

    def test_standalone_injects_only_trusted_ingress_issuer(self):
        trusted_values = yaml.safe_load(
            (ROOT / "k8s/helm/firemud/values-hosted-shared.example.yaml").read_text(
                encoding="utf-8"
            )
        )
        self.assertEqual(
            trusted_values["previewStack"]["ingress"]["clusterIssuer"],
            self.validator.CANONICAL_INGRESS_ISSUER,
        )
        documents = [
            self._tcp_proxy_service(
                {"ports": [{"port": 2323}]}, mode="standalone"
            ),
            self._preview_ingress(),
        ]
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.yaml"
            prepared = Path(directory) / "prepared.yaml"
            source.write_text(yaml.safe_dump_all(documents), encoding="utf-8")
            self.validator.inject_telnet_port(
                source, prepared, 32000, "pr-42", "standalone"
            )
            result = list(yaml.safe_load_all(prepared.read_text(encoding="utf-8")))
            self.assertEqual(
                result[0]["metadata"]["annotations"],
                {"firemud.dev/allocated-telnet-port": "32000"},
            )
            self.assertEqual(
                result[1]["metadata"]["annotations"],
                {"cert-manager.io/cluster-issuer": "letsencrypt-prod"},
            )
            self.validator.validate_runtime_target(prepared, "pr-42", 32000, "standalone")
            with self.assertRaisesRegex(
                ValueError, "certificate-identity-mode.*expected 'hosted-controller'"
            ):
                self.validator.validate_runtime_target(
                    prepared, "pr-42", 32000, "hosted-controller"
                )
            for annotations in (
                {"cert-manager.io/cluster-issuer": "attacker"},
                {
                    "cert-manager.io/cluster-issuer": "letsencrypt-prod",
                    "unexpected": "value",
                },
            ):
                with self.subTest(annotations=annotations):
                    result[1]["metadata"]["annotations"] = annotations
                    prepared.write_text(yaml.safe_dump_all(result), encoding="utf-8")
                    with self.assertRaisesRegex(ValueError, "unsafe certificate issuer"):
                        self.validator.validate_runtime_target(
                            prepared, "pr-42", 32000, "standalone"
                        )

    def test_standalone_injection_rejects_missing_ingress_or_source_annotations(self):
        service = self._tcp_proxy_service(
            {"ports": [{"port": 2323}]}, mode="standalone"
        )
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.yaml"
            prepared = Path(directory) / "prepared.yaml"
            source.write_text(yaml.safe_dump(service), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "exactly one preview Ingress"):
                self.validator.inject_telnet_port(
                    source, prepared, 32000, "pr-42", "standalone"
                )
            self.assertFalse(prepared.exists())
            ingress = self._preview_ingress()
            ingress["metadata"]["annotations"] = {
                "cert-manager.io/cluster-issuer": "attacker"
            }
            source.write_text(yaml.safe_dump_all([service, ingress]), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "unsupported fields"):
                self.validator.inject_telnet_port(
                    source, prepared, 32000, "pr-42", "standalone"
                )
            self.assertFalse(prepared.exists())

    def test_private_injection_preserves_cluster_ip_and_rejects_telnet_port(self):
        service = self._tcp_proxy_service(
            {"ports": [{"name": "tcp-2323", "port": 2323}]},
            mode="hosted-controller",
        )
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.yaml"
            prepared = Path(directory) / "prepared.yaml"
            source.write_text(yaml.safe_dump(service), encoding="utf-8")
            self.validator.inject_telnet_port(
                source, prepared, 0, "pr-42", "hosted-controller"
            )
            result = yaml.safe_load(prepared.read_text(encoding="utf-8"))
            self.assertNotIn("nodePort", result["spec"]["ports"][0])
            self.assertNotIn("annotations", result["metadata"])
            with self.assertRaisesRegex(ValueError, "sentinel Telnet port 0"):
                self.validator.inject_telnet_port(
                    source, prepared, 32000, "pr-42", "hosted-controller"
                )

    def test_hosted_controller_accepts_explicit_public_exposure(self):
        service = self._tcp_proxy_service(
            copy.deepcopy(self.validator.EXPECTED_SERVICE_SPECS["tcp-proxy-service"]),
            namespace="pr-42",
            mode="hosted-controller",
        )
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.yaml"
            prepared = Path(directory) / "prepared.yaml"
            source.write_text(yaml.safe_dump(service), encoding="utf-8")
            self.validator.inject_telnet_port(
                source, prepared, 32000, "pr-42", "hosted-controller", "public"
            )
            self.validator.validate_runtime_target(
                prepared, "pr-42", 32000, "hosted-controller", "public"
            )

    def test_explicit_exposure_mode_must_match_service_shape(self):
        service = self._tcp_proxy_service(
            copy.deepcopy(self.validator.EXPECTED_SERVICE_SPECS["tcp-proxy-service"]),
            namespace="pr-42",
            mode="hosted-controller",
        )
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.yaml"
            prepared = Path(directory) / "prepared.yaml"
            source.write_text(yaml.safe_dump(service), encoding="utf-8")
            with self.assertRaisesRegex(
                ValueError, "does not match TCP Proxy Service type"
            ):
                self.validator.inject_telnet_port(
                    source, prepared, 0, "pr-42", "hosted-controller", "private"
                )
            with self.assertRaisesRegex(
                ValueError, "does not match TCP Proxy Service type"
            ):
                self.validator.validate_runtime_target(
                    source, "pr-42", 0, "hosted-controller", "private"
                )

    def test_invalid_explicit_exposure_mode_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "missing-source.yaml"
            prepared = Path(directory) / "prepared.yaml"
            with self.assertRaisesRegex(
                ValueError, "preview exposure mode is not canonical"
            ):
                self.validator.inject_telnet_port(
                    source, prepared, 0, "pr-42", "hosted-controller", "invalid"
                )
            with self.assertRaisesRegex(
                ValueError, "preview exposure mode is not canonical"
            ):
                self.validator.validate_runtime_target(
                    source, "pr-42", 0, "hosted-controller", "invalid"
                )

    def test_exposure_mode_is_derived_from_tcp_proxy_service_shape(self):
        for service_type, expected_mode, identity_mode in (
            ("ClusterIP", "private", "hosted-controller"),
            ("NodePort", "public", "standalone"),
        ):
            with self.subTest(service_type=service_type), tempfile.TemporaryDirectory() as directory:
                source = Path(directory) / "source.yaml"
                spec = copy.deepcopy(
                    self.validator.EXPECTED_SERVICE_SPECS["tcp-proxy-service"]
                )
                spec["type"] = service_type
                source.write_text(
                    yaml.safe_dump(
                        self._tcp_proxy_service(
                            spec,
                            mode=identity_mode,
                        )
                    ),
                    encoding="utf-8",
                )
                self.assertEqual(
                    expected_mode,
                    self.validator.determine_exposure_mode(source, identity_mode),
                )

    def test_expected_top_level_label_mismatches_report_expected_and_actual(self):
        expected_labels = VALIDATOR._expected_object_labels("pr-42")
        for label, expected_value in expected_labels.items():
            document = self._tcp_proxy_service({})
            document["metadata"]["labels"] = {
                **expected_labels,
                label: "unexpected-value",
                "unexpected-label": "unexpected-value",
            }
            with self.subTest(label=label), self.assertRaisesRegex(
                ValueError,
                re.escape(
                    f"Service/tcp-proxy-service label {label!r} mismatch: "
                    f"expected {expected_value!r}, actual 'unexpected-value'"
                ),
            ):
                self.validator._validate_object_metadata(document, "pr-42")

    def test_runtime_target_finds_declared_telnet_port_without_relying_on_index(self):
        document = self._tcp_proxy_service(
            {
                "type": "NodePort",
                "ports": [
                    {"name": "metrics", "port": 8080},
                    {"name": "tcp-2323", "port": 2323, "nodePort": 32001},
                ]
            },
            namespace="pr-42",
        )
        document["metadata"]["annotations"] = {
            "firemud.dev/allocated-telnet-port": "32001"
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "prepared.yaml"
            path.write_text(yaml.safe_dump(document), encoding="utf-8")

            self.validator.validate_runtime_target(path, "pr-42", 32001)

    def test_runtime_target_rejects_unbound_or_extra_public_service_annotations(self):
        document = self._tcp_proxy_service(
            {
                "type": "NodePort",
                "ports": [
                    {"name": "tcp-2323", "port": 2323, "nodePort": 32001},
                ],
            },
            namespace="pr-42",
        )
        for annotations in (
            {"firemud.dev/allocated-telnet-port": "32000"},
            {
                "firemud.dev/allocated-telnet-port": "32001",
                "unexpected.example/annotation": "value",
            },
        ):
            with self.subTest(annotations=annotations), tempfile.TemporaryDirectory() as directory:
                document["metadata"]["annotations"] = annotations
                path = Path(directory) / "prepared.yaml"
                path.write_text(yaml.safe_dump(document), encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "allocator annotation"):
                    self.validator.validate_runtime_target(path, "pr-42", 32001)

    def test_runtime_target_rejects_private_service_annotation(self):
        document = self._tcp_proxy_service(
            {
                "type": "ClusterIP",
                "ports": [{"name": "tcp-2323", "port": 2323}],
            },
            namespace="pr-42",
            mode="hosted-controller",
        )
        document["metadata"]["annotations"] = {
            "firemud.dev/allocated-telnet-port": "0"
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "prepared.yaml"
            path.write_text(yaml.safe_dump(document), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "must not contain annotations"):
                self.validator.validate_runtime_target(
                    path, "pr-42", 0, "hosted-controller", "private"
                )

    def test_runtime_target_rejects_non_object_service_spec_or_ports(self):
        for spec, message in (
            ([], "Service/tcp-proxy-service.spec is not an object"),
            (
                {"ports": {}},
                "Service/tcp-proxy-service.spec.ports is not a list of objects",
            ),
        ):
            document = self._tcp_proxy_service(spec, namespace="pr-42")
            with self.subTest(spec=spec), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "prepared.yaml"
                path.write_text(yaml.safe_dump(document), encoding="utf-8")
                with self.assertRaisesRegex(ValueError, message):
                    self.validator.validate_runtime_target(path, "pr-42", 32001)

    def test_injection_rejects_missing_object_metadata_after_namespace_validation(self):
        document = {
            "apiVersion": "v1",
            "kind": "Service",
            "spec": {"ports": [{"name": "tcp-2323", "port": 2323}]},
        }
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.yaml"
            destination = Path(directory) / "destination.yaml"
            source.write_text(yaml.safe_dump(document), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Service.metadata is not an object"):
                self.validator.inject_telnet_port(source, destination, 32000, "pr-42")
            self.assertFalse(destination.exists())

    def test_injection_rejects_non_object_service_spec_or_ports(self):
        for spec, message in (
            ([], "Service/tcp-proxy-service.spec is not an object"),
            (
                {"ports": {}},
                "Service/tcp-proxy-service.spec.ports is not a list of objects",
            ),
        ):
            document = self._tcp_proxy_service(spec)
            with tempfile.TemporaryDirectory() as directory:
                source = Path(directory) / "source.yaml"
                destination = Path(directory) / "destination.yaml"
                source.write_text(yaml.safe_dump(document), encoding="utf-8")
                with self.assertRaisesRegex(ValueError, message):
                    self.validator.inject_telnet_port(source, destination, 32000, "pr-42")
                self.assertFalse(destination.exists())


class PreviewArtifactCertificateIdentityModeTest(unittest.TestCase):
    validator = VALIDATOR

    def _labels(self, mode=None):
        labels = {
            **self.validator._expected_top_level_labels(),
            "app.kubernetes.io/instance": "pr-42",
        }
        if mode is not None:
            labels[self.validator.CERTIFICATE_IDENTITY_LABEL] = mode
        return labels

    def _metadata_document(self, kind, name, mode=None):
        return {
            "apiVersion": (
                "apps/v1" if kind == "Deployment" else "v1"
            ),
            "kind": kind,
            "metadata": {"name": name, "labels": self._labels(mode)},
        }

    def _policy_document(self, name, spec):
        return {
            "apiVersion": "networking.k8s.io/v1",
            "kind": "NetworkPolicy",
            "metadata": {"name": name, "labels": self._labels()},
            "spec": copy.deepcopy(spec),
        }

    @staticmethod
    def _controller_from():
        return {
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

    def _policy_documents(self, mode):
        controller_from = self._controller_from()
        gateway_ingress = [
            {
                "from": [
                    {"podSelector": {"matchLabels": {"app": "tcp-proxy-service"}}}
                ],
                "ports": [{"protocol": "TCP", "port": 8443}],
            },
            {
                "from": [
                    {
                        "namespaceSelector": {
                            "matchLabels": {
                                "kubernetes.io/metadata.name": "kube-system"
                            }
                        },
                        "podSelector": {
                            "matchLabels": {"app.kubernetes.io/name": "traefik"}
                        },
                    }
                ],
                "ports": [{"protocol": "TCP", "port": 8080}],
            },
        ]
        if mode == "hosted-controller":
            gateway_ingress.append(
                {
                    "from": [controller_from],
                    "ports": [{"protocol": "TCP", "port": 8443}],
                }
            )
        proxy_egress = [
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
                "to": [{"podSelector": {"matchLabels": {"app": "spring-cloud-gateway"}}}],
                "ports": [{"protocol": "TCP", "port": 8443}],
            },
            {
                "to": [{"podSelector": {"matchLabels": {"app": "game-session-service"}}}],
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
        ]
        gateway_egress = [
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
                "to": [
                    {
                        "podSelector": {
                            "matchExpressions": [
                                {
                                    "key": "app",
                                    "operator": "In",
                                    "values": [
                                        "game-session-service",
                                        "logging-admin-service",
                                        "game-design-service",
                                        "account-service",
                                        "social-groups-service",
                                    ],
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
        documents = [
            self._policy_document(
                name, spec
            )
            for name, spec in self.validator.EXPECTED_INTERNAL_NETWORK_POLICY_SPECS.items()
        ]
        if mode == "hosted-controller":
            documents.append(
                self._policy_document(
                    "account-service-controller-ingress",
                    {
                        "podSelector": {"matchLabels": {"app": "account-service"}},
                        "policyTypes": ["Ingress"],
                        "ingress": [
                            {
                                "from": [controller_from],
                                "ports": [{"protocol": "TCP", "port": 6565}],
                            }
                        ],
                    },
                )
            )
        documents.extend(
            [
                self._policy_document(
                    "spring-cloud-gateway-ingress",
                    {
                        "podSelector": {
                            "matchLabels": {"app": "spring-cloud-gateway"}
                        },
                        "policyTypes": ["Ingress"],
                        "ingress": gateway_ingress,
                    },
                ),
                self._policy_document(
                    "spring-cloud-gateway-egress",
                    {
                        "podSelector": {
                            "matchLabels": {"app": "spring-cloud-gateway"}
                        },
                        "policyTypes": ["Egress"],
                        "egress": gateway_egress,
                    },
                ),
                self._policy_document(
                    "tcp-proxy-service-egress",
                    {
                        "podSelector": {
                            "matchLabels": {"app": "tcp-proxy-service"}
                        },
                        "policyTypes": ["Egress"],
                        "egress": proxy_egress,
                    },
                ),
            ]
        )
        return documents

    def test_both_modes_require_the_exact_tcp_proxy_identity_labels(self):
        for mode in ("standalone", "hosted-controller"):
            with self.subTest(mode=mode):
                for kind in ("Deployment", "Service"):
                    self.validator._validate_object_metadata(
                        self._metadata_document(kind, "tcp-proxy-service", mode),
                        "pr-42",
                        certificate_identity_mode=mode,
                    )
                self.validator.validate_network_policies(
                    self._policy_documents(mode), mode
                )

    def test_identity_label_rejects_missing_spoofed_extra_and_elsewhere(self):
        for mode in ("standalone", "hosted-controller"):
            valid = self._metadata_document("Service", "tcp-proxy-service", mode)
            spoofed_mode = (
                "standalone"
                if mode == "hosted-controller"
                else "hosted-controller"
            )
            cases = {
                "missing": lambda document: document["metadata"]["labels"].pop(
                    self.validator.CERTIFICATE_IDENTITY_LABEL
                ),
                "spoofed": lambda document, spoofed_mode=spoofed_mode: document[
                    "metadata"
                ]["labels"].__setitem__(
                    self.validator.CERTIFICATE_IDENTITY_LABEL,
                    spoofed_mode,
                ),
                "extra": lambda document: document["metadata"]["labels"].__setitem__(
                    "firemud.dev/untrusted", "capture"
                ),
                "elsewhere": lambda document, mode=mode: document[
                    "metadata"
                ]["labels"].__setitem__(
                    self.validator.CERTIFICATE_IDENTITY_LABEL, mode
                ),
            }
            for name, mutation in cases.items():
                with self.subTest(mode=mode, case=name):
                    document = copy.deepcopy(valid)
                    if name == "elsewhere":
                        document["metadata"]["name"] = "account-service"
                    mutation(document)
                    with self.assertRaises(ValueError):
                        self.validator._validate_object_metadata(
                            document,
                            "pr-42",
                            certificate_identity_mode=mode,
                        )

    def test_identity_labels_survive_sanitize_injection_and_runtime_target(self):
        for mode in ("standalone", "hosted-controller"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                source = Path(directory) / "source.yaml"
                sanitized = Path(directory) / "sanitized.yaml"
                prepared = Path(directory) / "prepared.yaml"
                service = {
                    "apiVersion": "v1",
                    "kind": "Service",
                    "metadata": {
                        "name": "tcp-proxy-service",
                        "labels": self._labels(mode),
                    },
                    "spec": {
                        "type": "NodePort" if mode == "standalone" else "ClusterIP",
                        "ports": [{"port": 2323}],
                    },
                }
                ingress = {
                    "apiVersion": "networking.k8s.io/v1",
                    "kind": "Ingress",
                    "metadata": {
                        "name": "firemud-preview",
                        "labels": self._labels(),
                        "annotations": {"untrusted.example/annotation": "drop"},
                    },
                    "spec": {},
                }
                source.write_text(
                    yaml.safe_dump_all([service, ingress]), encoding="utf-8"
                )
                self.validator.sanitize(source, sanitized)
                sanitized_documents = list(
                    yaml.safe_load_all(sanitized.read_text(encoding="utf-8"))
                )
                self.assertEqual(
                    sanitized_documents[0]["metadata"]["labels"], self._labels(mode)
                )
                self.validator.inject_telnet_port(
                    sanitized,
                    prepared,
                    0 if mode == "hosted-controller" else 32000,
                    "pr-42",
                    mode,
                )
                prepared_documents = list(
                    yaml.safe_load_all(prepared.read_text(encoding="utf-8"))
                )
                self.assertEqual(
                    prepared_documents[0]["metadata"]["labels"], self._labels(mode)
                )
                self.validator.validate_runtime_target(
                    prepared,
                    "pr-42",
                    0 if mode == "hosted-controller" else 32000,
                    mode,
                )

    def test_mode_specific_policy_sets_and_rules_are_closed(self):
        hosted = self._policy_documents("hosted-controller")
        standalone = self._policy_documents("standalone")
        with self.assertRaisesRegex(ValueError, "runtime NetworkPolicy set is not closed"):
            self.validator.validate_network_policies(
                standalone
                + [
                    self._policy_document(
                        "account-service-controller-ingress", {}
                    )
                ],
                "standalone",
            )
        missing_account = [
            document
            for document in hosted
            if document["metadata"]["name"] != "account-service-controller-ingress"
        ]
        with self.assertRaisesRegex(ValueError, "runtime NetworkPolicy set is not closed"):
            self.validator.validate_network_policies(missing_account, "hosted-controller")

        for mode in ("standalone", "hosted-controller"):
            with self.subTest(mode=mode, case="missing gateway egress"):
                missing_gateway_egress = [
                    document
                    for document in self._policy_documents(mode)
                    if document["metadata"]["name"] != "spring-cloud-gateway-egress"
                ]
                with self.assertRaisesRegex(
                    ValueError, "runtime NetworkPolicy set is not closed"
                ):
                    self.validator.validate_network_policies(
                        missing_gateway_egress, mode
                    )

            with self.subTest(mode=mode, case="broadened gateway egress"):
                broadened = copy.deepcopy(self._policy_documents(mode))
                gateway_egress = next(
                    document
                    for document in broadened
                    if document["metadata"]["name"] == "spring-cloud-gateway-egress"
                )
                gateway_egress["spec"]["egress"][1]["ports"][0]["port"] = 6565
                with self.assertRaisesRegex(ValueError, "unsafe spec"):
                    self.validator.validate_network_policies(broadened, mode)

        hosted_broad = copy.deepcopy(hosted)
        gateway = next(
            document
            for document in hosted_broad
            if document["metadata"]["name"] == "spring-cloud-gateway-ingress"
        )
        gateway["spec"]["ingress"].append(
            {
                "from": [{"podSelector": {}}],
                "ports": [{"protocol": "TCP", "port": 6565}],
            }
        )
        with self.assertRaisesRegex(ValueError, "unsafe exception"):
            self.validator.validate_network_policies(hosted_broad, "hosted-controller")

        standalone_controller = copy.deepcopy(standalone)
        standalone_gateway = next(
            document
            for document in standalone_controller
            if document["metadata"]["name"] == "spring-cloud-gateway-ingress"
        )
        standalone_gateway["spec"]["ingress"].append(
            {
                "from": [self._controller_from()],
                "ports": [{"protocol": "TCP", "port": 8443}],
            }
        )
        with self.assertRaisesRegex(ValueError, "unsafe exception"):
            self.validator.validate_network_policies(
                standalone_controller, "standalone"
            )


class PreviewArtifactCommandLineTest(unittest.TestCase):
    validator = VALIDATOR

    def test_chart_metadata_failures_are_reported_by_main(self):
        with tempfile.TemporaryDirectory() as directory:
            temp_dir = Path(directory)
            prepared_manifest = temp_dir / "prepared.yaml"
            prepared_manifest.write_text(
                yaml.safe_dump(
                    {
                        "apiVersion": "v1",
                        "kind": "Service",
                        "metadata": {
                            "name": "tcp-proxy-service",
                            "namespace": "pr-42",
                            "labels": {},
                        },
                        "spec": {"ports": [{"port": 2323, "nodePort": 32000}]},
                    }
                ),
                encoding="utf-8",
            )
            missing_chart = temp_dir / "missing-Chart.yaml"
            directory_chart = temp_dir / "directory-Chart.yaml"
            malformed_chart = temp_dir / "malformed-Chart.yaml"
            directory_chart.mkdir()
            malformed_chart.write_text("name: [\n", encoding="utf-8")

            for chart_metadata in (
                missing_chart,
                directory_chart,
                malformed_chart,
            ):
                with self.subTest(chart_metadata=chart_metadata):
                    stderr = io.StringIO()
                    argv = [
                        str(SCRIPT),
                        "runtime-target",
                        str(prepared_manifest),
                        "pr-42",
                        "32000",
                        "hosted-controller",
                        "private",
                    ]
                    with (
                        patch.object(
                            self.validator, "TRUSTED_CHART_METADATA", chart_metadata
                        ),
                        redirect_stderr(stderr),
                    ):
                        self.assertEqual(self.validator.main(argv), 1)

                    self.assertEqual(
                        stderr.getvalue(),
                        "preview runtime target rejected: "
                        f"could not load trusted chart metadata: {chart_metadata}\n",
                    )

    def test_subcommand_names_with_wrong_arity_report_general_usage(self):
        for command in ("sanitize", "inject", "runtime-target"):
            stderr = io.StringIO()
            with (
                self.subTest(command=command),
                redirect_stderr(stderr),
            ):
                self.assertEqual(self.validator.main([str(SCRIPT), command]), 2)
            self.assertTrue(
                stderr.getvalue().startswith(
                    "usage: validate-preview-artifact.py <metadata> <manifest>"
                )
            )

    def test_runtime_mutation_commands_require_certificate_identity_mode(self):
        for command, command_arguments in (
            (
                "inject",
                ["source.yaml", "destination.yaml", "pr-42", "32000"],
            ),
            (
                "runtime-target",
                ["prepared.yaml", "pr-42", "32000"],
            ),
        ):
            with self.subTest(command=command), redirect_stderr(io.StringIO()):
                self.assertEqual(
                    self.validator.main(
                        [str(SCRIPT), command, *command_arguments]
                    ),
                    2,
                )

    def test_runtime_mutation_commands_require_exposure_mode(self):
        for command, command_arguments in (
            (
                "inject",
                ["source.yaml", "destination.yaml", "pr-42", "32000", "standalone"],
            ),
            (
                "runtime-target",
                ["prepared.yaml", "pr-42", "32000", "standalone"],
            ),
        ):
            with self.subTest(command=command):
                stderr = io.StringIO()
                with redirect_stderr(stderr):
                    self.assertEqual(
                        self.validator.main(
                            [str(SCRIPT), command, *command_arguments]
                        ),
                        2,
                    )
                self.assertTrue(
                    stderr.getvalue().startswith(
                        "usage: validate-preview-artifact.py"
                    )
                )

    def test_artifact_validation_requires_certificate_identity_mode(self):
        arguments = [
            str(SCRIPT),
            "metadata.json",
            "manifest.yaml",
            "example/FireMUD",
            "123",
            "42",
            "a" * 40,
            "b" * 40,
            "c" * 40,
            "head-tag",
            "pr-42.preview.example.test",
        ]
        missing_mode_stderr = io.StringIO()
        with redirect_stderr(missing_mode_stderr):
            self.assertEqual(self.validator.main(arguments), 2)
        self.assertTrue(
            missing_mode_stderr.getvalue().startswith(
                "usage: validate-preview-artifact.py"
            )
        )
        stderr = io.StringIO()
        with redirect_stderr(stderr):
            self.assertEqual(
                self.validator.main([*arguments, "untrusted-mode"]),
                1,
            )
        self.assertEqual(
            stderr.getvalue(),
            "preview artifact rejected: preview certificate identity mode is not canonical\n",
        )

    def test_subcommand_named_metadata_paths_use_default_validation(self):
        for metadata_path in ("sanitize", "inject", "runtime-target"):
            argv = [
                str(SCRIPT),
                metadata_path,
                "manifest.yaml",
                "example/FireMUD",
                "123",
                "42",
                "a" * 40,
                "b" * 40,
                "c" * 40,
                "head-tag",
                "pr-42.preview.example.test",
                "hosted-controller",
            ]
            with (
                self.subTest(metadata_path=metadata_path),
                patch.object(self.validator, "validate_metadata") as validate_metadata,
            ):
                self.assertEqual(self.validator.main(argv), 0)
                validate_metadata.assert_called_once_with(
                    Path(metadata_path), Path("manifest.yaml"), *argv[3:]
                )


class PreviewArtifactConfigMapSanitizerTest(unittest.TestCase):
    validator = VALIDATOR

    def _trusted_config_data(self):
        return copy.deepcopy(self.validator._trusted_hosted_shared_config())

    def _sanitize_config_map_data(self, data):
        document = {
            "apiVersion": "v1",
            "kind": "ConfigMap",
            "metadata": {"name": "firemud-config"},
            "data": data,
        }
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.yaml"
            destination = Path(directory) / "sanitized.yaml"
            source.write_text(yaml.safe_dump(document), encoding="utf-8")
            self.validator.sanitize(source, destination)
            return yaml.safe_load(destination.read_text(encoding="utf-8"))["data"]

    def _validate_config_map_manifest(self, document):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.yaml"
            path.write_text(yaml.safe_dump(document), encoding="utf-8")
            with (
                patch.object(
                    self.validator,
                    "EXPECTED_NAMES",
                    {"ConfigMap": {"firemud-config"}},
                ),
                patch.object(
                    self.validator,
                    "EXPECTED_OBJECTS",
                    {("ConfigMap", "firemud-config")},
                ),
                patch.object(self.validator, "validate_services"),
                patch.object(self.validator, "validate_network_policies"),
                patch.object(
                    self.validator, "validate_infrastructure_deployments"
                ),
                patch.object(self.validator, "validate_service_consumers"),
            ):
                self.validator.validate_manifest(
                    path,
                    "pr-42",
                    "pr-42-head-42",
                    "pr-42.preview.example.test",
                )

    def test_sanitize_rejects_unapproved_name_for_every_supported_kind(self):
        api_versions = {
            kind: api_version
            for api_version, kind in self.validator.EXPECTED_KINDS
        }
        for kind in self.validator.EXPECTED_NAMES:
            document = {
                "apiVersion": api_versions[kind],
                "kind": kind,
                "metadata": {"name": "unapproved-preview-object"},
            }
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as directory:
                source = Path(directory) / "source.yaml"
                destination = Path(directory) / "sanitized.yaml"
                source.write_text(yaml.safe_dump(document), encoding="utf-8")

                with self.assertRaisesRegex(
                    ValueError,
                    re.escape(
                        f"{kind}/unapproved-preview-object is not an approved preview object"
                    ),
                ):
                    self.validator.sanitize(source, destination)

                self.assertFalse(destination.exists())

    def test_sanitize_rejects_jwt_jwks_config_map_as_unapproved(self):
        self.assertNotIn("jwt-jwks", self.validator.EXPECTED_NAMES["ConfigMap"])
        document = {
            "apiVersion": "v1",
            "kind": "ConfigMap",
            "metadata": {"name": "jwt-jwks"},
            "data": {"jwks.json": '{"keys": []}'},
        }
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.yaml"
            destination = Path(directory) / "sanitized.yaml"
            source.write_text(yaml.safe_dump(document), encoding="utf-8")

            with self.assertRaisesRegex(
                ValueError,
                re.escape("ConfigMap/jwt-jwks is not an approved preview object"),
            ):
                self.validator.sanitize(source, destination)

            self.assertFalse(destination.exists())

    def test_sanitize_rejects_unknown_redirect_and_jvm_keys(self):
        for key in (
            "FIREMUD_GATEWAY_GAMEPLAY_BRIDGE_UPSTREAM_URL",
            "FIREMUD_GATEWAY_ROUTE_ACCOUNT_URI",
            "SPRING_APPLICATION_JSON",
            "JAVA_TOOL_OPTIONS",
        ):
            with self.subTest(key=key):
                data = self._trusted_config_data()
                data[key] = "attacker-controlled"
                with self.assertRaisesRegex(ValueError, "unsafe keys"):
                    self._sanitize_config_map_data(data)

    def test_sanitize_rejects_changed_trusted_values(self):
        data = self._trusted_config_data()
        data["FIREMUD_REDIS_CACHE_HOST"] = "attacker.example"
        with self.assertRaisesRegex(ValueError, "differs from the trusted hosted value"):
            self._sanitize_config_map_data(data)

    def test_sanitize_preserves_only_trusted_noncredential_values(self):
        source = self._trusted_config_data()
        expected = {
            key: value
            for key, value in source.items()
            if key not in self.validator.HOSTED_REDACTED_CONFIG_KEYS
        }

        self.assertEqual(self._sanitize_config_map_data(source), expected)

    def test_final_validation_accepts_trusted_data(self):
        document = {
            "apiVersion": "v1",
            "kind": "ConfigMap",
            "metadata": {
                "name": "firemud-config",
                "labels": {
                    **self.validator._expected_top_level_labels(),
                    "app.kubernetes.io/instance": "pr-42",
                },
            },
            "data": {
                key: value
                for key, value in self._trusted_config_data().items()
                if key not in self.validator.HOSTED_REDACTED_CONFIG_KEYS
            },
        }

        self._validate_config_map_manifest(document)

    def test_sanitize_rejects_binary_data(self):
        document = {
            "apiVersion": "v1",
            "kind": "ConfigMap",
            "metadata": {"name": "firemud-config"},
            "data": self._trusted_config_data(),
            "binaryData": {"database_credential_file": "c2VjcmV0"},
        }
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.yaml"
            destination = Path(directory) / "sanitized.yaml"
            source.write_text(yaml.safe_dump(document), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "extra=\\['binaryData'\\]"):
                self.validator.sanitize(source, destination)

            self.assertFalse(destination.exists())

    def test_final_validation_rejects_binary_data(self):
        document = {
            "apiVersion": "v1",
            "kind": "ConfigMap",
            "metadata": {
                "name": "firemud-config",
                "labels": {
                    **self.validator._expected_top_level_labels(),
                    "app.kubernetes.io/instance": "pr-42",
                },
            },
            "data": {
                key: value
                for key, value in self._trusted_config_data().items()
                if key not in self.validator.HOSTED_REDACTED_CONFIG_KEYS
            },
            "binaryData": {"database_credential_file": "c2VjcmV0"},
        }

        with self.assertRaisesRegex(ValueError, "extra=\\['binaryData'\\]"):
            self._validate_config_map_manifest(document)


class PreviewArtifactGatewayContractTest(unittest.TestCase):
    validator = VALIDATOR

    def _gateway_document(self):
        expected_namespace = "pr-42"
        return {
            "kind": "Deployment",
            "metadata": {"name": "spring-cloud-gateway"},
            "spec": {
                "template": {
                    "spec": {
                        "serviceAccountName": "firemud-app",
                        "containers": [
                            {
                                "name": "spring-cloud-gateway",
                                "env": self.validator._expected_gateway_container_env(
                                    expected_namespace
                                ),
                                "envFrom": copy.deepcopy(
                                    self.validator.EXPECTED_GATEWAY_ENV_FROM
                                ),
                                "volumeMounts": [
                                    {
                                        "name": "grpc-tls",
                                        "mountPath": "/tls",
                                        "readOnly": True,
                                    },
                                    {
                                        "name": "jwt-signing-keys",
                                        "mountPath": "/var/run/secrets/firemud/jwt",
                                        "readOnly": True,
                                    },
                                    {
                                        "name": "gateway-ws-server-tls",
                                        "mountPath": "/gateway-ws-server-tls",
                                        "readOnly": True,
                                    },
                                ],
                            }
                        ],
                        "volumes": [
                            {
                                "name": "grpc-tls",
                                "secret": {"secretName": "firemud-grpc-tls"},
                            },
                            {
                                "name": "jwt-signing-keys",
                                "secret": {"secretName": "jwt-signing-keys"},
                            },
                            {
                                "name": "gateway-ws-server-tls",
                                "secret": {
                                    "secretName": "pr-42-gateway-internal-ws",
                                    "items": [
                                        {"key": "tls.crt", "path": "tls.crt"},
                                        {"key": "tls.key", "path": "tls.key"},
                                        {"key": "ca.crt", "path": "ca.crt"},
                                    ],
                                },
                            },
                        ],
                    }
                }
            },
        }

    def test_gateway_exact_environment_contract_is_accepted(self):
        with patch.object(self.validator, "SERVICE_IMAGES", {"spring-cloud-gateway"}):
            self.validator.validate_service_consumers(
                [self._gateway_document()], "pr-42"
            )

    def test_gateway_rejects_redirect_and_jvm_environment_injection(self):
        for injected in (
            {
                "name": "FIREMUD_GATEWAY_GAMEPLAY_BRIDGE_UPSTREAM_URL",
                "value": "wss://attacker.example",
            },
            {
                "name": "FIREMUD_GATEWAY_ROUTE_ACCOUNT_URI",
                "value": "https://attacker.example",
            },
            {
                "name": "SPRING_APPLICATION_JSON",
                "value": '{"spring.cloud.gateway.routes":[]}',
            },
            {"name": "JAVA_TOOL_OPTIONS", "value": "-Dspring.config.location=attacker"},
        ):
            with self.subTest(injected=injected):
                document = self._gateway_document()
                document["spec"]["template"]["spec"]["containers"][0]["env"].append(
                    injected
                )
                with (
                    patch.object(
                        self.validator, "SERVICE_IMAGES", {"spring-cloud-gateway"}
                    ),
                    self.assertRaisesRegex(ValueError, "unsafe container env"),
                ):
                    self.validator.validate_service_consumers([document], "pr-42")

    def test_gateway_rejects_extra_env_from_reference(self):
        document = self._gateway_document()
        document["spec"]["template"]["spec"]["containers"][0]["envFrom"].append(
            {"configMapRef": {"name": "attacker-config"}}
        )
        with (
            patch.object(self.validator, "SERVICE_IMAGES", {"spring-cloud-gateway"}),
            self.assertRaisesRegex(ValueError, "unsafe envFrom contract"),
        ):
            self.validator.validate_service_consumers([document], "pr-42")


class PreviewArtifactNetworkPolicyTest(unittest.TestCase):
    validator = VALIDATOR

    def test_gateway_egress_allows_only_declared_dependencies(self):
        policy = {"spec": copy.deepcopy(self.validator.EXPECTED_GATEWAY_NETWORK_POLICY_SPEC)}
        self.validator._validate_gateway_egress_policy(policy)

    def test_gateway_egress_rejects_widened_destination(self):
        policy = {"spec": copy.deepcopy(self.validator.EXPECTED_GATEWAY_NETWORK_POLICY_SPEC)}
        policy["spec"]["egress"].append(
            {"to": [{"ipBlock": {"cidr": "0.0.0.0/0"}}], "ports": [{"protocol": "TCP", "port": 443}]}
        )
        with self.assertRaisesRegex(ValueError, "spring-cloud-gateway-egress has an unsafe spec"):
            self.validator._validate_gateway_egress_policy(policy)

    def test_network_policy_set_rejects_missing_gateway_egress(self):
        documents = [
            {"kind": "NetworkPolicy", "metadata": {"name": name}}
            for name in self.validator.EXPECTED_NAMES["NetworkPolicy"]
            if name != "spring-cloud-gateway-egress"
        ]
        with self.assertRaisesRegex(ValueError, "runtime NetworkPolicy set is not closed"):
            self.validator.validate_network_policies(documents)


if __name__ == "__main__":
    unittest.main()
