#!/usr/bin/env python3
"""Resolve the strict hosted certificate-identity mode from Helm values."""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

import yaml

ALLOWED_MODES = frozenset({"standalone", "hosted-controller"})


class UniqueKeyLoader(yaml.SafeLoader):
    """Safe YAML loader that rejects duplicate mapping keys."""


def _construct_unique_mapping(
    loader: UniqueKeyLoader, node: yaml.MappingNode, deep: bool = False
) -> dict[Any, Any]:
    loader.flatten_mapping(node)
    result: dict[Any, Any] = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        try:
            duplicate = key in result
        except TypeError as exc:
            raise yaml.constructor.ConstructorError(
                "while constructing a mapping",
                node.start_mark,
                "found an unhashable mapping key",
                key_node.start_mark,
            ) from exc
        if duplicate:
            raise yaml.constructor.ConstructorError(
                "while constructing a mapping",
                node.start_mark,
                f"found duplicate key {key!r}",
                key_node.start_mark,
            )
        result[key] = loader.construct_object(value_node, deep=deep)
    return result


UniqueKeyLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG,
    _construct_unique_mapping,
)


def resolve_mode(document: object) -> str:
    if document is None:
        return "standalone"
    if not isinstance(document, dict):
        raise TypeError("Helm values root must be a mapping")

    if "previewStack" not in document:
        return "standalone"
    preview_stack = document["previewStack"]
    if not isinstance(preview_stack, dict):
        raise TypeError("previewStack must be a mapping")

    if "certificateIdentity" not in preview_stack:
        return "standalone"
    certificate_identity = preview_stack["certificateIdentity"]
    if not isinstance(certificate_identity, dict):
        raise TypeError("previewStack.certificateIdentity must be a mapping")

    if "mode" not in certificate_identity:
        return "standalone"
    mode = certificate_identity["mode"]
    if not isinstance(mode, str):
        raise TypeError("previewStack.certificateIdentity.mode must be a string")
    if mode not in ALLOWED_MODES:
        raise ValueError(
            "previewStack.certificateIdentity.mode must be standalone or "
            f"hosted-controller (got {mode!r})"
        )
    return mode


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {Path(sys.argv[0]).name} <values.yaml>", file=sys.stderr)
        return 2
    values_path = Path(sys.argv[1])
    try:
        document = yaml.load(values_path.read_text(encoding="utf-8"), Loader=UniqueKeyLoader)
        print(resolve_mode(document))
    except (OSError, TypeError, UnicodeError, ValueError, yaml.YAMLError) as exc:
        print(f"invalid certificate identity mode configuration: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
