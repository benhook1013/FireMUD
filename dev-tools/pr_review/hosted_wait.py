"""Bounded, read-only wait for one exact Hosted CodeRabbit trigger."""

from __future__ import annotations

import subprocess
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any

from . import github, hosted

DEFAULT_POLL_SECONDS = 30
DEFAULT_MAX_WAIT_SECONDS = 90 * 60


class HostedWaitError(ValueError):
    """The selected trigger is missing or cannot be observed safely."""


def observe_trigger(repo: str, pr: int, trigger_id: int) -> hosted.TriggerState:
    """Classify the exact saved trigger using complete current GitHub history."""

    matches: list[tuple[Path, dict[str, Any]]] = []
    for path in hosted.trigger_record_paths(repo, pr):
        if path.name == "trigger.json":
            reservation = hosted.load_trigger_reservation(path, repo, pr)
            trigger = reservation.get("trigger")
            if not isinstance(trigger, dict):
                # An unresolved current posting reservation has no public
                # trigger yet and cannot be the requested exact trigger.
                continue
            if type(trigger.get("id")) is not int or trigger["id"] != trigger_id:
                continue
        elif path.name != f"trigger-{trigger_id}.json":
            continue
        record = hosted.load_trigger_record(path, repo, pr)
        trigger = record.get("trigger")
        if isinstance(trigger, dict) and type(trigger.get("id")) is int and trigger["id"] == trigger_id:
            matches.append((path, record))
    if len(matches) != 1:
        raise HostedWaitError(
            f"PR #{pr} requires exactly one saved Hosted trigger with ID {trigger_id}; found {len(matches)}"
        )
    path, record = matches[0]
    return hosted.trigger_state(repo, pr, github.fetch_pull_request(repo, pr), record, path)


def wait_for_hosted(
    repo: str,
    pr: int,
    trigger_id: int,
    *,
    poll_seconds: int = DEFAULT_POLL_SECONDS,
    max_wait_seconds: int = DEFAULT_MAX_WAIT_SECONDS,
    observe: Callable[[str, int, int], hosted.TriggerState] = observe_trigger,
    monotonic: Callable[[], float] = time.monotonic,
    sleep: Callable[[float], None] = time.sleep,
    on_change: Callable[[str], None] | None = None,
) -> tuple[dict[str, Any], int]:
    """Return on terminal evidence or deadline; never post, count, or retire a review."""

    if type(pr) is not int or pr <= 0 or type(trigger_id) is not int or trigger_id <= 0:
        raise HostedWaitError("pull request and trigger ID must be positive integers")
    if type(poll_seconds) is not int or not 1 <= poll_seconds <= 120:
        raise HostedWaitError("poll seconds must be between 1 and 120")
    if type(max_wait_seconds) is not int or not 1 <= max_wait_seconds <= 4 * 60 * 60:
        raise HostedWaitError("maximum wait must be between 1 second and 4 hours")
    deadline = monotonic() + max_wait_seconds
    previous_state: str | None = None
    last_observation: dict[str, Any] | None = None
    transient_errors = 0
    while True:
        try:
            state = observe(repo, pr, trigger_id)
        except HostedWaitError:
            raise
        except (OSError, RuntimeError, subprocess.SubprocessError) as error:
            transient_errors += 1
            if transient_errors >= 3:
                raise HostedWaitError(
                    f"Hosted observation failed {transient_errors} times ({type(error).__name__})"
                ) from error
            current = f"observation_unavailable ({type(error).__name__})"
        else:
            transient_errors = 0
            last_observation = state.as_dict()
            current = state.state
            if state.terminal:
                return {
                    "pr": pr,
                    "trigger_id": trigger_id,
                    "status": current,
                    "observation": last_observation,
                }, 0 if current == "completed" and state.attributed else 3
        if current != previous_state and on_change is not None:
            on_change(current)
        previous_state = current
        remaining = deadline - monotonic()
        if remaining <= 0:
            return {
                "pr": pr,
                "trigger_id": trigger_id,
                "status": "wait_expired",
                "observation": last_observation,
            }, 4
        sleep(min(poll_seconds, remaining))
