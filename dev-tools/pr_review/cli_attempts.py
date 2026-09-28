"""Read bounded, non-counting CLI attempts from existing private captures."""

from __future__ import annotations

import json
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

RUN_NAME = re.compile(r"run\.[0-9a-f]{32}\Z")
MAX_METADATA_BYTES = 16_384
MAX_ATTEMPTS = 5
MAX_EXIT_STATUS_BYTES = 33
MAX_STDERR_BYTES = 4096


def failed_attempts(database: Path, pr: int) -> dict[str, Any]:
    """Expose coarse failure reasons without copying provider output or changing review counts."""
    root = database.parent / "pr-review" / "runs"
    if not root.exists():
        return {"available": True, "attempts": []}
    try:
        directories = list(root.iterdir())
    except OSError:
        return {"available": False, "attempts": []}
    attempts = []
    for directory in directories:
        if not RUN_NAME.fullmatch(directory.name) or directory.is_symlink() or not directory.is_dir():
            continue
        metadata_path = directory / "metadata.json"
        try:
            if metadata_path.is_symlink():
                continue
            with metadata_path.open("rb") as metadata_file:
                metadata_bytes = metadata_file.read(MAX_METADATA_BYTES + 1)
            if len(metadata_bytes) > MAX_METADATA_BYTES:
                continue
            metadata = json.loads(metadata_bytes.decode("utf-8"))
            if not isinstance(metadata, dict) or str(metadata.get("pull_request")) != str(pr):
                continue
            exit_path = directory / "exit-status"
            stderr_path = directory / "stderr"
            error_path = directory / "error"
            if any(path.is_symlink() for path in (exit_path, stderr_path, error_path)):
                continue
            if exit_path.exists():
                with exit_path.open("rb") as exit_file:
                    exit_status = exit_file.read(MAX_EXIT_STATUS_BYTES).decode("utf-8")[:32].strip()
                if exit_status == "0":
                    continue
                when = exit_path.stat().st_mtime
                if exit_status == "timeout":
                    outcome = "timed_out"
                else:
                    if stderr_path.exists():
                        with stderr_path.open("rb") as stderr_file:
                            stderr = stderr_file.read(MAX_STDERR_BYTES).decode("utf-8", errors="replace")
                    else:
                        stderr = ""
                    outcome = "rate_limited" if "rate limit exceeded" in stderr.casefold() else "provider_failed"
            elif error_path.exists():
                when = error_path.stat().st_mtime
                outcome = "setup_failed"
            else:
                continue
        except (OSError, ValueError, UnicodeError):
            continue
        attempts.append({
            "run_id": directory.name,
            "finished_at": datetime.fromtimestamp(when, timezone.utc).isoformat().replace("+00:00", "Z"),
            "outcome": outcome,
        })
    attempts.sort(key=lambda item: (item["finished_at"], item["run_id"]), reverse=True)
    return {"available": True, "attempts": attempts[:MAX_ATTEMPTS]}
