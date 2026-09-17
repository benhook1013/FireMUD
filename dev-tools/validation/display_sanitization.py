"""Shared rendering safeguards for provider-controlled report text."""

from __future__ import annotations

import re
from typing import Any

# Remove terminal controls and invisible formatting characters that can alter
# the apparent structure or direction of a human-readable report.
TERMINAL_CONTROLS = re.compile(
    r"[\x00-\x1f\x7f-\x9f\u00ad\u034f\u061c\u180e\u200b-\u200f\u202a-\u202e\u2060-\u2064\u2066-\u206f\ufeff\ufe00-\ufe0f\ufff9-\ufffb]+"
)


def display(value: Any) -> str:
    """Render provider-controlled text without allowing it to alter report layout."""

    text = TERMINAL_CONTROLS.sub(" ", str(value))
    return " ".join(text.split()) or "-"
