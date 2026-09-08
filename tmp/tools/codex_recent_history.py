#!/usr/bin/env python3
"""Read a bounded wall-clock slice from one Codex JSONL session."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from collections import deque
from datetime import datetime, timedelta, timezone
from pathlib import Path


UUID_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
    re.IGNORECASE,
)


def parse_time(value: str) -> datetime:
    if not isinstance(value, str):
        raise argparse.ArgumentTypeError("timestamp must be a string")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise argparse.ArgumentTypeError(f"invalid ISO timestamp: {value}") from error
    if parsed.tzinfo is None:
        raise argparse.ArgumentTypeError("timestamps must include a UTC offset or Z")
    return parsed.astimezone(timezone.utc)


def iso(value: datetime | None) -> str:
    if value is None:
        return "none"
    return value.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def default_roots() -> list[Path]:
    roots: list[Path] = []
    codex_home = os.environ.get("CODEX_HOME")
    if codex_home:
        roots.append(Path(codex_home) / "sessions")
    roots.append(Path.home() / ".codex" / "sessions")
    windows_users = Path("/mnt/c/Users")
    if windows_users.is_dir():
        roots.extend(path / ".codex" / "sessions" for path in windows_users.iterdir())
    return roots


def find_session(thread: str, roots: list[Path]) -> Path:
    suffix = f"-{thread.lower()}.jsonl"
    matches: list[Path] = []
    for root in dict.fromkeys(root.resolve() for root in roots if root.is_dir()):
        for directory, _, filenames in os.walk(root):
            matches.extend(
                Path(directory) / name for name in filenames if name.lower().endswith(suffix)
            )
    matches = sorted(set(matches))
    if not matches:
        raise RuntimeError(f"no session filename exactly matches thread {thread}")
    if len(matches) > 1:
        choices = "\n  ".join(str(path) for path in matches)
        raise RuntimeError(
            "multiple exact session matches; select one with --sessions-root:\n  " + choices
        )
    return matches[0]


def content_text(content: object) -> str:
    if not isinstance(content, list):
        return ""
    parts = []
    for item in content:
        if isinstance(item, dict) and isinstance(item.get("text"), str):
            parts.append(item["text"])
    return "\n".join(part for part in parts if part)


def visible_event(record: dict) -> tuple[str, str, str] | None:
    if record.get("type") != "response_item":
        return None
    payload = record.get("payload")
    if not isinstance(payload, dict) or payload.get("type") != "message":
        return None
    role = payload.get("role")
    if role not in ("user", "assistant"):
        return None
    if payload.get("channel") in ("analysis", "reasoning"):
        return None
    text = content_text(payload.get("content"))
    if not text:
        return None
    return role, text, str(payload.get("id") or "")


def delegation_event(record: dict) -> tuple[str, str, str] | None:
    if record.get("type") != "response_item":
        return None
    payload = record.get("payload")
    if not isinstance(payload, dict) or payload.get("type") != "function_call":
        return None
    name = payload.get("name")
    if name not in ("spawn_agent", "followup_task"):
        return None
    try:
        arguments = json.loads(payload.get("arguments", "{}"))
    except (json.JSONDecodeError, TypeError):
        return None
    if not isinstance(arguments, dict) or not isinstance(arguments.get("message"), str):
        return None
    target = arguments.get("task_name") or arguments.get("target") or "unknown"
    details = [f"target={target}", f"tool={name}"]
    if arguments.get("model"):
        details.append(f"model={arguments['model']}")
    if arguments.get("reasoning_effort"):
        details.append(f"reasoning={arguments['reasoning_effort']}")
    message = arguments["message"]
    if message.startswith("gAAAA"):
        message = "[assignment prompt is encrypted in this session log]"
    return "delegation", message, " ".join(details)


def load_events(
    path: Path, thread: str, since: datetime, until: datetime, limit: int, delegations: bool
) -> tuple[list[tuple[datetime, str, str, str]], datetime | None, datetime | None, int]:
    events: deque[tuple[datetime, str, str, str]] = deque(maxlen=limit)
    newest_record = None
    newest_eligible = None
    malformed = 0
    verified = False
    seen: set[tuple[str, ...]] = set()
    extractor = delegation_event if delegations else visible_event
    with path.open(encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, 1):
            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                malformed += 1
                continue
            if not isinstance(record, dict):
                malformed += 1
                continue
            if record.get("type") == "session_meta":
                payload = record.get("payload", {})
                if str(payload.get("id", "")).lower() == thread.lower():
                    verified = True
            try:
                timestamp = parse_time(record["timestamp"])
            except (KeyError, TypeError, argparse.ArgumentTypeError):
                malformed += 1
                continue
            newest_record = max(newest_record, timestamp) if newest_record else timestamp
            extracted = extractor(record)
            if extracted is None:
                continue
            role, text, extracted_detail = extracted
            newest_eligible = max(newest_eligible, timestamp) if newest_eligible else timestamp
            payload = record.get("payload", {})
            record_id = str(payload.get("id") or payload.get("call_id") or "")
            key = (record_id,) if record_id else (iso(timestamp), role, text)
            if key in seen:
                continue
            seen.add(key)
            if since <= timestamp <= until:
                detail = extracted_detail if delegations else ""
                events.append((timestamp, role, detail, text))
    if not verified:
        raise RuntimeError("session metadata does not exactly match requested thread")
    return list(events), newest_record, newest_eligible, malformed


def render(
    path: Path,
    since: datetime,
    until: datetime,
    events: list[tuple[datetime, str, str, str]],
    newest_record: datetime | None,
    newest_eligible: datetime | None,
    malformed: int,
    max_chars: int,
) -> str:
    header = f"session: {path}\nwindow: {iso(since)} .. {iso(until)}\n"
    if not events:
        body = (
            "no matching events in interval\n"
            f"newest session record: {iso(newest_record)}\n"
            f"newest eligible event: {iso(newest_eligible)}\n"
        )
    else:
        blocks = [f"\n[{iso(ts)}] {role}{(' ' + detail) if detail else ''}\n{text}\n" for ts, role, detail, text in events]
        budget = max_chars - len(header)
        kept: list[str] = []
        for block in reversed(blocks):
            if len(block) <= budget:
                kept.append(block)
                budget -= len(block)
            elif not kept and budget > 40:
                kept.append(block[: budget - 24] + "\n[output truncated]\n")
                budget = 0
            else:
                break
        kept.reverse()
        omitted = len(blocks) - len(kept)
        prefix = f"{omitted} older matching event(s) omitted by --max-chars\n" if omitted else ""
        body = prefix + "".join(kept)
    if malformed:
        body += f"\nwarning: skipped {malformed} malformed JSONL record(s)\n"
    return (header + body)[:max_chars]


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--thread", required=True, help="exact Codex task UUID")
    result.add_argument("--minutes", type=float, default=30, help="minutes before --until (default: 30)")
    result.add_argument("--since", type=parse_time, help="ISO start time; overrides --minutes")
    result.add_argument("--until", type=parse_time, help="ISO end time (default: now UTC)")
    result.add_argument("--limit", type=int, default=50, help="maximum events, keeping newest (default: 50)")
    result.add_argument("--max-chars", type=int, default=20000, help="maximum output characters (default: 20000)")
    result.add_argument("--sessions-root", action="append", type=Path, help="session root to search; repeatable")
    result.add_argument("--delegations", action="store_true", help="show spawn/follow-up assignment prompts only")
    return result


def main() -> int:
    args = parser().parse_args()
    if not UUID_RE.fullmatch(args.thread):
        parser().error("--thread must be a UUID")
    if args.minutes <= 0 or args.limit <= 0 or args.max_chars < 256:
        parser().error("--minutes and --limit must be positive; --max-chars must be at least 256")
    until = args.until or datetime.now(timezone.utc)
    since = args.since or (until - timedelta(minutes=args.minutes))
    if since > until:
        parser().error("--since must not be later than --until")
    try:
        path = find_session(args.thread, args.sessions_root or default_roots())
        events, newest_record, newest_eligible, malformed = load_events(
            path, args.thread, since, until, args.limit, args.delegations
        )
    except (OSError, RuntimeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    print(render(path, since, until, events, newest_record, newest_eligible, malformed, args.max_chars), end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
