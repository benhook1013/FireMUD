"""Canonical evidence-record digest shared by deployment and validation gates."""

from __future__ import annotations

import hashlib
import json
from typing import Any


def canonical_evidence_digest(record: dict[str, Any]) -> str:
    """Hash a selected evidence record with the documented RFC 8785 subset."""
    payload = {key: value for key, value in record.items() if key != "immutableArtifactId"}

    def encode(value: Any) -> str:
        if value is None:
            return "null"
        if isinstance(value, bool):
            return "true" if value else "false"
        if isinstance(value, str):
            # Lone surrogates are intentionally rejected: RFC 8785 canonical input must be valid Unicode/UTF-8.
            value.encode("utf-8")
            return json.dumps(value, ensure_ascii=False)
        if isinstance(value, int):
            if abs(value) > 9_007_199_254_740_991:
                raise TypeError("integers outside the RFC 8785 interoperable range are not allowed")
            return str(value)
        if isinstance(value, float):
            raise TypeError("floating-point numbers are not allowed in evidence records")
        if isinstance(value, dict):
            if not all(isinstance(key, str) for key in value):
                raise TypeError("evidence object keys must be strings")
            keys = sorted(value, key=lambda key: key.encode("utf-16-be"))
            return "{" + ",".join(f"{encode(key)}:{encode(value[key])}" for key in keys) + "}"
        if isinstance(value, list):
            return "[" + ",".join(encode(nested) for nested in value) + "]"
        raise TypeError(f"unsupported evidence value type: {type(value).__name__}")

    encoded = encode(payload).encode("utf-8")
    return f"sha256:{hashlib.sha256(encoded).hexdigest()}"
