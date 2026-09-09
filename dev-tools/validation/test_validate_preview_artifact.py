#!/usr/bin/env python3
"""Regression tests for trusted preview artifact Secret-reference validation."""

from __future__ import annotations

import copy
import importlib.util
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


class PreviewArtifactSecretReferenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.validator = load_validator()

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


class PreviewArtifactConfigMapSanitizerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.validator = load_validator()

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
