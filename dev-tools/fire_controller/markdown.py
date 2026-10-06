"""Batched advisory lint via the checkout's established markdownlint dependency."""
from __future__ import annotations

import json
import subprocess
from pathlib import Path


def diagnostics(strings: dict[str, str]) -> list[dict]:
    """Lint submitted Markdown fields once; failures precede the caller's write.

    Findings are advisory. No formatting, custom lint rules, network requests,
    or persistent helper processes are used. Empty submissions need no parser.
    """
    if not isinstance(strings, dict) or any(not isinstance(key, str) or not isinstance(value, str)
                                           for key, value in strings.items()):
        raise ValueError("Markdown precheck requires text fields")
    strings = {key: value for key, value in strings.items() if value}
    if not strings:
        return []
    try:
        result = subprocess.run(["node", str(Path(__file__).with_name("markdownlint.mjs"))],
                                input=json.dumps(strings), capture_output=True, text=True,
                                timeout=30, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ValueError("Markdown precheck unavailable; prepare this checkout's pinned Node dependencies") from error
    if result.returncode:
        raise ValueError("Markdown precheck failed; run npm ci --ignore-scripts --no-audit --no-fund in this checkout's config/openapi with the pinned Node toolchain")
    try:
        warnings = json.loads(result.stdout)
        if not isinstance(warnings, list) or any(not isinstance(item, dict) for item in warnings):
            raise ValueError("invalid diagnostics")
    except (ValueError, TypeError) as error:
        raise ValueError("Markdown precheck returned invalid diagnostics") from error
    return warnings
