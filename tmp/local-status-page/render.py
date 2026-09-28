#!/usr/bin/env python3
"""Render the controller queue with manually maintained programme and lane notes."""

from __future__ import annotations

import argparse
import base64
import hashlib
import html
import json
import os
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta, timezone
from itertools import groupby
from pathlib import Path
from zoneinfo import ZoneInfo

from mast import render_mast
from render_progress import render_current as render_project_map

ROOT = Path(__file__).resolve().parent
SHARED_CSS = (ROOT / "shared.css").read_text(encoding="utf-8")
DEFAULT_INPUT = ROOT / "status.json"
DEFAULT_OUTPUT = ROOT / "output" / "index.html"
ASSET_FILES = (
    "icon-options.html", "flame-ember.svg", "flame-monogram.svg", "flame-crest.svg", "flame-pixel.svg",
)
REPO_URL = "https://github.com/benhook1013/FireMUD/pull/"
REPO_HOME = "https://github.com/benhook1013/FireMUD"
REPO = "benhook1013/FireMUD"
LOCAL_TIMEZONE = ZoneInfo("Pacific/Auckland")
ACTIVE_REVIEW_STATES = frozenset({"ACTIVE", "IN_PROGRESS", "REVIEWING", "RUNNING"})
EXPLICIT_HUMAN_STOP_STATES = frozenset({
    "HUMAN_STOP", "HUMAN_STOPPED", "MANUALLY_STOPPED", "OVERRIDE", "STOPPED_BY_HUMAN",
})
REVIEW_FINISHED_STATES = EXPLICIT_HUMAN_STOP_STATES | {"COMPLETE"}
RECORDS_PREFLIGHT_TIMEOUT_SECONDS = 8
RECORDS_HISTORY_TIMEOUT_SECONDS = 5
MAX_RECORD_HISTORY_BYTES = 512_000
MAX_RECORDS_PER_KIND = 40
MAX_HISTORY_RECORDS = 1000
MAX_RECORD_VALUE_LENGTH = 80
MAX_RECORD_TEXT_LENGTH = 500
MAX_REVIEW_ROUNDS = 100
MAX_REVIEW_DETAIL_RECORD_HTML_CHARS = 250_000
REFRESH_SCRIPT = """(() => {
  const form = document.querySelector('.refresh-form');
  if (!form) return;
  const button = form.querySelector('button');
  const progress = form.querySelector('.refresh-progress');
  let stage = 'rendering';
  let statusPending = false;
  let waitingForOther = false;
  let finishOther;
  const stageLabel = {rendering: 'Loading', publishing: 'Saving'};
  const readStage = async () => {
    if (statusPending) return;
    statusPending = true;
    try {
      const response = await fetch('/refresh-status', {credentials: 'same-origin'});
      if (!response.ok) return;
      const status = await response.json();
      if (waitingForOther) {
        if (['complete', 'render_failed', 'publish_failed', 'idle'].includes(status.phase)) {
          waitingForOther = false;
          finishOther(status.phase);
        }
        return;
      }
      if (status.phase === 'publishing') {
        stage = 'publishing';
        progress.textContent = 'Publishing public status page';
      }
    } catch {
      // A missed progress update does not interrupt the refresh request.
    } finally {
      statusPending = false;
    }
  };
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (button.disabled) return;
    button.disabled = true;
    stage = 'rendering';
    waitingForOther = false;
    form.classList.remove('failed');
    progress.textContent = 'Refreshing local review data';
    form.classList.add('loading');
    const started = Date.now();
    const update = () => {
      button.textContent = `${waitingForOther ? 'Waiting' : stageLabel[stage]}\n${Math.floor((Date.now() - started) / 1000)}s`;
      void readStage();
    };
    update();
    const timer = setInterval(update, 1000);
    try {
      const response = await fetch(form.action, { method: 'POST', credentials: 'same-origin' });
      if (!response.ok) {
        if (response.status === 409) {
          waitingForOther = true;
          progress.textContent = 'Another refresh is running. This page will update when it finishes.';
          const outcome = await new Promise(resolve => { finishOther = resolve; void readStage(); });
          if (outcome === 'complete') {
            progress.textContent = 'Local and public status pages updated';
            window.location.reload();
          } else if (outcome === 'render_failed') {
            form.classList.add('failed');
            progress.textContent = 'The other local refresh failed. The previous pages are still available.';
          } else if (outcome === 'publish_failed') {
            form.classList.add('failed');
            progress.textContent = 'The other refresh updated the local page, but public publishing failed.';
          } else {
            form.classList.add('failed');
            progress.textContent = 'The other refresh ended. You can try again.';
          }
          return;
        }
        form.classList.add('failed');
        progress.textContent = response.status === 429
          ? 'Refresh recently completed. Try again shortly.'
          : response.status === 502
          ? 'Local refresh failed. The previous local and public pages are still available.'
          : response.status === 503
          ? 'Local page updated, but public publishing failed. Try again shortly.'
          : `Refresh failed (${response.status}). Try again shortly.`;
        return;
      }
      progress.textContent = 'Local and public status pages updated';
      window.location.reload();
    } catch {
      form.classList.add('failed');
      progress.textContent = 'Refresh connection failed. Check both pages before trying again.';
    } finally {
      clearInterval(timer);
      form.classList.remove('loading');
      button.disabled = false;
      button.textContent = 'Refresh';
    }
  });
})();"""

AGE_SCRIPT = """(() => {
  const labels = document.querySelectorAll('.relative-age');
  const rounds = document.querySelectorAll('.round-age[datetime]');
  const roundAge = (timestamp, now) => {
    if (!Number.isFinite(timestamp) || timestamp > now) return '?';
    const minutes = Math.floor((now - timestamp) / 60000);
    if (minutes === 0) return '<1m';
    if (minutes < 100) return `${minutes}m`;
    if (minutes < 6000) return `${Math.floor(minutes / 60)}h`;
    const days = Math.floor(minutes / 1440);
    return days < 100 ? `${days}d` : '99d+';
  };
  const update = () => {
    const now = Date.now();
    for (const label of labels) {
      const timestamp = Date.parse(label.dateTime);
      if (Number.isNaN(timestamp)) continue;
      const minutes = Math.max(0, Math.floor((now - timestamp) / 60000));
      label.textContent = minutes === 0 ? 'just now'
        : minutes < 60 ? `${minutes}m ago`
        : minutes < 1440 ? `${Math.floor(minutes / 60)}h ${minutes % 60}m ago`
        : `${Math.floor(minutes / 1440)}d ${Math.floor((minutes % 1440) / 60)}h ago`;
    }
    for (const round of rounds) {
      round.textContent = roundAge(Date.parse(round.dateTime), now);
    }
  };
  update();
  setInterval(update, 30000);
})();"""

SNAPSHOT_SCRIPT = """(() => {
  const version = document.querySelector('meta[name="status-snapshot"]')?.content;
  if (!version) return;
  const scrollKey = 'firemud-status-scroll-y';
  try {
    const saved = sessionStorage.getItem(scrollKey);
    if (saved !== null) {
      sessionStorage.removeItem(scrollKey);
      requestAnimationFrame(() => window.scrollTo(0, Number(saved) || 0));
    }
  } catch {
    // The page still updates when browser storage is unavailable.
  }
  let checking = false;
  const check = async () => {
    if (checking || document.visibilityState === 'hidden'
        || document.querySelector('.refresh-form.loading')) return;
    checking = true;
    try {
      const response = await fetch(window.location.pathname || '/', {cache: 'no-store'});
      if (!response.ok) return;
      const source = await response.text();
      const next = source.match(/<meta name="status-snapshot" content="([^"]+)">/)?.[1];
      if (!next || !(Date.parse(next) > Date.parse(version))) return;
      try {
        sessionStorage.setItem(scrollKey, String(window.scrollY));
      } catch {
        // Reload without restoring scroll when browser storage is unavailable.
      }
      window.location.reload();
    } catch {
      // A missed snapshot check is retried on the next interval.
    } finally {
      checking = false;
    }
  };
  setInterval(check, 120000);
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') void check();
  });
})();"""


def utc(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("timestamp requires a timezone")
    return parsed.astimezone(timezone.utc)


def safe(value: object) -> str:
    return html.escape(str(value), quote=True)


def channel_label(channel: str, state: str, pr: int, targets: dict) -> str:
    """Describe review-request availability without implying merge readiness."""
    name = "Hosted" if channel == "hosted" else "CLI"
    if state == "READY":
        target = targets.get(channel, {}) if isinstance(targets, dict) else {}
        if not isinstance(target, dict):
            target = {}
        if target.get("pr") == pr and target.get("status") == "READY":
            detail = "ready to request"
        elif target:
            detail = (
                "waiting its turn"
                if target.get("pr") is not None and target.get("pr") != pr and target.get("status") != "UNKNOWN"
                else "request status unknown"
            )
        else:
            detail = "request status unknown"
    else:
        detail = {
            "RATE_LIMITED": "cooldown active",
            "HELD": "new request blocked",
            "HUMAN_STOPPED": "human bypass",
            "OVERRIDE": "human bypass",
            "HUMAN_STOP": "human bypass",
            "MANUALLY_STOPPED": "human bypass",
            "STOPPED_BY_HUMAN": "human bypass",
            "COMPLETE": "review complete",
            "ALLOCATION_EXHAUSTED": "review allowance used",
            "OVER_CEILING": "file limit",
            "PARENT_MOVED": "parent changed",
            "UNRECONCILED": "needs reconciliation",
            "MISSING_EVIDENCE": "needs evidence",
            "JUDGMENT_REQUIRED": "needs decision",
            "UNSTABLE": "evidence unclear",
            "NOT_CHECKED": "not checked",
        }.get(state, state.replace("_", " ").lower())
    return f"{name} {detail}"


def held_request_reason(review: dict, channel: str, pr: int, state: str) -> str | None:
    """Return a specific selected-target hold reason when the controller provides one."""
    if state != "HELD":
        return None
    targets = review.get("review_targets", {})
    target = targets.get(channel, {}) if isinstance(targets, dict) else {}
    if not isinstance(target, dict) or target.get("pr") != pr or target.get("status") != "HELD":
        return None
    reason = target.get("reason")
    if not isinstance(reason, str) or not reason.strip() or reason.strip().casefold() == f"{pr} is held":
        return None
    return reason.strip()


def local_time(value: datetime) -> str:
    local = value.astimezone(LOCAL_TIMEZONE)
    return f"{local.day} {local.strftime('%b %H:%M %Z')}"


def relative_time(value: datetime, now: datetime) -> str:
    minutes = max(0, int((now - value).total_seconds() // 60))
    if minutes == 0:
        return "just now"
    if minutes < 60:
        return f"{minutes}m ago"
    if minutes < 24 * 60:
        return f"{minutes // 60}h {minutes % 60}m ago"
    days, remaining = divmod(minutes, 24 * 60)
    return f"{days}d {remaining // 60}h ago"


def round_age(value: datetime, now: datetime) -> str:
    if value > now:
        return "?"
    minutes = int((now - value).total_seconds() // 60)
    if minutes == 0:
        return "<1m"
    if minutes < 100:
        return f"{minutes}m"
    if minutes < 6000:
        return f"{minutes // 60}h"
    days = minutes // 1440
    return f"{days}d" if days < 100 else "99d+"


def lane_items(value: object) -> list[str]:
    """Normalize a lane summary or optional detail into short display items."""
    if isinstance(value, str):
        return [value] if value.strip() else []
    if isinstance(value, list) and all(isinstance(item, str) for item in value):
        return [item for item in value if item.strip()]
    raise ValueError("lane summary fields must be text or lists of text")


def round_completion(value: object, now: datetime) -> datetime | None:
    if not isinstance(value, str):
        return None
    try:
        completed = utc(value)
    except (ValueError, OverflowError):
        return None
    return completed if completed <= now else None


def time_label(value: str, now: datetime, subject: str = "Manual status") -> str:
    observed = utc(value)
    age = now - observed
    if age < timedelta(minutes=-5):
        return f"{safe(subject)} future-dated"
    if age > timedelta(hours=24):
        return f"{safe(subject)} stale · checked {relative_time(observed, now)}"
    return f"{safe(subject)} checked {relative_time(observed, now)}"


def review_snapshot(tool: Path | None, pr: int, expected_head: str, now: datetime) -> dict:
    source = "Repository PR-review controller"
    unavailable = {"available": False, "source": source, "as_of": now.isoformat(), "reason": "No review-status tool configured"}
    if tool is None:
        return unavailable
    try:
        run = subprocess.run(
            [sys.executable, str(tool), "status", "--json"],
            cwd=tool.parent.parent,
            capture_output=True,
            text=True,
            timeout=120,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        return {**unavailable, "reason": f"Status fetch unavailable ({type(exc).__name__})"}
    if run.returncode != 0:
        reason = (
            "Local controller private-state schema mismatch"
            if "state contains fields outside the private configuration schema" in run.stderr
            else f"Status fetch failed (exit {run.returncode})"
        )
        return {**unavailable, "reason": reason}
    try:
        report = json.loads(run.stdout)
        if not isinstance(report["prs"], list):
            raise ValueError("invalid review stack")
        ordered_prs = report["ordered_prs"]
        if (not isinstance(ordered_prs, list)
                or any(type(number) is not int for number in ordered_prs)
                or len(ordered_prs) != len(set(ordered_prs))):
            raise ValueError("invalid review queue order")
        queue = {}
        for item in report["prs"]:
            number = item["pr"]
            if type(number) is not int or number in queue or not isinstance(item["head"], str):
                raise ValueError("invalid review stack record")
            queue[number] = item
        if pr not in queue:
            raise ValueError("review front missing from stack")
        if set(ordered_prs) != set(queue):
            raise ValueError("review queue and status records disagree")
        live_head = queue[pr]["head"]
        return {
            "available": True,
            "source": source,
            "as_of": now.isoformat(),
            "head": live_head,
            "saved_head_stale": live_head != expected_head,
            "mode": report.get("mode"),
            "controller_status": report.get("status"),
            "detail_window": report.get("detail_window", {}),
            "review_targets": report.get("review_targets", {}),
            "queue": queue,
            "ordered_prs": ordered_prs,
        }
    except (ValueError, KeyError, TypeError, AttributeError):
        return {**unavailable, "reason": "Status fetch malformed or review front missing"}


def github_stages(now: datetime) -> dict:
    """Fetch live GitHub PR stage and diff size once for the open queue."""
    try:
        run = subprocess.run(
            ["gh", "pr", "list", "--repo", REPO, "--state", "all", "--limit", "200", "--json", "number,state,isDraft,mergedAt,changedFiles,additions,deletions,title,baseRefName,headRefOid"],
            capture_output=True,
            text=True,
            timeout=20,
            check=False,
        )
        if run.returncode != 0:
            raise ValueError("GitHub query failed")
        items = json.loads(run.stdout)
        if not isinstance(items, list):
            raise ValueError("GitHub query returned no PR list")
        states = {}
        lifecycle = {}
        merged_at = {}
        stats = {}
        identity = {}
        for item in items:
            if (type(item["number"]) is not int or type(item["isDraft"]) is not bool
                    or item["state"] not in ("OPEN", "MERGED", "CLOSED")):
                raise ValueError("GitHub query returned an invalid PR stage")
            states[item["number"]] = item["isDraft"]
            lifecycle[item["number"]] = item["state"]
            if (isinstance(item.get("title"), str) and item["title"].strip()
                    and isinstance(item.get("baseRefName"), str) and item["baseRefName"]
                    and isinstance(item.get("headRefOid"), str) and item["headRefOid"]):
                identity[item["number"]] = {
                    "title": item["title"], "base": item["baseRefName"], "head": item["headRefOid"],
                }
            if item["state"] == "MERGED" and isinstance(item.get("mergedAt"), str):
                merged_at[item["number"]] = item["mergedAt"]
            if all(type(item.get(key)) is int and item[key] >= 0 for key in ("changedFiles", "additions", "deletions")):
                stats[item["number"]] = {key: item[key] for key in ("changedFiles", "additions", "deletions")}
        return {"available": True, "as_of": now.isoformat(), "states": states, "lifecycle": lifecycle, "merged_at": merged_at, "stats": stats, "identity": identity}
    except (OSError, subprocess.TimeoutExpired, ValueError, KeyError, TypeError):
        return {"available": False, "as_of": now.isoformat(), "states": {}, "lifecycle": {}, "merged_at": {}, "stats": {}, "identity": {}}


def controller_stack(data: dict, review: dict, github: dict, now: datetime) -> dict:
    """Use the live queue for PR order while retaining manual programme labels."""
    if not review["available"] or not github["available"]:
        return data
    saved = {item["number"]: item for item in data["stack"]}
    ordered = review["ordered_prs"]
    identities = github.get("identity", {})
    if not ordered or any(number not in identities for number in ordered):
        raise ValueError("GitHub identities missing for a configured PR; existing page preserved")
    entries = []
    for position, number in enumerate(ordered):
        old = saved.get(number)
        stage = old["stage"] if old else (
            entries[-1]["stage"] if entries else
            next((saved[later]["stage"] for later in ordered[position + 1:] if later in saved), "Review queue")
        )
        entries.append({"number": number, **identities[number], "stage": stage,
                        "verified_at": now.isoformat()})
    return {**data, "stack": entries}


def selected_review_front(data: dict, review: dict, github: dict) -> int | None:
    """Prefer the controller's next channel target; never feature a closed review."""
    manual = data["review_front"]
    if not review["available"] or not github.get("available"):
        return manual
    ordered = [item["number"] for item in data["stack"]]
    queue = review.get("queue", {})
    lifecycle = github.get("lifecycle", {})

    def unfinished(number: int) -> bool:
        channels = queue.get(number, {}).get("channels", {})
        return (lifecycle.get(number) == "OPEN" and isinstance(channels, dict)
                and any(channels.get(channel) not in REVIEW_FINISHED_STATES
                        for channel in ("hosted", "cli")))

    targets = review.get("review_targets", {})
    selected = set()
    if isinstance(targets, dict):
        for channel in ("hosted", "cli"):
            target = targets.get(channel, {})
            if isinstance(target, dict) and type(target.get("pr")) is int:
                number = target["pr"]
                channels = queue.get(number, {}).get("channels", {})
                if (number in ordered and unfinished(number) and isinstance(channels, dict)
                        and channels.get(channel) not in REVIEW_FINISHED_STATES):
                    selected.add(number)
    if selected:
        return next(number for number in ordered if number in selected)
    if manual in ordered and unfinished(manual):
        return manual
    return next((number for number in ordered if unfinished(number)), None)


def records_history_snapshots(tool: Path | None, prs: list[int]) -> dict[int, dict]:
    """Fetch records only after one read-only SQLite compatibility preflight."""
    unavailable = {number: {"state": "unavailable", "reason": "Records history is unavailable"}
                   for number in prs}
    if tool is None or not prs:
        return unavailable
    try:
        preflight = subprocess.run(
            [sys.executable, str(tool), "state", "status", "--json"],
            cwd=tool.parent.parent,
            capture_output=True,
            text=True,
            timeout=RECORDS_PREFLIGHT_TIMEOUT_SECONDS,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return unavailable
    if preflight.returncode != 0 or len(preflight.stdout.encode("utf-8")) > MAX_RECORD_HISTORY_BYTES:
        return unavailable
    try:
        state = json.loads(preflight.stdout)
    except (ValueError, TypeError):
        return unavailable
    if not (isinstance(state, dict) and state.get("format") == "sqlite"
            and state.get("compatible") is True and state.get("read_only") is True
            and state.get("bootstrapped", True) is True):
        return unavailable

    def fetch(number: int) -> tuple[int, dict]:
        try:
            result = subprocess.run(
                [sys.executable, str(tool), "records", "history", "--pr", str(number)],
                cwd=tool.parent.parent,
                capture_output=True,
                text=True,
                timeout=RECORDS_HISTORY_TIMEOUT_SECONDS,
                check=False,
            )
        except (OSError, subprocess.SubprocessError):
            return number, {"state": "unavailable", "reason": "History read failed"}
        if result.returncode != 0 or len(result.stdout.encode("utf-8")) > MAX_RECORD_HISTORY_BYTES:
            return number, {"state": "unavailable", "reason": "History read failed"}
        try:
            document = json.loads(result.stdout)
            payload = document["result"]
            if (document.get("api_version") != 1 or not isinstance(payload, dict)
                    or payload.get("pr") != number):
                raise ValueError("history envelope mismatch")
            records = {key: payload[key] for key in ("runs", "findings", "routes", "decisions")}
            if any(not isinstance(value, list) or len(value) > MAX_HISTORY_RECORDS
                   or any(not isinstance(record, dict) for record in value)
                   for value in records.values()):
                raise ValueError("history records malformed or oversized")
        except (ValueError, TypeError, KeyError, AttributeError):
            return number, {"state": "unavailable", "reason": "History response is malformed"}
        state_name = "empty" if not any(records.values()) else "available"
        return number, {"state": state_name, **records}

    snapshots: dict[int, dict] = {}
    with ThreadPoolExecutor(max_workers=min(8, len(prs))) as pool:
        futures = [pool.submit(fetch, number) for number in prs]
        for future in as_completed(futures):
            number, snapshot = future.result()
            snapshots[number] = snapshot
    return snapshots


def _bounded_category(value: object, fallback: str = "Unspecified") -> str:
    if not isinstance(value, str):
        return fallback
    category = value.strip()
    if not category or len(category) > MAX_RECORD_VALUE_LENGTH:
        return fallback
    if any(not (character.isalnum() or character in " _.-") for character in category):
        return fallback
    return safe(category.replace("_", " "))


def _record_text(value: object) -> str:
    if not isinstance(value, str):
        return ""
    text = value.strip()
    if len(text) > MAX_RECORD_TEXT_LENGTH:
        text = text[:MAX_RECORD_TEXT_LENGTH].rstrip() + "…"
    return safe(text)


def _run_source(run: dict) -> str:
    source = run.get("source", run.get("channel", run.get("source_channel", "")))
    if not isinstance(source, str):
        source = ""
    normalized = source.casefold().replace("_", "-").replace(" ", "-")
    if "subagent" in normalized or "sub-agent" in normalized:
        return "Subagent pre-review"
    if "manual" in normalized:
        return "Manual pre-review"
    if "hosted" in normalized:
        return "Hosted review"
    if normalized == "cli" or normalized.startswith("cli-"):
        return "CLI review"
    return "Other review source"


def _run_counts(run: dict) -> str:
    counts = run.get("counts", {})
    if not isinstance(counts, dict):
        counts = {}
    labels = (("found", "found"), ("raw", "found"), ("accepted", "accepted"),
              ("useful", "accepted"), ("routed", "routed"))
    shown = []
    used = set()
    for key, label in labels:
        value = counts.get(key, run.get(key))
        if type(value) is int and value >= 0 and label not in used:
            shown.append(f'<span>{label}: {value}</span>')
            used.add(label)
    return " ".join(shown) or "Counts unavailable"


def _decision_rows(records: list[dict]) -> str:
    rows = []
    for index, record in enumerate(records[:MAX_RECORDS_PER_KIND], 1):
        decision = _bounded_category(record.get("decision", record.get("disposition")))
        reason = _record_text(record.get("reason"))
        response = _record_text(record.get("response", record.get("response_text", record.get("proof_or_reason"))))
        content = f'<li>{decision}{_history_source_label(record)}'
        if reason:
            content += f'<p>{reason}</p>'
        if response:
            content += f'<p>{response}</p>'
        rows.append(content + "</li>")
    if len(records) > MAX_RECORDS_PER_KIND:
        rows.append("<li>Additional records omitted from this bounded page.</li>")
    return "".join(rows)


def _history_source_label(record: dict) -> str:
    if "source" not in record and "channel" not in record:
        if "source_channel" not in record:
            return ""
    return f' <span class="record-source">({_run_source(record)})</span>'


def _render_finding(record: dict, index: int, decisions: list[dict], route: dict | None) -> str:
    disposition = _bounded_category(record.get("disposition"))
    title = _record_text(record.get("title"))
    detail = _record_text(record.get("detail"))
    content = f'<strong>{title}</strong>' if title else f'Finding {index}'
    if detail:
        content += f'<p>{detail}</p>'
    content += f' · {disposition}{_history_source_label(record)}'
    if route is not None:
        content += f'<div class="linked-record">{_render_route(route)}</div>'
    if decisions:
        content += f'<ul class="linked-record">{_decision_rows(decisions)}</ul>'
    return f'<li>{content}</li>'


def _render_route(route: dict) -> str:
    source_pr = route.get("source_pr")
    target_pr = route.get("target_pr")
    source = f'PR #{source_pr}' if type(source_pr) is int and source_pr > 0 else "Unknown source"
    target = f'PR #{target_pr}' if type(target_pr) is int and target_pr > 0 else "Unassigned target"
    status = _bounded_category(route.get("status"))
    parts = [f'<strong>Route</strong> · {source} → {target} · {status}']
    target_history = route.get("target_history", [])
    if not isinstance(target_history, list):
        target_history = []
    for target_change in target_history[:MAX_RECORDS_PER_KIND]:
        if not isinstance(target_change, dict):
            continue
        previous_target = target_change.get("target_pr")
        previous = (f'PR #{previous_target}'
                    if type(previous_target) is int and previous_target > 0 else "Unassigned")
        reason = _record_text(target_change.get("reason"))
        parts.append(f'<span class="record-counts">Target history: {previous}{": " + reason if reason else ""}</span>')
    if route.get("disposition"):
        parts.append(f'<span class="record-counts">Disposition: {_record_text(route.get("disposition"))}</span>')
    if route.get("proof"):
        parts.append(f'<span class="record-counts">Proof: {_record_text(route.get("proof"))}</span>')
    resolutions = route.get("resolutions", [])
    if not isinstance(resolutions, list):
        resolutions = []
    for resolution in resolutions[:MAX_RECORDS_PER_KIND]:
        if isinstance(resolution, dict):
            outcome = _bounded_category(resolution.get("outcome"))
            proof = _record_text(resolution.get("proof_or_reason"))
            parts.append(f'<span class="record-counts">Resolution: {outcome}{": " + proof if proof else ""}</span>')
    nested_decisions = route.get("decisions", [])
    if isinstance(nested_decisions, list) and nested_decisions:
        parts.append(f'<ul class="linked-record">{_decision_rows(nested_decisions)}</ul>')
    return " ".join(parts)


def render_record_sections(history: dict) -> str:
    runs = history.get("runs", [])
    findings = history.get("findings", [])
    routes = history.get("routes", [])
    decisions = history.get("decisions", [])
    decisions_by_route: dict[object, list[dict]] = {}
    for decision in decisions:
        route_id = decision.get("route_id")
        if route_id:
            decisions_by_route.setdefault(route_id, []).append(decision)
    expanded_routes = []
    for route in routes:
        expanded = dict(route)
        nested_value = expanded.get("decisions", [])
        nested = list(nested_value) if isinstance(nested_value, list) else []
        existing_ids = {decision.get("decision_id") for decision in nested}
        nested.extend(decision for decision in decisions_by_route.get(route.get("route_id"), [])
                      if decision.get("decision_id") not in existing_ids)
        expanded["decisions"] = nested
        expanded_routes.append(expanded)
    routes_by_id = {route.get("route_id"): route for route in expanded_routes if route.get("route_id")}
    run_sections = []
    attached_decisions = set()
    attached_findings = set()
    for run in runs[:MAX_RECORDS_PER_KIND]:
        run_id = run.get("run_id")
        run_findings = [(index, finding) for index, finding in enumerate(findings, 1)
                        if finding.get("run_id") == run_id]
        finding_ids = {finding.get("finding_id") for _, finding in run_findings}
        run_decisions = [decision for decision in decisions
                         if decision.get("run_id") == run_id and decision.get("finding_id") not in finding_ids]
        rendered_findings = []
        for index, finding in run_findings[:MAX_RECORDS_PER_KIND]:
            finding_id = finding.get("finding_id")
            attached_findings.add(finding_id)
            finding_decisions = [decision for decision in decisions
                                 if decision.get("run_id") == run_id and decision.get("finding_id") == finding_id]
            attached_decisions.update(decision.get("decision_id", id(decision)) for decision in finding_decisions)
            route = routes_by_id.get(finding.get("route_id"))
            rendered_findings.append(_render_finding(finding, index, finding_decisions, route))
        attached_decisions.update(decision.get("decision_id", id(decision)) for decision in run_decisions)
        outcome = _bounded_category(run.get("outcome"))
        run_header = (
            f'<strong>{_run_source(run)}</strong> · {outcome}'
            f'<span class="record-counts">{_run_counts(run)}</span>'
        )
        findings_list = "".join(rendered_findings) or "<li>No findings recorded for this run.</li>"
        decision_list = _decision_rows(run_decisions)
        if decision_list:
            findings_list += decision_list
        run_sections.append(
            f'<li>{run_header}<ol class="history-list">{findings_list}</ol></li>'
        )
    if len(runs) > MAX_RECORDS_PER_KIND:
        run_sections.append("<li>Additional runs omitted from this bounded page.</li>")
    for route in expanded_routes:
        nested = route.get("decisions", [])
        if isinstance(nested, list):
            attached_decisions.update(decision.get("decision_id", id(decision)) for decision in nested
                                      if isinstance(decision, dict))

    unlinked_findings = [finding for finding in findings if finding.get("finding_id") not in attached_findings]
    unlinked_findings_html = "".join(
        _render_finding(finding, index, [], routes_by_id.get(finding.get("route_id")))
        for index, finding in enumerate(unlinked_findings[:MAX_RECORDS_PER_KIND], 1)
    )
    rendered_routes = "".join(f'<li>{_render_route(route)}</li>' for route in expanded_routes[:MAX_RECORDS_PER_KIND])
    unlinked_decisions = [decision for decision in decisions
                          if decision.get("decision_id", id(decision)) not in attached_decisions]
    rendered_decisions = _decision_rows(unlinked_decisions)
    return (
        '<section class="history-group"><h2>Runs and findings</h2>'
        f'<ol class="history-list">{"".join(run_sections) or "<li>No runs recorded.</li>"}</ol></section>'
        '<section class="history-group"><h2>Unlinked findings</h2>'
        f'<ol class="history-list">{unlinked_findings_html or "<li>No unlinked findings.</li>"}</ol></section>'
        '<section class="history-group"><h2>Routes</h2>'
        f'<ol class="history-list">{rendered_routes or "<li>No routes recorded.</li>"}</ol></section>'
        '<section class="history-group"><h2>Other decisions</h2>'
        f'<ol class="history-list">{rendered_decisions or "<li>No unlinked decisions.</li>"}</ol></section>'
    )


def render_review_detail(data: dict, review: dict, now: datetime, pr: int,
                         history: dict | None = None) -> str:
    """Render a bounded static PR history page from structured records."""
    item = next((entry for entry in data["stack"] if entry["number"] == pr), None)
    if item is None:
        raise ValueError("review details require a configured queue PR")
    queue_item = review.get("queue", {}).get(pr) if review.get("available") else None
    activity_html = render_activity_cards(queue_item, now)
    history = history or {"state": "unavailable"}
    state = history.get("state")
    if state == "unavailable":
        records_html = '<p class="history-note">Recorded history unavailable.</p>'
    elif state == "empty":
        records_html = '<p class="history-note">No recorded history for this PR.</p>'
    else:
        records_html = render_record_sections(history)
        if len(records_html) > MAX_REVIEW_DETAIL_RECORD_HTML_CHARS:
            records_html = ('<p class="history-note">Recorded history exceeds this page’s display limit; '
                            'the source data is unchanged.</p>')
    timestamp = safe(now.isoformat())
    mast = render_mast(
        f'PR #{pr} Review History',
        f'<span class="mast-meta">Snapshot <time datetime="{timestamp}">{safe(local_time(now))}</time></span>',
        "mast-meta",
        (("/", "Delivery Status", "Status"), (f"{REPO_URL}{pr}", "Open PR on GitHub ↗", "GitHub ↗")),
    )
    activity_section = activity_html or '<p class="history-note">Review-round summaries unavailable.</p>'
    return f'''<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src 'self'; base-uri 'none'; form-action 'none">
<link rel="icon" type="image/svg+xml" href="/flame-ember.svg"><title>FireMUD PR #{pr} Review History</title>
<style>
main {{ max-width: 1160px; margin: auto; padding: 1.5rem clamp(1rem, 4vw, 3.5rem) 4rem; }}
.detail-title {{ display: flex; flex-wrap: wrap; align-items: baseline; gap: .35rem .7rem; }}
.detail-title h2 {{ margin: 0; }} .detail-title .queue-status {{ margin-left: auto; }}
.history-card {{ margin-top: 1rem; padding: 1rem; border: 1px solid var(--line); border-radius: 12px; background: var(--paper); }}
.history-group {{ margin-top: 1.4rem; }} .history-group:first-child {{ margin-top: 0; }}
.history-group h2 {{ font-size: 1.2rem; }} .history-list {{ margin: 0; padding-left: 1.3rem; }}
.history-list li + li {{ margin-top: .35rem; }} .history-list p {{ margin: .25rem 0; overflow-wrap: anywhere; }}
.record-counts {{ display: flex; flex-wrap: wrap; gap: .35rem .7rem; color: var(--muted); font-size: .8rem; }}
.record-source {{ color: var(--muted); font-size: .8rem; }}
.linked-record {{ margin: .35rem 0 .2rem; padding-left: 1.2rem; }}
.history-note {{ color: var(--muted); }} footer {{ margin-top: 2rem; color: var(--muted); font-size: .8rem; }}
{SHARED_CSS}
</style></head><body>{mast}<main>
<section class="detail-title"><h2>{safe(item['title'])}</h2><span class="queue-status">{safe(item['stage'])}</span></section>
<section class="history-card" aria-labelledby="round-summary"><h2 id="round-summary">Review rounds</h2>{activity_section}</section>
<section class="history-card" aria-labelledby="record-history"><h2 id="record-history">Recorded review history</h2>{records_html}</section>
<footer>History comes from the controller's read-only records view.</footer>
</main></body></html>'''


def render_activity_cards(queue_item: dict | None, now: datetime) -> str:
    """Render the current compact Hosted/CLI round summaries for reuse on detail pages."""
    activity_cards = []
    if queue_item and isinstance(queue_item.get("review_activity"), dict):
        for channel in ("hosted", "cli"):
            activity = queue_item["review_activity"].get(channel)
            if not isinstance(activity, dict):
                continue
            recent = activity.get("recent", [])
            if not isinstance(recent, list):
                continue
            pills = []
            for result in recent[:MAX_REVIEW_ROUNDS]:
                if not isinstance(result, dict):
                    continue
                routed = result.get("routed")
                if routed is not None and (
                    type(routed) is not int or routed < 0 or routed + result["accepted"] > result["raw"]
                ):
                    raise ValueError("review routed count is invalid")
                older = not result["current_head"]
                unlinked = not result["attributable"]
                non_counting = result["non_counting"]
                description = ", ".join(
                    part for part, selected in
                    (("older head", older), ("unlinked", unlinked), ("non-counting", non_counting)) if selected
                )
                pill_label = f'{result["raw"]}/{result["accepted"]}'
                if routed is not None:
                    pill_label += f"/{routed} (found / accepted here / routed)"
                if description:
                    pill_label += f" ({description})"
                pill_class = "round-pill" + (" zero-accepted" if result["accepted"] == 0 else "") + (" older" if older else "") + (" unlinked" if unlinked else "")
                completed = round_completion(result.get("completed_at"), now)
                if completed is None:
                    completion_label = "Completion time unavailable"
                    age_html = '<span class="round-age">age n/a</span>'
                else:
                    completion_label = f"Completed {completed.astimezone(LOCAL_TIMEZONE).strftime('%d %b %Y %H:%M %Z')}"
                    age_html = (
                        f'<time class="round-age" datetime="{safe(completed.isoformat())}">'
                        f'{safe(round_age(completed, now))}</time>'
                    )
                pills.append(
                    f'<span class="{pill_class}" aria-label="{safe(pill_label + ", " + completion_label)}" '
                    f'title="{safe(completion_label)}">'
                    f'<span>{safe(result["raw"])}/{safe(result["accepted"])}'
                    f'{"/" + safe(routed) if routed is not None else ""}</span>{age_html}</span>'
                )
            older_count = sum(not result["current_head"] for result in recent if isinstance(result, dict))
            unlinked_count = sum(not result["attributable"] for result in recent if isinstance(result, dict))
            non_counting_count = sum(result["non_counting"] for result in recent if isinstance(result, dict))
            notes = []
            if older_count:
                notes.append(f"{older_count} from older heads")
            if unlinked_count:
                notes.append(f"{unlinked_count} unlinked to a verified review")
            if non_counting_count:
                notes.append(f"{non_counting_count} excluded from taper")
            caption = "Recent, oldest to newest"
            if any(isinstance(result, dict) and result.get("routed") is not None for result in recent):
                caption += " · 3 numbers: found / accepted here / routed"
            if notes:
                caption += " · " + " · ".join(notes)
            channel_name = "CLI CodeRabbit" if channel == "cli" else "Hosted CodeRabbit"
            total = activity.get("total", 0)
            if type(total) is not int or total < 0:
                total = 0
            activity_cards.append(
                f'<div class="activity-card"><div class="activity-top"><strong>{channel_name}</strong>'
                f'<span>{safe(total)} completed</span></div>'
                f'<span class="activity-caption">{safe(caption)}</span>'
                f'<div class="round-pills">{"".join(pills) if pills else "None yet"}</div></div>'
            )
    return f'<div class="activity-grid">{"".join(activity_cards)}</div>' if activity_cards else ""


def write_review_detail_pages(output_dir: Path, data: dict, review: dict, now: datetime,
                              histories: dict[int, dict]) -> None:
    detail_dir = output_dir / "review"
    detail_dir.mkdir(parents=True, exist_ok=True)
    for item in data["stack"]:
        number = item["number"]
        document = render_review_detail(data, review, now, number, histories.get(number))
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=detail_dir,
                                         prefix=f".pr-{number}-", delete=False) as temporary:
            temporary.write(document)
            temporary_path = Path(temporary.name)
        try:
            os.replace(temporary_path, detail_dir / f"pr-{number}.html")
        finally:
            temporary_path.unlink(missing_ok=True)


def render(data: dict, review: dict, now: datetime, github: dict | None = None) -> str:
    stack = data["stack"]
    lanes = data["lanes"]
    if not stack or [lane["name"] for lane in lanes] != ["Gameplay", "Document", "General"]:
        raise ValueError("status file needs an ordered stack and Gameplay, Document, General lanes")
    if len({item["number"] for item in stack}) != len(stack):
        raise ValueError("duplicate PR in stack")
    stages = [item["stage"] for item in stack]
    if any(not isinstance(stage, str) or not stage.strip() for stage in stages):
        raise ValueError("every PR needs a programme stage")
    if len(set(stages)) != sum(1 for _ in groupby(stages)):
        raise ValueError("programme stages must be contiguous in stack order")
    github = github or {"available": False, "states": {}, "lifecycle": {}, "merged_at": {}, "stats": {}}
    front_number = selected_review_front(data, review, github)
    front_index = next((index for index, item in enumerate(stack) if item["number"] == front_number), None)
    front_item = stack[front_index] if front_index is not None else None
    next_pr = (
        next((item["number"] for item in stack[front_index + 1:]
              if github["lifecycle"].get(item["number"]) == "OPEN"), None)
        if front_index is not None and github["available"] else None
    )
    rows = []
    front_size_html = "Diff size unavailable"
    front_controller_html = '<span class="front-controller-unavailable">Hosted/CLI states unavailable</span>'
    front_activity_grid = ""
    for position, item in enumerate(stack, 1):
        number = item["number"]
        if type(number) is not int or number <= 0:
            raise ValueError("PR numbers must be positive integers")
        lifecycle = github.get("lifecycle", {}).get(number) if github.get("available") else None
        stats = github.get("stats", {}).get(number) if github.get("available") else None
        if stats:
            files_label = f'{stats["changedFiles"]} files'
            files_html = (
                f'<span class="files-over-warning">{files_label}</span>'
                if stats["changedFiles"] > 90 else files_label
            )
            size_html = (
                f'{files_html} · <span class="additions">+{stats["additions"]:,}</span>/'
                f'<span class="deletions">−{stats["deletions"]:,}</span> lines'
            )
        else:
            size_html = "Diff size unavailable"
        queue_item = review.get("queue", {}).get(number) if review["available"] else None
        channels = queue_item.get("channels", {}) if queue_item else {}
        states = [channels.get(channel) for channel in ("hosted", "cli")] if isinstance(channels, dict) else []
        named_states = []
        for channel, state in zip(("hosted", "cli"), states):
            if not isinstance(state, str) or not state:
                continue
            label = channel_label(channel, state, number, review.get("review_targets", {}))
            reason = held_request_reason(review, channel, number, state)
            named_states.append(f"{label} · {reason}" if reason else label)
        has_controller_states = len(named_states) == 2
        if has_controller_states:
            controller_text = " · ".join(named_states)
        elif named_states:
            missing_channel = "CLI" if isinstance(states[0], str) and states[0] else "Hosted"
            controller_text = f'{" · ".join(named_states)} · Review state unavailable ({missing_channel})'
        else:
            controller_text = "Review state unavailable"
        controller_status_html = f'<span class="sub">{safe(controller_text)}</span>'
        if lifecycle == "MERGED":
            merged_at = github.get("merged_at", {}).get(number)
            merged_time = utc(merged_at) if merged_at else None
            channel_html = (
                f'<time class="relative-age" datetime="{safe(merged_time.isoformat())}" '
                f'title="{safe(local_time(merged_time))}">{relative_time(merged_time, now)}</time>'
                if merged_time else "Merge time unavailable"
            )
            status_html = f'<span class="sub">{channel_html}</span>'
            queue_badge_html = '<span class="queue-status queue-status-merged">MERGED</span>'
        elif lifecycle == "CLOSED":
            status_html = (
                '<span class="sub"><strong>Closed</strong> · Historical review record</span>'
                f'{controller_status_html}'
            )
            queue_badge_html = '<span class="queue-status queue-status-closed">CLOSED</span>'
        elif lifecycle == "OPEN" and github.get("states", {}).get(number) is True:
            status_html = controller_status_html
            queue_badge_html = '<span class="queue-status queue-status-draft">DRAFT</span>'
        elif queue_item:
            status_html = controller_status_html
            if number == front_number:
                badge_label, badge_class = "REVIEW FRONT", "front"
            elif has_controller_states and any(state in ACTIVE_REVIEW_STATES for state in states):
                badge_label, badge_class = "REVIEWING", "reviewing"
            elif has_controller_states and all(state in REVIEW_FINISHED_STATES for state in states):
                badge_label, badge_class = "REVIEW CLOSED", "review-closed"
            elif number == next_pr:
                badge_label, badge_class = "UP NEXT", "up-next"
            else:
                badge_label, badge_class = "QUEUED", "queued"
            queue_badge_html = f'<span class="queue-status queue-status-{badge_class}">{badge_label}</span>'
        else:
            status_html = controller_status_html
            queue_badge_html = (
                '<span class="queue-status queue-status-front">REVIEW FRONT</span>'
                if number == front_number else
                '<span class="queue-status queue-status-up-next">UP NEXT</span>'
                if number == next_pr else
                '<span class="queue-status queue-status-pending">PENDING</span>'
            )
        if queue_item and queue_item.get("detail_level") == "identity_only":
            evidence = queue_item.get("evidence_status")
            label = (
                "Review evidence stale after identity or parent movement"
                if evidence == "stale" else "Review evidence not checked in this refresh"
            )
            status_html += f'<span class="sub">{label} · identity only</span>'
        elif queue_item and queue_item.get("detail_level") == "unknown":
            status_html += '<span class="sub">Review evidence unavailable · identity unknown</span>'
        activity_grid = render_activity_cards(queue_item, now)
        if number == front_number:
            front_size_html = size_html
            if has_controller_states:
                front_controller_html = f'<span class="front-controller-state">{safe(controller_text)}</span>'
            front_activity_grid = activity_grid
        row_classes = []
        if lifecycle == "MERGED":
            row_classes.append("merged")
        elif lifecycle == "CLOSED":
            row_classes.append("closed")
        if number == front_number:
            row_classes.append("front")
        if number == next_pr:
            row_classes.append("next")
        row_class = f' class="{" ".join(row_classes)}"' if row_classes else ""
        rows.append((item["stage"], position,
            f'<li id="pr-{number}"{row_class}><span class="order" aria-label="Queue position {position}">{position:02d}</span><div class="pr-main">'
            f'<div class="pr-title-line"><a href="{REPO_URL}{number}">#{number} {safe(item["title"])}</a></div>'
            f'<span class="sub">{size_html}</span>'
            f'<div class="pr-status-line">{queue_badge_html}{status_html}</div>'
            f'{activity_grid}'
            f'<a class="review-detail-link" href="review/pr-{number}.html">Review details ↗</a>'
            f'</div></li>'))
    train = []
    for stage_position, (stage, grouped_rows) in enumerate(groupby(rows, key=lambda row: row[0]), 1):
        grouped_rows = list(grouped_rows)
        first_position = grouped_rows[0][1]
        train.append(
            f'<section class="queue-stage" aria-labelledby="queue-stage-{stage_position}">'
            f'<h3 id="queue-stage-{stage_position}">{safe(stage)}</h3>'
            f'<ol class="stack" start="{first_position}">{"".join(row[2] for row in grouped_rows)}</ol></section>'
        )
    front_html = ""
    if front_item:
        front_html = (
            f'<section class="front-board" id="review-front" aria-labelledby="front-title">'
            f'<div class="front-copy">'
            f'<h2><a href="{REPO_URL}{front_item["number"]}"><span id="front-title"><span class="front-number">#{front_item["number"]}</span>'
            f'{safe(front_item["title"])}</span></a></h2>'
            f'</div><div class="front-evidence">'
            f'<div class="front-facts"><div class="front-fact"><strong>Diff size</strong>'
            f'<span class="sub front-fact-value">{front_size_html}</span></div>'
            f'<div class="front-fact"><strong>Controller</strong>'
            f'{front_controller_html}</div></div>'
            f'{front_activity_grid or "<p>Review activity unavailable</p>"}</div>'
            f'</section>'
        )
    cards = []
    for lane in lanes:
        status = lane.get("status", "RUNNING")
        if status not in ("RUNNING", "PAUSED"):
            raise ValueError("lane status must be RUNNING or PAUSED")
        details = [f'<li>{safe(item)}</li>' for item in lane_items(lane["task"])]
        sections = []
        for field, label, class_name in (
            ("up_next", "Queued next", "lane-queued"),
            ("blocker", "Blocker", "lane-blocker"),
        ):
            items = lane_items(lane[field]) if lane.get(field) is not None else []
            if items:
                sections.append(
                    f'<div class="{class_name}"><h4>{label}</h4><ul>'
                    f'{"".join(f"<li>{safe(item)}</li>" for item in items)}</ul></div>'
                )
        cards.append(
            f'<article class="card"><div class="card-top"><div class="lane-topline">'
            f'<h3>{safe(lane["name"])}</h3>'
            f'<span class="lane-state lane-state-{status.lower()}" aria-label="{status}">'
            f'<span class="lane-state-icon" aria-hidden="true"></span>{status}</span></div>'
            f'<span class="fresh">{time_label(lane["verified_at"], now, "Lane note")}</span></div>'
            f'<div class="lane-content"><ul class="lane-task">{"".join(details)}</ul>'
            f'{"".join(sections)}</div></article>'
        )
    refreshed_at = (
        f'<time class="relative-age" datetime="{safe(now.isoformat())}" '
        f'title="{safe(local_time(now))}">just now</time>'
    )
    header_time = (
        f'PR data refreshed {refreshed_at}'
        if review["available"] and github["available"]
        else 'Review or PR details unavailable'
    )
    mast_html = render_mast(
        "FireMUD Delivery Status",
        '<form class="refresh-form" action="/refresh" method="post">'
        '<span class="refresh-slot"><button type="submit">Refresh</button></span>'
        '<span class="refresh-progress" role="status" aria-live="polite"></span>'
        f'</form><span class="refresh-time">{header_time}</span>',
        "refresh-space",
        (("/progress.html", "Project Map ↗", "Map ↗"),
         (REPO_HOME, "FireMUD on GitHub ↗", "GitHub ↗")),
    )
    refresh_hash = base64.b64encode(hashlib.sha256(REFRESH_SCRIPT.encode()).digest()).decode()
    age_hash = base64.b64encode(hashlib.sha256(AGE_SCRIPT.encode()).digest()).decode()
    snapshot_hash = base64.b64encode(hashlib.sha256(SNAPSHOT_SCRIPT.encode()).digest()).decode()
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="status-snapshot" content="{safe(now.isoformat())}">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'sha256-{refresh_hash}' 'sha256-{age_hash}' 'sha256-{snapshot_hash}'; img-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'self'">
<link rel="icon" type="image/svg+xml" href="/flame-ember.svg">
<title>FireMUD Delivery Status</title>
<style>
:root {{ color-scheme: light; font-family: ui-sans-serif, system-ui, sans-serif; background: #e5e7eb; color: #252a32; }}
* {{ box-sizing: border-box; }} body {{ margin: 0; overflow-x: hidden; }} main {{ max-width: 1160px; margin: auto; padding: 2rem 1.25rem 4rem; }}
header {{ background: #8e2941; color: #f7f2f4; padding: 2.4rem 1.25rem; }}
.topline {{ display: flex; justify-content: space-between; align-items: center; gap: 1rem; }}
.repo-link {{ color: #f7dce4; font-size: .86rem; font-weight: 650; white-space: nowrap; }} .repo-link:hover {{ color: #fff; }}
h1 {{ font-size: clamp(2rem, 4vw, 3rem); margin: .75rem 0 .5rem; letter-spacing: -.04em; }} h2 {{ margin: 0 0 1rem; font-size: 1.4rem; }} h3 {{ margin: 0; font-size: 1.12rem; }}
p {{ line-height: 1.5; }} .eyebrow {{ text-transform: uppercase; letter-spacing: .16em; font-size: .72rem; font-weight: 700; color: #f2d3dc; }}
header p {{ color: #f0e0e6; max-width: 58ch; margin-bottom: 0; }} .generated {{ color: #66707c; font-size: .8rem; }} header .generated {{ color: #efd5dd; }}
.refresh-form {{ position: absolute; right: calc(100% + .7rem); top: calc(50% - 1.05rem); width: 4.25rem; height: 2.1rem; margin: 0; color: #f0e0e6; font-size: .74rem; }}
.refresh-slot {{ display: flex; align-items: center; width: 100%; height: 100%; }}
.refresh-form button {{ display: inline-flex; align-items: center; justify-content: center; width: 100%; height: 1.65rem; border: 1px solid #f0e0e6; border-radius: 7px; padding: .1rem .375rem; background: #f0e9ed; color: #8e2941; font: inherit; line-height: 1.2; font-weight: 700; cursor: pointer; white-space: pre-line; text-align: center; }}
.refresh-form.loading button {{ height: 2.1rem; }}
.refresh-time {{ display: block; margin-top: .55rem; color: #f0e0e6; font-size: .78rem; line-height: 1.2; }}
.refresh-form button:hover {{ background: #e5dbe0; }}
.refresh-form button:disabled {{ cursor: wait; opacity: .75; }}
.refresh-progress {{ position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; border: 0; }}
.refresh-form.failed .refresh-progress {{ position: fixed; z-index: 10; left: 1rem; right: 1rem; bottom: 1rem; width: auto; height: auto; max-width: 36rem; margin: 0 auto; padding: .75rem 1rem; overflow: visible; clip-path: none; white-space: normal; border: 1px solid #e9b7bb; border-radius: 8px; background: #71232f; color: #fff; box-shadow: 0 4px 18px #252b3940; }}
section {{ margin-top: 2rem; }} .section-note {{ margin: -.35rem 0 1rem; color: #5c6571; font-size: .88rem; }}
.stack, .card {{ background: #f1f2f4; border: 1px solid #cbd0d7; border-radius: 14px; box-shadow: 0 3px 12px #252b390c; }}
.overview {{ list-style: none; padding: 0; margin: 0; display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: .8rem; }}
.overview li {{ display: grid; grid-template-columns: 2.5rem minmax(0,1fr); background: #f1f2f4; border: 1px solid #cbd0d7; border-radius: 14px; overflow: hidden; box-shadow: 0 3px 12px #252b390c; }}
.overview li.merged {{ background: #dbcbe2; }}
.overview .stage-order {{ grid-row: 1 / span 2; background: #8e2941; color: #f7f2f4; font-size: .72rem; font-weight: 700; text-align: center; padding-top: .85rem; }}
.overview h3 {{ line-height: 1.25; padding: .8rem .9rem 0; }} .overview p {{ margin: .45rem 0 0; padding: 0 .9rem .8rem; font-size: .9rem; }}
.stack {{ list-style: none; padding: 0; margin: 0; overflow: hidden; }} .stack li {{ display: grid; grid-template-columns: 2.5rem minmax(0,1fr); gap: 0 1rem; align-items: start; border-bottom: 1px solid #d5d9df; }} .stack li:last-child {{ border: 0; }}
.stack li.merged {{ background: #dbcbe2; }}
.stack li.closed {{ background: #e8eaed; }}
.order {{ align-self: stretch; background: #8e2941; color: #f7f2f4; font-size: .78rem; font-weight: 700; text-align: center; padding-top: .9rem; }} .pr-main {{ min-width: 0; padding: .85rem 1rem .85rem 0; }}
a {{ color: #963149; text-decoration-thickness: 1px; text-underline-offset: 3px; }} a:hover {{ color: #742138; }}
.pr-main > a {{ color: #252a32; font-weight: 650; }} .pr-main > a:hover {{ color: #742138; }} .sub {{ display: block; margin-top: .25rem; color: #626b77; font-size: .78rem; overflow-wrap: anywhere; }}
.sub .files-over-warning, .sub .deletions {{ color: #a13047; font-weight: 650; }} .sub .additions {{ color: #237451; font-weight: 650; }}
.activity-grid {{ display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: .6rem; margin-top: .7rem; }}
.activity-card {{ min-width: 0; padding: .6rem .75rem; border: 1px solid #cbd0d7; border-radius: 9px; background: #e9ebef; }}
.activity-top {{ display: flex; justify-content: space-between; gap: .5rem; font-size: .8rem; }}
.activity-caption {{ display: block; color: #626b77; font-size: .7rem; margin-top: .32rem; }}
.round-pills {{ display: flex; flex-wrap: wrap; gap: .3rem; margin-top: .35rem; font-size: .77rem; }}
.round-pill {{ display: inline-flex; flex-direction: column; align-items: center; justify-content: center; min-width: 2.8rem; border: 1px solid #adb4be; border-radius: 12px; padding: .18rem .43rem; background: #e4e8ed; font-weight: 650; line-height: 1.15; white-space: nowrap; }}
.round-age {{ display: block; margin-top: .08rem; font-size: .67rem; font-weight: 550; }}
.round-pill.older {{ border-style: dashed; background: #f1f2f4; color: #626b77; }}
.round-pill.unlinked {{ border-color: #b9945a; background: #f3e9d9; color: #79562b; }}
.round-pill.zero-accepted {{ background: #ad3b55; color: #fff; }}
.fresh {{ color: #626b77; font-size: .72rem; white-space: nowrap; }} code {{ font-family: ui-monospace, SFMono-Regular, monospace; }}
.cards {{ display: grid; grid-template-columns: repeat(3, minmax(0,1fr)); gap: 1rem; min-width: 0; }} .card {{ min-width: 0; overflow: hidden; }} .card-top {{ display: flex; flex-direction: column; align-items: stretch; gap: .35rem; background: var(--smoke); color: #f7f8f9; padding: .85rem 1rem; }} .card-top .fresh {{ align-self: flex-end; color: #f1dce1; }}
.lane-topline {{ display: flex; align-items: center; justify-content: space-between; gap: .75rem; width: 100%; min-width: 0; }} .lane-topline h3 {{ min-width: 0; margin: 0; }}
.lane-state {{ display: inline-flex; flex: 0 0 auto; align-items: center; gap: .35rem; padding: .26rem .42rem; border: 1px solid #777e87; border-radius: 4px; background: #454a51; color: #fff; font-size: .58rem; font-weight: 850; letter-spacing: .06em; line-height: 1.1; }}
.lane-state-running {{ border-color: #f07865; }} .lane-state-running .lane-state-icon {{ width: .48rem; height: .48rem; border-radius: 50%; background: #ff654d; box-shadow: 0 0 0 2px #754239; }}
.lane-state-paused .lane-state-icon {{ width: .48rem; height: .48rem; border-left: 2px solid #dfe2e6; border-right: 2px solid #dfe2e6; }}
.lane-content {{ padding: .8rem 1rem 1rem; }} .lane-content ul {{ margin: 0; padding-left: 1.1rem; }} .lane-task {{ font-size: .91rem; font-weight: 650; line-height: 1.45; }}
.lane-content li + li {{ margin-top: .3rem; }} .lane-queued, .lane-blocker {{ margin-top: .75rem; padding-top: .65rem; border-top: 1px solid #d9dfe1; font-size: .8rem; line-height: 1.4; }}
.lane-content h4 {{ margin: 0 0 .25rem; color: #515a63; font-size: .66rem; font-weight: 850; letter-spacing: .07em; text-transform: uppercase; }} .lane-blocker h4 {{ color: #9c2939; }}
footer {{ color: #66707c; font-size: .8rem; margin-top: 2.5rem; }}
@media (max-width: 760px) {{ .cards, .activity-grid {{ grid-template-columns: 1fr; }} .card .task {{ min-height: 0; }} .card-top {{ flex-wrap: wrap; }} }}
{SHARED_CSS}
.refresh-space {{ position: relative; display: flex; align-items: center; min-height: 2.1rem; }}
.refresh-space .refresh-time {{ margin: 0; white-space: nowrap; }}
.front-board {{ display: grid; grid-template-columns: minmax(0,1fr) minmax(360px,1fr); background: var(--smoke); color: #fff; overflow: hidden; }}
.front-copy {{ padding: clamp(1.5rem,4vw,3.25rem); display: flex; flex-direction: column; align-items: flex-start; justify-content: center; min-height: 300px; }}
.front-copy h2 {{ margin: 1rem 0; font-size: clamp(1.5rem,3vw,2.75rem); line-height: 1.1; letter-spacing: -.04em; overflow-wrap: anywhere; }}
.front-number {{ display: block; margin-bottom: .65rem; color: #ffc390; font-size: clamp(3rem,6vw,5.5rem); line-height: .95; letter-spacing: -.07em; }}
.front-copy h2 a {{ color: #fff; text-decoration: none; }} .front-copy h2 a:hover {{ text-decoration: underline; }}
.front-facts {{ display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: .6rem; width: 100%; }}
.front-fact {{ display: flex; min-width: 0; flex-direction: column; align-items: flex-start; gap: .35rem; padding: .6rem .75rem; border: 1px solid #f4c9c7; border-radius: 9px; background: #fff; color: var(--ink); font-size: .8rem; }}
.front-fact > strong {{ color: #37414a; font-size: .8rem; font-weight: 700; }}
.front-facts .sub {{ display: inline; margin: 0; font-size: .78rem; color: var(--ink); }}
.front-fact-value, .front-controller-state, .front-controller-unavailable {{ color: var(--ink); font-size: .78rem; font-weight: 650; line-height: 1.35; overflow-wrap: anywhere; }}
.front-fact-value .additions {{ color: #9de0bd; }} .front-fact-value .deletions, .front-fact-value .files-over-warning {{ color: #ffc390; }}
.front-controller-unavailable {{ color: #f1dfe1; font-weight: 600; }}
.front-evidence {{ background: var(--fire); padding: clamp(1.35rem,3vw,2.5rem); display: flex; flex-direction: column; justify-content: center; align-items: stretch; gap: .8rem; }}
@media (min-width: 901px) {{ .front-copy {{ padding: 2.15rem; }} .front-evidence {{ padding: 1.65rem; }} }}
.front-evidence > .activity-grid {{ grid-template-columns: repeat(2,minmax(0,1fr)); width: 100%; margin-top: 0; }}
.front-evidence .activity-card {{ background: #fff; border-color: #f4c9c7; color: var(--ink); }}
.front-evidence .activity-top strong {{ color: #37414a; }}
.front-evidence .activity-caption {{ color: #57636c; }}
.front-evidence .round-pill {{ background: #fff; color: #423039; }}
.front-evidence .round-pill.zero-accepted {{ background: #25212a; color: #fff; }}
.section-head {{ display: flex; justify-content: space-between; align-items: end; gap: 1rem; margin: 2.8rem 0 1rem; }}
.section-head h2 {{ margin: 0; }}
.section-head p {{ max-width: 70ch; margin: 0; color: var(--muted); font-size: .8rem; }}
.queue-guide {{ margin: -.5rem 0 .65rem; padding: .45rem .65rem; border-left: 3px solid var(--fire); background: var(--paper); color: var(--muted); font-size: .72rem; line-height: 1.35; }}
.queue-guide h3 {{ margin: 0 0 .3rem; color: var(--ink); font-size: .67rem; letter-spacing: .06em; text-transform: uppercase; }}
.queue-guide dl {{ display: grid; grid-template-columns: repeat(auto-fit,minmax(210px,1fr)); gap: .2rem .65rem; margin: 0; }}
.queue-guide dl > div {{ display: flex; gap: .25rem; min-width: 0; }} .queue-guide dt {{ flex: 0 0 auto; color: var(--ink); font-weight: 800; }} .queue-guide dd {{ margin: 0; }}
.queue-guide p {{ margin: .3rem 0 0; font-size: .68rem; }}
.review-train {{ background: var(--paper); border-top: 3px solid var(--smoke); border-bottom: 2px solid var(--smoke); }}
.queue-stage {{ display: grid; grid-template-columns: minmax(150px,.4fr) minmax(0,1.6fr); gap: 1rem; margin-top: 0; padding: .55rem 1rem; border-top: 1px solid var(--line); }}
.queue-stage:first-child {{ border-top: 0; }} .queue-stage > h3 {{ margin: .3rem 1rem 0 0; color: #37414a; font-size: 1.05rem; font-weight: 850; line-height: 1.2; }}
.queue-stage > .stack {{ border: 0; border-radius: 0; background: transparent; box-shadow: none; overflow: visible; }}
.queue-stage > .stack li {{ position: relative; padding: 0; }} .queue-stage > .stack li:last-child {{ border: 0; }}
.queue-stage > .stack .pr-main {{ padding: .55rem .7rem .55rem 0; }} .queue-stage > .stack .order {{ padding-top: .55rem; }}
.queue-stage > .stack li.closed {{ background: #e8eaed; }}
.queue-stage > .stack li.front {{ background: var(--blush); border-left: 0; box-shadow: inset 5px 0 var(--ember); }}
.queue-stage > .stack li.front .order {{ background: var(--fire); }}
.queue-stage > .stack li.merged {{ background: var(--plum-wash); border-left: 0; box-shadow: none; }}
.queue-stage > .stack li.merged .order {{ background: var(--plum); }}
.pr-title-line {{ display: flex; flex-wrap: wrap; align-items: baseline; gap: .35rem .55rem; }}
.pr-status-line {{ display: flex; flex-wrap: wrap; align-items: center; gap: .2rem .5rem; margin-top: .25rem; }}
.pr-status-line .sub {{ display: inline; margin-top: 0; }}
.review-detail-link {{ display: inline-block; margin-top: .4rem; font-size: .78rem; font-weight: 700; }}
.queue-status {{ display: inline-flex; align-items: center; padding: .16rem .38rem; border: 1px solid transparent; font-size: .59rem; font-weight: 900; letter-spacing: .045em; line-height: 1.2; white-space: nowrap; }}
.queue-status-front {{ background: var(--fire); color: #fff; }}
.queue-status-merged {{ background: var(--plum); color: #fff; }}
.queue-status-reviewing {{ background: #d9edf4; border-color: #8bb8c8; color: #17495a; }}
.queue-status-review-closed {{ background: #e7e9ed; border-color: #b9bec7; color: #454b56; }}
.queue-status-draft {{ background: #e9eaf0; border-color: #b8bdcc; color: #444b5b; }}
.queue-status-up-next {{ background: #fff3db; border-color: #d0a95c; color: #6a4d17; }}
.queue-status-queued, .queue-status-pending {{ background: #fff; border-color: #c7ccd4; color: #58616d; }}
.queue-status-closed {{ background: #e7e9ed; border-color: #b9bec7; color: #454b56; }}
.queue-stage > .stack li.merged .pr-main > a {{ color: #392451; }}
.cards {{ margin-top: 0; }} .card {{ border-radius: 0; box-shadow: none; }} .card-top {{ background: var(--smoke); }}
@media (max-width: 900px) {{ .queue-stage {{ grid-template-columns: 1fr; gap: .45rem; }} .queue-stage > h3 {{ margin: 0 0 0 3.5rem; }} }}
@media (max-width: 900px) {{ .mast-inner > .refresh-space {{ margin-left: 4.95rem; }} }}
@media (max-width: 760px) {{ .mast-inner > .repo-links {{ text-align: right; gap: .25rem .6rem; }} .refresh-space {{ flex-wrap: wrap; gap: .35rem .6rem; }} .front-board {{ grid-template-columns: 1fr; }} .front-copy {{ min-height: 250px; }} .front-facts {{ grid-template-columns: 1fr; }} .front-evidence > .activity-grid {{ grid-template-columns: 1fr; }} .section-head {{ display: block; }} .section-head p {{ margin-top: .55rem; }} .queue-stage {{ padding: .55rem .8rem; }} .cards {{ grid-template-columns: minmax(0,1fr); width: 100%; }} .lane-topline {{ padding-right: .75rem; }} .card-top .fresh {{ max-width: 100%; margin-right: .75rem; white-space: normal; text-align: right; }} }}
</style></head><body>
{mast_html}
<main>{front_html}<section id="workers"><div class="section-head"><h2>Worker lanes</h2><p>Current focus across active workstreams.</p></div><div class="cards">{"".join(cards)}</div></section>
<section id="train"><div class="section-head"><h2>Configured review queue</h2></div>
<div class="queue-guide"><h3>Review request states</h3><dl>
<div><dt>Ready</dt><dd>selected channel may request</dd></div>
<div><dt>Waiting turn</dt><dd>another PR is ahead</dd></div>
<div><dt>Reviewing</dt><dd>request is active</dd></div>
<div><dt>Cooldown</dt><dd>provider rate limit</dd></div>
<div><dt>Blocked</dt><dd>new request is held; a specific reason appears on the row when supplied</dd></div>
<div><dt>Parent changed / needs reconciliation</dt><dd>re-prove branch before requesting</dd></div>
<div><dt>Human bypass / Review closed</dt><dd>intentionally stopped or completed</dd></div>
</dl><p>Request states are not merge readiness. Result pills show raw/useful counts and age; dashed borders mark older PR heads.</p></div>
<div class="review-train">{"".join(train)}</div></section>
<footer>Queue order follows the review controller; programme labels and lane notes are maintained in status.json. The Refresh button updates PR details and publishes both pages. The queue links to public review details; raw captures and credentials are not intentionally embedded.</footer></main><script id="local-refresh-progress">{REFRESH_SCRIPT}</script><script id="relative-age-updates">{AGE_SCRIPT}</script><script id="snapshot-updates">{SNAPSHOT_SCRIPT}</script></body></html>"""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--review-tool", type=Path, help="optional read-only repository PR-review controller")
    args = parser.parse_args()
    now = datetime.now(timezone.utc)
    data = json.loads(args.input.read_text(encoding="utf-8"))
    front = next(item for item in data["stack"] if item["number"] == data["review_front"])
    configured_tool = data.get("review_tool")
    if configured_tool is not None and (not isinstance(configured_tool, str) or not Path(configured_tool).is_absolute()):
        raise ValueError("review_tool must be an absolute path")
    review_tool = args.review_tool or (Path(configured_tool) if configured_tool else None)
    review = review_snapshot(review_tool, front["number"], front["head"], now)
    if review_tool is not None and not review["available"] and args.output.exists():
        raise RuntimeError(f"review status unavailable; existing page preserved: {review['reason']}")
    github = github_stages(now)
    if not github["available"] and args.output.exists():
        raise RuntimeError("GitHub PR details unavailable; existing page preserved")
    now = datetime.now(timezone.utc)
    data = controller_stack(data, review, github, now)
    history_prs = [item["number"] for item in data["stack"]]
    histories = records_history_snapshots(review_tool, history_prs)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    rendered = render(data, review, now, github)
    progress_rendered = render_project_map(now)
    write_review_detail_pages(args.output.parent, data, review, now, histories)
    for name in ASSET_FILES:
        source = ROOT / "assets" / name
        with tempfile.NamedTemporaryFile(dir=args.output.parent, prefix=".asset-", delete=False) as asset_temp:
            asset_temp.write(source.read_bytes())
            asset_temp_path = Path(asset_temp.name)
        try:
            os.replace(asset_temp_path, args.output.parent / name)
        finally:
            asset_temp_path.unlink(missing_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=args.output.parent, prefix=".status-", delete=False) as temporary:
        temporary.write(rendered)
        temporary_path = Path(temporary.name)
    try:
        os.replace(temporary_path, args.output)
    finally:
        temporary_path.unlink(missing_ok=True)
    progress_output = args.output.parent / "progress.html"
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=args.output.parent, prefix=".progress-", delete=False) as temporary:
        temporary.write(progress_rendered)
        progress_temporary = Path(temporary.name)
    try:
        os.replace(progress_temporary, progress_output)
    finally:
        progress_temporary.unlink(missing_ok=True)
    print(args.output.resolve())


if __name__ == "__main__":
    main()
