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
from datetime import datetime, timedelta, timezone
from itertools import groupby
from pathlib import Path
from zoneinfo import ZoneInfo

from render_progress import render_current as render_project_map

ROOT = Path(__file__).resolve().parent
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
REFRESH_SCRIPT = """(() => {
  const form = document.querySelector('.refresh-form');
  if (!form) return;
  const button = form.querySelector('button');
  const progress = form.querySelector('.refresh-progress');
  let stage = 'rendering';
  let statusPending = false;
  let waitingForOther = false;
  let finishOther;
  const stageLabel = {rendering: 'Refreshing', publishing: 'Publishing'};
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
      button.textContent = `${waitingForOther ? 'Waiting' : stageLabel[stage]} · ${Math.floor((Date.now() - started) / 1000)}s`;
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
      button.textContent = 'Refresh review data';
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
        : `${Math.floor(minutes / 1440)}d ago`;
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
    return f"{minutes // (24 * 60)}d ago"


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
        named_states = [
            channel_label(channel, state, number, review.get("review_targets", {}))
            for channel, state in zip(("hosted", "cli"), states)
            if isinstance(state, str) and state
        ]
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
        activity_cards = []
        if queue_item and isinstance(queue_item.get("review_activity"), dict):
            for channel in ("hosted", "cli"):
                activity = queue_item["review_activity"].get(channel)
                if not isinstance(activity, dict):
                    continue
                recent = activity.get("recent", [])
                pills = []
                for result in recent:
                    older = not result["current_head"]
                    unlinked = not result["attributable"]
                    non_counting = result["non_counting"]
                    description = ", ".join(
                        part for part, selected in
                        (("older head", older), ("unlinked", unlinked), ("non-counting", non_counting)) if selected
                    )
                    pill_label = f'{result["raw"]}/{result["accepted"]}'
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
                        f'<span>{safe(result["raw"])}/{safe(result["accepted"])}</span>{age_html}</span>'
                    )
                older_count = sum(not result["current_head"] for result in recent)
                unlinked_count = sum(not result["attributable"] for result in recent)
                non_counting_count = sum(result["non_counting"] for result in recent)
                notes = []
                if older_count:
                    notes.append(f"{older_count} from older heads")
                if unlinked_count:
                    notes.append(f"{unlinked_count} unlinked to a verified review")
                if non_counting_count:
                    notes.append(f"{non_counting_count} excluded from taper")
                caption = "Recent, oldest to newest"
                if notes:
                    caption += " · " + " · ".join(notes)
                channel_name = "CLI CodeRabbit" if channel == "cli" else "Hosted CodeRabbit"
                activity_cards.append(
                    f'<div class="activity-card"><div class="activity-top"><strong>{channel_name}</strong>'
                    f'<span>{safe(activity["total"])} completed</span></div>'
                    f'<span class="activity-caption">{safe(caption)}</span>'
                    f'<div class="round-pills">{"".join(pills) if pills else "None yet"}</div></div>'
                )
        activity_grid = f'<div class="activity-grid">{"".join(activity_cards)}</div>' if activity_cards else ""
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
            f'<h2 id="front-title"><span class="front-number">#{front_item["number"]}</span>'
            f'<a href="{REPO_URL}{front_item["number"]}">{safe(front_item["title"])}</a></h2>'
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
    refresh_hash = base64.b64encode(hashlib.sha256(REFRESH_SCRIPT.encode()).digest()).decode()
    age_hash = base64.b64encode(hashlib.sha256(AGE_SCRIPT.encode()).digest()).decode()
    snapshot_hash = base64.b64encode(hashlib.sha256(SNAPSHOT_SCRIPT.encode()).digest()).decode()
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="status-snapshot" content="{safe(now.isoformat())}">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'sha256-{refresh_hash}' 'sha256-{age_hash}' 'sha256-{snapshot_hash}'; img-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'self'">
<link rel="icon" type="image/svg+xml" href="/flame-ember.svg">
<title>FireMUD · local delivery status</title>
<style>
:root {{ color-scheme: light; font-family: ui-sans-serif, system-ui, sans-serif; background: #e5e7eb; color: #252a32; }}
* {{ box-sizing: border-box; }} body {{ margin: 0; overflow-x: hidden; }} main {{ max-width: 1160px; margin: auto; padding: 2rem 1.25rem 4rem; }}
header {{ background: #8e2941; color: #f7f2f4; padding: 2.4rem 1.25rem; }}
.topline {{ display: flex; justify-content: space-between; align-items: center; gap: 1rem; }}
.repo-link {{ color: #f7dce4; font-size: .86rem; font-weight: 650; white-space: nowrap; }} .repo-link:hover {{ color: #fff; }}
h1 {{ font-size: clamp(2rem, 4vw, 3rem); margin: .75rem 0 .5rem; letter-spacing: -.04em; }} h2 {{ margin: 0 0 1rem; font-size: 1.4rem; }} h3 {{ margin: 0; font-size: 1.12rem; }}
p {{ line-height: 1.5; }} .eyebrow {{ text-transform: uppercase; letter-spacing: .16em; font-size: .72rem; font-weight: 700; color: #f2d3dc; }}
header p {{ color: #f0e0e6; max-width: 58ch; margin-bottom: 0; }} .generated {{ color: #66707c; font-size: .8rem; }} header .generated {{ color: #efd5dd; }}
.refresh-form {{ width: 8.5rem; height: 2.1rem; margin: 0; color: #f0e0e6; font-size: .74rem; }}
.refresh-slot {{ display: flex; align-items: center; width: 100%; height: 100%; }}
.refresh-form button {{ display: inline-flex; align-items: center; justify-content: center; width: 100%; height: 100%; border: 1px solid #f0e0e6; border-radius: 7px; padding: .2rem .7rem; background: #f0e9ed; color: #8e2941; font: inherit; line-height: 1.2; font-weight: 700; cursor: pointer; white-space: nowrap; }}
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
:root {{ --ash: #e9eef0; --paper: #f9faf9; --ink: #242832; --muted: #57636c; --line: #bfccd0; --smoke: #a51f27; --fire: #b71d35; --ember: #e85137; --blush: #fff0eb; --plum: #7042a0; --plum-wash: #f2ebf8; }}
body {{ background: var(--ash); color: var(--ink); }}
header.mast {{ position: sticky; top: 0; z-index: 20; background: var(--smoke); padding: .8rem clamp(1rem,4vw,3.5rem); border-bottom: 1px solid #671820; box-shadow: 0 3px 10px #252b3933; }}
.mast-inner {{ display: grid; grid-template-columns: minmax(0,1fr) auto minmax(0,1fr); grid-template-areas: "title refresh repo"; align-items: center; column-gap: 1rem; max-width: 1440px; margin: auto; }}
.mast-inner > .refresh-space, .mast-inner > .mast-content {{ min-width: 0; max-width: none; margin: 0; }}
.refresh-space {{ grid-area: refresh; display: flex; align-items: center; justify-self: center; gap: .7rem; }}
.mast-content {{ grid-area: title; display: flex; align-items: flex-end; gap: .55rem; text-align: left; }}
.mast-icon {{ width: 1.7rem; height: 1.7rem; flex: none; }}
.brand {{ display: block; min-width: 0; margin: 0; color: #fff; font-size: clamp(1rem,2.2vw,1.4rem); font-weight: 850; line-height: 1.1; letter-spacing: -.04em; overflow-wrap: anywhere; }}
.refresh-space .refresh-time {{ margin: 0; white-space: nowrap; }}
.mast-inner > .repo-links {{ grid-area: repo; justify-self: end; display: flex; align-items: center; flex-wrap: wrap; justify-content: flex-end; gap: .3rem 1rem; }}
.repo-links a {{ color: #fff; font-size: .86rem; font-weight: 700; white-space: nowrap; }}
.nav-short {{ display: none; }}
main {{ width: 100%; max-width: 1440px; margin: auto; padding: 1rem clamp(1rem,4vw,3.5rem) 4rem; }}
.front-board {{ display: grid; grid-template-columns: minmax(0,1fr) minmax(360px,1fr); background: var(--smoke); color: #fff; overflow: hidden; }}
.front-copy {{ padding: clamp(1.5rem,4vw,3.25rem); display: flex; flex-direction: column; align-items: flex-start; justify-content: center; min-height: 300px; }}
.front-copy h2 {{ margin: 1rem 0; font-size: clamp(1.5rem,3vw,2.75rem); line-height: 1.1; letter-spacing: -.04em; overflow-wrap: anywhere; }}
.front-number {{ display: block; margin-bottom: .65rem; color: #ffc390; font-size: clamp(3rem,6vw,5.5rem); line-height: .95; letter-spacing: -.07em; }}
.front-copy h2 a {{ color: #fff; text-decoration: none; }} .front-copy h2 a:hover {{ text-decoration: underline; }}
.front-facts {{ display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: .6rem; width: 100%; }}
.front-fact {{ display: flex; min-width: 0; flex-direction: column; align-items: flex-start; gap: .35rem; padding: .6rem .75rem; border: 1px solid #f4c9c7; border-radius: 9px; background: #fff; color: var(--ink); font-size: .8rem; }}
.front-fact > strong {{ color: #62212f; font-size: .8rem; font-weight: 700; }}
.front-facts .sub {{ display: inline; margin: 0; font-size: .78rem; color: var(--ink); }}
.front-fact-value, .front-controller-state, .front-controller-unavailable {{ color: var(--ink); font-size: .78rem; font-weight: 650; line-height: 1.35; overflow-wrap: anywhere; }}
.front-fact-value .additions {{ color: #9de0bd; }} .front-fact-value .deletions, .front-fact-value .files-over-warning {{ color: #ffc390; }}
.front-controller-unavailable {{ color: #f1dfe1; font-weight: 600; }}
.front-evidence {{ background: var(--fire); padding: clamp(1.35rem,3vw,2.5rem); display: flex; flex-direction: column; justify-content: center; align-items: stretch; gap: .8rem; }}
.front-evidence > .activity-grid {{ grid-template-columns: repeat(2,minmax(0,1fr)); width: 100%; margin-top: 0; }}
.front-evidence .activity-card {{ background: #fff; border-color: #f4c9c7; color: var(--ink); }}
.front-evidence .activity-top strong {{ color: #62212f; }}
.front-evidence .activity-caption {{ color: #57636c; }}
.front-evidence .round-pill {{ background: #fff; color: #423039; }}
.front-evidence .round-pill.zero-accepted {{ background: #25212a; color: #fff; }}
.section-head {{ display: flex; justify-content: space-between; align-items: end; gap: 1rem; margin: 2.8rem 0 1rem; }}
.section-head h2 {{ margin: 0; font-size: clamp(1.8rem,3vw,2.8rem); letter-spacing: -.04em; }}
.section-head p {{ max-width: 70ch; margin: 0; color: var(--muted); font-size: .8rem; }}
.legend {{ display: flex; flex-wrap: wrap; align-items: center; gap: .5rem 1.1rem; padding: .7rem .9rem; margin-bottom: .8rem; border-left: 5px solid var(--fire); background: #fff; font-size: .76rem; }}
.legend strong {{ color: #89182c; }} .legend-dash {{ display: inline-block; width: 1.2rem; margin-right: .3rem; border-top: 2px dashed #9b5760; vertical-align: middle; }}
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
a:focus-visible, button:focus-visible {{ outline: 3px solid #f6aa61; outline-offset: 3px; }}
@media (max-width: 900px) {{ .queue-stage {{ grid-template-columns: 1fr; gap: .45rem; }} .queue-stage > h3 {{ margin: 0 0 0 3.5rem; }} }}
@media (max-width: 900px) {{ .mast-inner {{ grid-template-columns: minmax(0,1fr) auto; grid-template-areas: "title repo" "refresh refresh"; gap: .45rem .8rem; }} .refresh-space {{ justify-self: start; }} }}
@media (max-width: 760px) {{ .mast-inner > .repo-links {{ text-align: right; gap: .25rem .6rem; }} .repo-links a {{ font-size: .73rem; }} .repo-links .nav-full {{ display: none; }} .repo-links .nav-short {{ display: inline; }} .refresh-space {{ flex-wrap: wrap; gap: .35rem .6rem; }} .front-board {{ grid-template-columns: 1fr; }} .front-copy {{ min-height: 250px; }} .front-facts {{ grid-template-columns: 1fr; }} .front-evidence > .activity-grid {{ grid-template-columns: 1fr; }} .section-head {{ display: block; }} .section-head p {{ margin-top: .55rem; }} .queue-stage {{ padding: .55rem .8rem; }} .cards {{ grid-template-columns: minmax(0,1fr); width: 100%; }} .lane-topline {{ padding-right: .75rem; }} .card-top .fresh {{ max-width: 100%; margin-right: .75rem; white-space: normal; text-align: right; }} }}
</style></head><body>
<header class="mast"><div class="mast-inner"><div class="mast-content"><img class="mast-icon" src="/flame-ember.svg" alt=""><h1 class="brand">FireMUD Delivery Status</h1></div><div class="refresh-space"><form class="refresh-form" action="/refresh" method="post"><span class="refresh-slot"><button type="submit">Refresh review data</button></span><span class="refresh-progress" role="status" aria-live="polite"></span></form><span class="refresh-time">{header_time}</span></div><nav class="repo-links"><a href="/progress.html"><span class="nav-full">Project Map ↗</span><span class="nav-short">Map ↗</span></a><a class="repo-link" href="{REPO_HOME}"><span class="nav-full">FireMUD on GitHub ↗</span><span class="nav-short">GitHub ↗</span></a></nav></div></header>
<main>{front_html}<section id="workers"><div class="section-head"><h2>Worker lanes</h2><p>Current focus across active workstreams.</p></div><div class="cards">{"".join(cards)}</div></section>
<section id="train"><div class="section-head"><h2>Configured review queue</h2></div>
<div class="legend"><strong>Read the results</strong><span>Pills show raw/useful results and their age.</span><span><span class="legend-dash" aria-hidden="true"></span>Dashed border: older PR head</span></div>
<div class="review-train">{"".join(train)}</div></section>
<footer>Queue order follows the review controller; programme labels and lane notes are maintained in status.json. The Refresh review data button updates PR details and publishes both pages. No credentials or private review records are embedded in this page.</footer></main><script id="local-refresh-progress">{REFRESH_SCRIPT}</script><script id="relative-age-updates">{AGE_SCRIPT}</script><script id="snapshot-updates">{SNAPSHOT_SCRIPT}</script></body></html>"""


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
    args.output.parent.mkdir(parents=True, exist_ok=True)
    rendered = render(data, review, now, github)
    progress_rendered = render_project_map(now)
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
