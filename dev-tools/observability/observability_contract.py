"""Shared serialized observability contract vocabulary."""

from __future__ import annotations

QUERYABILITY_CAPABILITIES = frozenset(
    {
        "indexed-log-observability",
        "console-journal-log-observability",
        "log-queryability-omitted",
    }
)
OMITTED_QUERYABILITY_CAPABILITY = "log-queryability-omitted"
