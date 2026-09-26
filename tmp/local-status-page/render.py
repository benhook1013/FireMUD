#!/usr/bin/env python3
"""Render the private, manually maintained FireMUD stack snapshot as static HTML."""

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

ROOT = Path(__file__).resolve().parent
DEFAULT_INPUT = ROOT / "status.json"
DEFAULT_OUTPUT = ROOT / "output" / "index.html"
REPO_URL = "https://github.com/benhook1013/FireMUD/pull/"
REPO_HOME = "https://github.com/benhook1013/FireMUD"
REPO = "benhook1013/FireMUD"
LOCAL_TIMEZONE = ZoneInfo("Pacific/Auckland")
REFRESH_SCRIPT = """(() => {
  const form = document.querySelector('.refresh-form');
  if (!form) return;
  const button = form.querySelector('button');
  const progress = form.querySelector('.refresh-progress');
  let stage = 'rendering';
  let statusPending = false;
  const stageLabel = {rendering: 'Refreshing local review data', publishing: 'Publishing public status page'};
  const readStage = async () => {
    if (statusPending) return;
    statusPending = true;
    try {
      const response = await fetch('/refresh-status', {credentials: 'same-origin'});
      if (!response.ok) return;
      const status = await response.json();
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
    form.classList.remove('failed');
    progress.textContent = 'Refreshing local review data';
    form.classList.add('loading');
    const started = Date.now();
    const update = () => {
      button.textContent = `${stageLabel[stage]} · ${Math.floor((Date.now() - started) / 1000)}s`;
      void readStage();
    };
    update();
    const timer = setInterval(update, 1000);
    try {
      const response = await fetch(form.action, { method: 'POST', credentials: 'same-origin' });
      if (!response.ok) {
        form.classList.add('failed');
        progress.textContent = response.status === 409
          ? 'Another refresh is already running. Try again when it finishes.'
          : response.status === 429
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
        queue = {}
        for item in report["prs"]:
            number = item["pr"]
            if type(number) is not int or number in queue or not isinstance(item["head"], str):
                raise ValueError("invalid review stack record")
            queue[number] = item
        if pr not in queue:
            raise ValueError("review front missing from stack")
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
            "queue": queue,
        }
    except (ValueError, KeyError, TypeError, AttributeError):
        return {**unavailable, "reason": "Status fetch malformed or review front missing"}


def github_stages(now: datetime) -> dict:
    """Fetch live GitHub PR stage and diff size once for the open queue."""
    try:
        run = subprocess.run(
            ["gh", "pr", "list", "--repo", REPO, "--state", "all", "--limit", "200", "--json", "number,state,isDraft,mergedAt,changedFiles,additions,deletions"],
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
        for item in items:
            if (type(item["number"]) is not int or type(item["isDraft"]) is not bool
                    or item["state"] not in ("OPEN", "MERGED", "CLOSED")):
                raise ValueError("GitHub query returned an invalid PR stage")
            states[item["number"]] = item["isDraft"]
            lifecycle[item["number"]] = item["state"]
            if item["state"] == "MERGED" and isinstance(item.get("mergedAt"), str):
                merged_at[item["number"]] = item["mergedAt"]
            if all(type(item.get(key)) is int and item[key] >= 0 for key in ("changedFiles", "additions", "deletions")):
                stats[item["number"]] = {key: item[key] for key in ("changedFiles", "additions", "deletions")}
        return {"available": True, "as_of": now.isoformat(), "states": states, "lifecycle": lifecycle, "merged_at": merged_at, "stats": stats}
    except (OSError, subprocess.TimeoutExpired, ValueError, KeyError, TypeError):
        return {"available": False, "as_of": now.isoformat(), "states": {}, "lifecycle": {}, "merged_at": {}, "stats": {}}


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
    overview = []
    for position, (stage, members) in enumerate(groupby(stack, key=lambda item: item["stage"]), 1):
        members = list(members)
        links = " · ".join(
            f'<a href="{REPO_URL}{item["number"]}">#{item["number"]}</a>' for item in members
        )
        merged = github["available"] and all(github["lifecycle"].get(item["number"]) == "MERGED" for item in members)
        row_class = ' class="merged"' if merged else ""
        overview.append(
            f'<li{row_class}><span class="stage-order">{position:02d}</span>'
            f'<h3>{safe(stage)}</h3><p>{links}</p></li>'
        )
    front_index = next((index for index, item in enumerate(stack) if item["number"] == data["review_front"]), None)
    next_pr = (
        next((item["number"] for item in stack[front_index + 1:]
              if github["lifecycle"].get(item["number"]) == "OPEN"), None)
        if front_index is not None and github["available"] else None
    )
    rows = []
    for position, item in enumerate(stack, 1):
        number = item["number"]
        if type(number) is not int or number <= 0:
            raise ValueError("PR numbers must be positive integers")
        lifecycle = github.get("lifecycle", {}).get(number)
        stats = github.get("stats", {}).get(number)
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
        if lifecycle == "MERGED":
            merged_at = github.get("merged_at", {}).get(number)
            merged_time = utc(merged_at) if merged_at else None
            channel_html = (
                f'<time class="relative-age" datetime="{safe(merged_time.isoformat())}" '
                f'title="{safe(local_time(merged_time))}">{relative_time(merged_time, now)}</time>'
                if merged_time else "Merge time unavailable"
            )
            status_html = f'<span class="sub"><strong>Merged</strong> {channel_html}</span>'
        elif lifecycle == "CLOSED":
            status_html = '<span class="sub"><strong>Closed</strong> · Historical review record</span>'
        elif queue_item:
            channels = queue_item.get("channels", {})
            states = [channels.get(channel) for channel in ("hosted", "cli")] if isinstance(channels, dict) else []
            if (len(states) == 2 and all(isinstance(state, str) and state for state in states)
                    and ("READY" in states or number in (data["review_front"], next_pr))):
                labels = (f"{name} {state.replace('_', ' ').lower()}"
                          for name, state in zip(("Hosted", "CLI"), states))
                status_html = f'<span class="sub">{safe(" · ".join(labels))}</span>'
            else:
                status_html = ""
        else:
            status_html = ""
        if number == next_pr:
            status_html = (
                '<span class="sub"><strong>Up next in queue</strong> · Queue position alone does not establish review eligibility.</span>'
                + status_html
            )
            if not queue_item or not isinstance(queue_item.get("channels"), dict) or not all(
                isinstance(queue_item["channels"].get(channel), str) and queue_item["channels"][channel]
                for channel in ("hosted", "cli")
            ):
                status_html += '<span class="sub">Controller review states unavailable</span>'
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
                        age_html = '<span class="round-age">?</span>'
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
                channel_name = "CLI" if channel == "cli" else "Hosted"
                activity_cards.append(
                    f'<div class="activity-card"><div class="activity-top"><strong>{channel_name}</strong>'
                    f'<span>{safe(activity["total"])} completed</span></div>'
                    f'<span class="activity-caption">{safe(caption)}</span>'
                    f'<div class="round-pills">{"".join(pills) if pills else "None yet"}</div></div>'
                )
        activity_grid = f'<div class="activity-grid">{"".join(activity_cards)}</div>' if activity_cards else ""
        row_class = ' class="merged"' if lifecycle == "MERGED" else ' class="closed"' if lifecycle == "CLOSED" else ""
        rows.append(
            f'<li{row_class}><span class="order">{position:02d}</span><div class="pr-main">'
            f'<a href="{REPO_URL}{number}">#{number} {safe(item["title"])}</a>'
            f'<span class="sub">{size_html}</span>'
            f'{status_html}'
            f'{activity_grid}'
            f'</div></li>'
        )
    cards = []
    for lane in lanes:
        cards.append(
            f'<article class="card"><div class="card-top"><h3>{safe(lane["name"])}</h3>'
            f'<span class="fresh">{time_label(lane["verified_at"], now, "Lane note")}</span></div>'
            f'<p class="task">{safe(lane["task"])}</p>'
            f'<dl><dt>Next</dt><dd>{safe(lane["next_action"])}</dd>'
            f'<dt>Blocker / gate</dt><dd>{safe(lane["blocker"])}</dd>'
            f'</dl></article>'
        )
    refreshed_at = (
        f'<time class="relative-age" datetime="{safe(now.isoformat())}" '
        f'title="{safe(local_time(now))}">just now</time>'
    )
    queue_time = (
        f'Refreshed {refreshed_at}'
        if review["available"] and github["available"]
        else 'Review or PR details unavailable'
    )
    header_time = (
        f'PR data refreshed {refreshed_at}'
        if review["available"] and github["available"]
        else queue_time
    )
    if review["available"] and github["available"] and review.get("mode") == "windowed":
        window = review.get("detail_window", {})
        checked = window.get("deep_prs", []) if isinstance(window, dict) else []
        checked_count = len(checked) if isinstance(checked, list) else 0
        checked_unit = "PR" if checked_count == 1 else "PRs"
        queue_time = (
            f'PR identities refreshed {refreshed_at} · Review overview partial: '
            f'detailed evidence checked for {checked_count} {checked_unit}; other entries are informational.'
        )
    refresh_hash = base64.b64encode(hashlib.sha256(REFRESH_SCRIPT.encode()).digest()).decode()
    age_hash = base64.b64encode(hashlib.sha256(AGE_SCRIPT.encode()).digest()).decode()
    snapshot_hash = base64.b64encode(hashlib.sha256(SNAPSHOT_SCRIPT.encode()).digest()).decode()
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="status-snapshot" content="{safe(now.isoformat())}">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'sha256-{refresh_hash}' 'sha256-{age_hash}' 'sha256-{snapshot_hash}'; img-src 'none'; connect-src 'self'; base-uri 'none'; form-action 'self'">
<title>FireMUD · local delivery status</title>
<style>
:root {{ color-scheme: light; font-family: ui-sans-serif, system-ui, sans-serif; background: #e5e7eb; color: #252a32; }}
* {{ box-sizing: border-box; }} body {{ margin: 0; overflow-x: hidden; }} main {{ max-width: 1160px; margin: auto; padding: 2rem 1.25rem 4rem; }}
header {{ background: #8e2941; color: #f7f2f4; padding: 2.4rem 1.25rem; }} header div {{ max-width: 1160px; margin: auto; }}
.topline {{ display: flex; justify-content: space-between; align-items: center; gap: 1rem; }}
.repo-link {{ color: #f7dce4; font-size: .86rem; font-weight: 650; white-space: nowrap; }} .repo-link:hover {{ color: #fff; }}
h1 {{ font-size: clamp(2rem, 4vw, 3rem); margin: .75rem 0 .5rem; letter-spacing: -.04em; }} h2 {{ margin: 0 0 1rem; font-size: 1.4rem; }} h3 {{ margin: 0; font-size: 1.12rem; }}
p {{ line-height: 1.5; }} .eyebrow {{ text-transform: uppercase; letter-spacing: .16em; font-size: .72rem; font-weight: 700; color: #f2d3dc; }}
header p {{ color: #f0e0e6; max-width: 58ch; margin-bottom: 0; }} .generated {{ color: #66707c; font-size: .8rem; }} header .generated {{ color: #efd5dd; }}
.refresh-form {{ display: flex; flex-wrap: wrap; gap: .65rem; align-items: center; margin-top: 1rem; color: #f0e0e6; font-size: .78rem; }}
.refresh-form button {{ border: 1px solid #f0e0e6; border-radius: 7px; padding: .5rem .75rem; background: #f0e9ed; color: #8e2941; font: inherit; font-weight: 700; cursor: pointer; white-space: nowrap; }}
.refresh-form button:hover {{ background: #e5dbe0; }}
.refresh-form button:disabled {{ cursor: wait; opacity: .75; }}
.refresh-progress {{ position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; border: 0; }}
.refresh-form.failed .refresh-progress {{ position: static; width: auto; height: auto; margin: 0; overflow: visible; clip-path: none; white-space: normal; }}
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
.cards {{ display: grid; grid-template-columns: repeat(3, minmax(0,1fr)); gap: 1rem; }} .card {{ overflow: hidden; }} .card-top {{ display: flex; justify-content: space-between; gap: .5rem; align-items: baseline; background: #8e2941; color: #f7f2f4; padding: .9rem 1.15rem; }} .card-top .fresh {{ color: #f0e0e6; }}
.task {{ font-weight: 620; min-height: 3.1em; margin: 1rem 1.15rem; }} dl {{ display: grid; grid-template-columns: 5.5rem 1fr; gap: .55rem .4rem; margin: 0 1.15rem 1.15rem; font-size: .86rem; }} dt {{ color: #626b77; }} dd {{ margin: 0; line-height: 1.4; }}
footer {{ color: #66707c; font-size: .8rem; margin-top: 2.5rem; }}
@media (max-width: 760px) {{ .cards, .activity-grid {{ grid-template-columns: 1fr; }} .card .task {{ min-height: 0; }} .card-top {{ flex-wrap: wrap; }} }}
</style></head><body>
<header><div><div class="topline"><span class="eyebrow">Private local snapshot</span><a class="repo-link" href="{REPO_HOME}">FireMUD on GitHub ↗</a></div><h1>FireMUD delivery status</h1>
<p>Worker lanes and the PR train.</p>
<form class="refresh-form" action="/refresh" method="post"><button type="submit">Refresh review data</button><span class="refresh-progress" role="status" aria-live="polite"></span><span class="refresh-time">{header_time}</span></form></div></header>
<main><section><h2>Worker lanes</h2><p class="section-note">Task state is maintained by hand. Check its verified time before acting.</p><div class="cards">{"".join(cards)}</div></section>
<section><h2>Stack at a glance</h2><p class="section-note">The single published review train, grouped by what the PRs are meant to deliver. Position in the train is not merge readiness.</p>
<ol class="overview">{"".join(overview)}</ol></section>
<section><h2>Configured review queue</h2><p class="section-note">{queue_time}</p>
<ol class="stack">{"".join(rows)}</ol></section>
<footer>Stack and lane notes are maintained in status.json. The Refresh review data button updates PR details and publishes both pages. No credentials or private review records are embedded in this page.</footer></main><script id="local-refresh-progress">{REFRESH_SCRIPT}</script><script id="relative-age-updates">{AGE_SCRIPT}</script><script id="snapshot-updates">{SNAPSHOT_SCRIPT}</script></body></html>"""


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
    args.output.parent.mkdir(parents=True, exist_ok=True)
    rendered = render(data, review, now, github)
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=args.output.parent, prefix=".status-", delete=False) as temporary:
        temporary.write(rendered)
        temporary_path = Path(temporary.name)
    try:
        os.replace(temporary_path, args.output)
    finally:
        temporary_path.unlink(missing_ok=True)
    print(args.output.resolve())


if __name__ == "__main__":
    main()
