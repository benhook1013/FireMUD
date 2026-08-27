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

# Canonical serialized player-flow canary identity contract.  The producer and
# retained-evidence validator import this immutable value rather than keeping
# independent copies of the schema.
CANARY_IDENTITY_REQUIRED_FIELDS = frozenset(
    {
        "authority",
        "classification",
        "analyticsSloExclusion",
        "credentials",
        "transportCharacters",
        "evidenceRef",
    }
)

# No authoritative Account-owned synthetic identity verifier is shipped yet.
# Consumers import this default into their own module namespace so their
# existing test seams can monkeypatch availability independently.
AUTHORITATIVE_CANARY_IDENTITY_VERIFIER_AVAILABLE = False
