"""Shared finite-number validation for observability smoke tooling."""

from __future__ import annotations

import math
from typing import Any

MIN_POSITIVE_SECONDS = 1e-6
MAX_SAFE_SECONDS = 10**12


def is_finite_number(value: Any) -> bool:
    """Return true only for finite int/float values, excluding booleans."""
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return False
    try:
        return math.isfinite(value)
    except OverflowError:
        return False


def is_bounded_positive_seconds(value: Any) -> bool:
    """Return whether seconds fit the shared positive timestamp budget policy."""
    return (
        is_finite_number(value)
        and MIN_POSITIVE_SECONDS <= value <= MAX_SAFE_SECONDS
    )


def parse_bounded_positive_seconds(value: Any, key: str) -> float:
    """Parse a bounded positive seconds value for runner configuration."""
    if isinstance(value, bool):
        raise ValueError(f"{key} must be a positive finite number")  # noqa: TRY004 - one config-validation error contract
    try:
        parsed = float(value)
    except (TypeError, ValueError, OverflowError) as exc:
        raise ValueError(f"{key} must be a positive finite number") from exc
    if not is_bounded_positive_seconds(parsed):
        raise ValueError(f"{key} must be a positive finite number")
    return parsed
