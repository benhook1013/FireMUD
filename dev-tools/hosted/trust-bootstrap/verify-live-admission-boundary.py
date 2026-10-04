#!/usr/bin/env python3
"""Compare the live trust-bootstrap admission specs with checked-in authority."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import subprocess
import sys
from pathlib import Path
from typing import Any

try:
    import yaml
except ImportError:
    print(
        "trust-bootstrap admission verifier: PyYAML is required",
        file=sys.stderr,
    )
    raise SystemExit(1)


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
MANIFESTS = (
    Path("k8s/trust-bootstrap/issuance-admission.yaml"),
    Path("k8s/trust-bootstrap/recovery-admission.yaml"),
    Path("k8s/trust-bootstrap/deployment-admission.yaml"),
)
POLICY_NAMES = (
    "firemud-trust-bootstrap-certificaterequest",
    "firemud-trust-bootstrap-certificaterequest-subresources",
    "firemud-trust-bootstrap-certificate",
    "firemud-trust-bootstrap-certificate-status",
    "firemud-trust-bootstrap-ca-issuers",
    "firemud-trust-ca-secret-boundary",
    "firemud-trust-runtime-namespace-boundary",
    "firemud-trust-runtime-binding-boundary",
)
API_VERSION = "admissionregistration.k8s.io/v1"
POLICY_KIND = "ValidatingAdmissionPolicy"
BINDING_KIND = "ValidatingAdmissionPolicyBinding"
RESOURCE_BY_KIND = {
    POLICY_KIND: "validatingadmissionpolicy",
    BINDING_KIND: "validatingadmissionpolicybinding",
}


class UniqueKeyLoader(yaml.SafeLoader):
    def construct_mapping(self, node: Any, deep: bool = False) -> dict[str, Any]:
        mapping: dict[str, Any] = {}
        for key_node, value_node in node.value:
            key = self.construct_object(key_node, deep=deep)
            if key in mapping:
                raise ValueError("duplicate mapping key")
            mapping[key] = self.construct_object(value_node, deep=deep)
        return mapping


UniqueKeyLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG,
    UniqueKeyLoader.construct_mapping,
)


class ProofError(Exception):
    def __init__(self, result: str, *, kind: str = "", name: str = "") -> None:
        super().__init__(result)
        self.result = result
        self.kind = kind
        self.name = name


def expected_keys() -> set[tuple[str, str]]:
    return {(kind, name) for kind in (POLICY_KIND, BINDING_KIND) for name in POLICY_NAMES}


def load_expected_specs() -> dict[tuple[str, str], dict[str, Any]]:
    expected: dict[tuple[str, str], dict[str, Any]] = {}
    for relative_path in MANIFESTS:
        path = REPOSITORY_ROOT / relative_path
        try:
            documents = yaml.load_all(
                path.read_text(encoding="utf-8"),
                Loader=UniqueKeyLoader,
            )
            for document in documents:
                if not isinstance(document, dict):
                    raise TypeError("manifest document is not a mapping")
                kind = document.get("kind")
                metadata = document.get("metadata")
                spec = document.get("spec")
                if (
                    document.get("apiVersion") != API_VERSION
                    or kind not in RESOURCE_BY_KIND
                    or not isinstance(metadata, dict)
                    or not isinstance(metadata.get("name"), str)
                    or not isinstance(spec, dict)
                ):
                    raise ValueError("manifest admission object is incomplete")
                key = (kind, metadata["name"])
                if key not in expected_keys() or key in expected:
                    raise ValueError("manifest admission object set is invalid")
                expected[key] = spec
        except (OSError, TypeError, ValueError, yaml.YAMLError) as error:
            raise ProofError("source-error", name=relative_path.as_posix()) from error

    if set(expected) != expected_keys():
        raise ProofError("source-object-set-error")
    return expected


def empty_label_selector(value: Any) -> bool:
    if value is None or value == {}:
        return True
    if not isinstance(value, dict):
        return False
    if set(value) - {"matchLabels", "matchExpressions"}:
        return False
    labels = value.get("matchLabels")
    expressions = value.get("matchExpressions")
    return labels in (None, {}) and expressions in (None, [])


def normalize_empty_selectors(kind: str, spec: dict[str, Any]) -> dict[str, Any]:
    normalized = copy.deepcopy(spec)
    parent_name = "matchConstraints" if kind == POLICY_KIND else "matchResources"
    parent = normalized.get(parent_name)
    if isinstance(parent, dict):
        for selector_name in ("namespaceSelector", "objectSelector"):
            if selector_name in parent and empty_label_selector(parent[selector_name]):
                parent.pop(selector_name)
        if kind == BINDING_KIND and not parent:
            normalized.pop(parent_name)
    elif kind == BINDING_KIND and parent is None:
        normalized.pop(parent_name, None)
    return normalized


def digest(value: Any) -> str:
    encoded = json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def read_live_spec(context: str, kind: str, name: str) -> dict[str, Any]:
    resource = RESOURCE_BY_KIND[kind]
    try:
        result = subprocess.run(
            [
                "kubectl",
                "--context",
                context,
                "get",
                resource,
                name,
                "-o",
                "json",
            ],
            capture_output=True,
            check=False,
            text=True,
        )
    except OSError as error:
        raise ProofError("api-unavailable", kind=kind, name=name) from error
    if result.returncode != 0:
        raise ProofError("api-error", kind=kind, name=name)
    try:
        live = json.loads(result.stdout)
    except (json.JSONDecodeError, TypeError) as error:
        raise ProofError("invalid-json", kind=kind, name=name) from error
    if (
        not isinstance(live, dict)
        or live.get("apiVersion") != API_VERSION
        or live.get("kind") != kind
        or not isinstance(live.get("metadata"), dict)
        or live["metadata"].get("name") != name
        or not isinstance(live.get("spec"), dict)
    ):
        raise ProofError("invalid-object", kind=kind, name=name)
    return live["spec"]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--context", required=True)
    arguments = parser.parse_args()
    if not arguments.context.strip():
        print(
            "trust-bootstrap admission verifier: context is required",
            file=sys.stderr,
        )
        return 1

    try:
        expected = load_expected_specs()
    except ProofError as error:
        label = f" source={error.name}" if error.name else ""
        print(
            f"admission-boundary=result={error.result}{label}",
            file=sys.stderr,
        )
        return 1

    combined: list[tuple[str, str, str]] = []
    for kind, name in sorted(expected):
        try:
            live_spec = normalize_empty_selectors(
                kind,
                read_live_spec(arguments.context, kind, name),
            )
        except ProofError as error:
            print(
                f"admission-object={kind}/{name} result={error.result}",
                file=sys.stderr,
            )
            return 1

        expected_spec = normalize_empty_selectors(kind, expected[(kind, name)])
        expected_digest = digest(expected_spec)
        live_digest = digest(live_spec)
        if live_digest != expected_digest:
            print(
                f"admission-object={kind}/{name} result=spec-mismatch "
                f"expected_sha256={expected_digest} observed_sha256={live_digest}",
                file=sys.stderr,
            )
            return 1
        print(f"admission-object={kind}/{name} result=match sha256={live_digest}")
        combined.append((kind, name, live_digest))

    summary = digest(combined)
    print(f"admission-specs=pass objects={len(combined)} sha256={summary}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
