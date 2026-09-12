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


class PreviewArtifactSecretReferenceTest(unittest.TestCase):
    validator = VALIDATOR

    def test_expected_chart_label_comes_from_trusted_metadata(self):
        self.assertEqual(
            "firemud-0.1.0",
            self.validator.EXPECTED_TOP_LEVEL_LABELS["helm.sh/chart"],
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
                    **self.validator.EXPECTED_TOP_LEVEL_LABELS,
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
                    **self.validator.EXPECTED_TOP_LEVEL_LABELS,
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


class PreviewArtifactMetadataTest(unittest.TestCase):
    validator = VALIDATOR

    @staticmethod
    def _metadata_fixture(manifest_path):
        return {
            "schemaVersion": 1,
            "event": "pull_request",
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
                ]

                with (
                    patch.object(self.validator.sys, "argv", argv),
                    patch.object(self.validator.sys, "stderr", stderr),
                ):
                    self.assertEqual(self.validator.main(), 1)

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

    def _tcp_proxy_service(self, spec, *, namespace=None):
        metadata = {
            "name": "tcp-proxy-service",
            "labels": {
                **self.validator.EXPECTED_TOP_LEVEL_LABELS,
                "app.kubernetes.io/instance": "pr-42",
            },
        }
        if namespace is not None:
            metadata["namespace"] = namespace
        return {
            "apiVersion": "v1",
            "kind": "Service",
            "metadata": metadata,
            "spec": spec,
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
            {"ports": [{"name": "tcp-2323", "port": 2323}]}
        )
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.yaml"
            destination = Path(directory) / "destination.yaml"
            source.write_text(yaml.safe_dump(document), encoding="utf-8")

            self.validator.inject_telnet_port(source, destination, 32000, "pr-42")

            prepared = yaml.safe_load(destination.read_text(encoding="utf-8"))
            self.assertEqual(prepared["metadata"]["namespace"], "pr-42")
            self.assertEqual(prepared["spec"]["ports"][0]["nodePort"], 32000)

    def test_expected_top_level_label_mismatches_report_expected_and_actual(self):
        expected_labels = {
            **self.validator.EXPECTED_TOP_LEVEL_LABELS,
            "app.kubernetes.io/instance": "pr-42",
        }
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
                "ports": [
                    {"name": "metrics", "port": 8080},
                    {"name": "tcp-2323", "port": 2323, "nodePort": 32001},
                ]
            },
            namespace="pr-42",
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "prepared.yaml"
            path.write_text(yaml.safe_dump(document), encoding="utf-8")

            self.validator.validate_runtime_target(path, "pr-42", 32001)

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


class PreviewArtifactCommandLineTest(unittest.TestCase):
    validator = VALIDATOR

    def test_subcommand_wrong_arity_reports_subcommand_usage(self):
        cases = (
            (
                "sanitize",
                "usage: validate-preview-artifact.py sanitize <render> <output>\n",
            ),
            (
                "inject",
                (
                    "usage: validate-preview-artifact.py inject "
                    "<render> <output> <namespace> <port>\n"
                ),
            ),
            (
                "runtime-target",
                (
                    "usage: validate-preview-artifact.py runtime-target "
                    "<render> <namespace> <port>\n"
                ),
            ),
        )
        for command, expected_usage in cases:
            stderr = io.StringIO()
            with (
                self.subTest(command=command),
                patch.object(self.validator.sys, "argv", [str(SCRIPT), command]),
                patch.object(self.validator.sys, "stderr", stderr),
            ):
                self.assertEqual(self.validator.main(), 2)
            self.assertEqual(stderr.getvalue(), expected_usage)


class PreviewArtifactConfigMapSanitizerTest(unittest.TestCase):
    validator = VALIDATOR

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

    def test_sanitize_strips_sensitive_tokens_anywhere_case_insensitively(self):
        sanitized = self._sanitize_config_map_data(
            {
                "FIREMUD_POSTGRES_PASSWORD_FILE": "password-value",
                "firemud_minio_secret_key": "secret-key-value",
                "AuthTokenValue": "token-value",
                "TLS_PRIVATE_KEY_PEM": "private-key-value",
                "aws_access_key_id": "access-key-value",
                "database_credential_file": "credential-value",
            }
        )

        self.assertEqual(sanitized, {})

    def test_sanitize_preserves_keys_without_sensitive_tokens(self):
        expected = {
            "FIREMUD_PUBLIC_KEY_URL": "https://example.test/jwks.json",
            "FIREMUD_AUTH_MODE": "preview",
            "FIREMUD_KEYSTORE_PATH": "/var/run/firemud/keystore",
            "IDENTITY_PROVIDER": "workload-identity",
        }

        self.assertEqual(self._sanitize_config_map_data(expected), expected)

    def test_final_validation_accepts_ordinary_data(self):
        document = {
            "apiVersion": "v1",
            "kind": "ConfigMap",
            "metadata": {
                "name": "firemud-config",
                "labels": {
                    **self.validator.EXPECTED_TOP_LEVEL_LABELS,
                    "app.kubernetes.io/instance": "pr-42",
                },
            },
            "data": {"FIREMUD_AUTH_MODE": "preview"},
        }

        self._validate_config_map_manifest(document)

    def test_sanitize_rejects_binary_data(self):
        document = {
            "apiVersion": "v1",
            "kind": "ConfigMap",
            "metadata": {"name": "firemud-config"},
            "data": {"FIREMUD_AUTH_MODE": "preview"},
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
                    **self.validator.EXPECTED_TOP_LEVEL_LABELS,
                    "app.kubernetes.io/instance": "pr-42",
                },
            },
            "data": {"FIREMUD_AUTH_MODE": "preview"},
            "binaryData": {"database_credential_file": "c2VjcmV0"},
        }

        with self.assertRaisesRegex(ValueError, "extra=\\['binaryData'\\]"):
            self._validate_config_map_manifest(document)


if __name__ == "__main__":
    unittest.main()
