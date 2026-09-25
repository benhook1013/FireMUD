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
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (button.disabled) return;
    button.disabled = true;
    button.textContent = 'Refreshing…';
    form.classList.add('loading');
    const started = Date.now();
    const update = () => { progress.textContent = `Working for ${Math.floor((Date.now() - started) / 1000)}s…`; };
    update();
    const timer = setInterval(update, 1000);
    try {
      const response = await fetch(form.action, { method: 'POST', credentials: 'same-origin' });
      if (!response.ok) {
        progress.textContent = response.status === 409
          ? 'Another refresh is already running. Try again when it finishes.'
          : `Refresh failed (${response.status}). Try again shortly.`;
        return;
      }
      window.location.reload();
    } catch {
      progress.textContent = 'Refresh connection failed. Try again shortly.';
    } finally {
      clearInterval(timer);
      button.disabled = false;
      button.textContent = 'Refresh review data';
    }
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
            [sys.executable, str(tool), "status", "--pr", str(pr), "--json"],
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
        live_head = report["pull_request"]["headRefOid"]
        if not isinstance(live_head, str) or report["pr_number"] != pr:
            raise ValueError("status is for a different PR")
        return {
            "available": True,
            "source": source,
            "as_of": now.isoformat(),
            "head": live_head,
            "saved_head_stale": live_head != expected_head,
            "queue": {item["pr"]: item for item in report["review_stack"]["prs"]},
        }
    except (ValueError, KeyError, TypeError, AttributeError):
        return {**unavailable, "reason": "Status fetch malformed or for a different PR head"}


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
    rows = []
    for position, item in enumerate(stack, 1):
        number = item["number"]
        if type(number) is not int or number <= 0:
            raise ValueError("PR numbers must be positive integers")
        draft = github["states"].get(number)
        lifecycle = github.get("lifecycle", {}).get(number)
        github_label = (
            "Merged" if lifecycle == "MERGED" else "Closed" if lifecycle == "CLOSED"
            else "Draft" if draft is True else "Ready for review" if draft is False
            else "GitHub stage unavailable"
        )
        stats = github.get("stats", {}).get(number)
        size_label = (
            f'{stats["changedFiles"]} files · +{stats["additions"]:,}/−{stats["deletions"]:,} lines'
            if stats else "Diff size unavailable"
        )
        queue_item = review.get("queue", {}).get(number) if review["available"] else None
        if lifecycle == "MERGED":
            merged_at = github.get("merged_at", {}).get(number)
            merged_time = utc(merged_at) if merged_at else None
            channel_label = (
                f"{relative_time(merged_time, now)} · {local_time(merged_time)}"
                if merged_time else "Merge time unavailable"
            )
        elif lifecycle == "CLOSED":
            channel_label = "Historical review record"
        elif queue_item:
            channels = queue_item["channels"]
            channel_label = f'Hosted {channels["hosted"].replace("_", " ").title()} · CLI {channels["cli"].replace("_", " ").title()}'
        else:
            channel_label = "Review eligibility unavailable"
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
                    pill_class = "round-pill" + (" older" if older else "") + (" unlinked" if unlinked else "")
                    pills.append(
                        f'<span class="{pill_class}" aria-label="{safe(pill_label)}">'
                        f'{safe(result["raw"])}/{safe(result["accepted"])}</span>'
                    )
                older_count = sum(not result["current_head"] for result in recent)
                unlinked_count = sum(not result["attributable"] for result in recent)
                non_counting_count = sum(result["non_counting"] for result in recent)
                notes = []
                if older_count:
                    notes.append(f"{older_count} shown from older heads")
                if unlinked_count:
                    notes.append(f"{unlinked_count} unlinked to a verified review")
                if non_counting_count:
                    notes.append(f"{non_counting_count} excluded from taper")
                channel_name = "CLI" if channel == "cli" else "Hosted"
                activity_cards.append(
                    f'<div class="activity-card"><div class="activity-top"><strong>{channel_name}</strong>'
                    f'<span>{safe(activity["total"])} completed</span></div>'
                    f'<span class="activity-caption">Recent, oldest to newest</span>'
                    f'<div class="round-pills">{"".join(pills) if pills else "None yet"}</div>'
                    f'<span class="activity-note">{safe(" · ".join(notes))}</span></div>'
                )
        activity_grid = f'<div class="activity-grid">{"".join(activity_cards)}</div>' if activity_cards else ""
        row_class = ' class="merged"' if lifecycle == "MERGED" else ' class="closed"' if lifecycle == "CLOSED" else ""
        rows.append(
            f'<li{row_class}><span class="order">{position:02d}</span><div class="pr-main">'
            f'<a href="{REPO_URL}{number}">#{number} {safe(item["title"])}</a>'
            f'<span class="sub">{safe(size_label)}</span>'
            f'<span class="sub"><strong>{safe(github_label)}</strong> · {safe(channel_label)}</span>'
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
    queue_time = (
        f'Review and PR details refreshed {safe(local_time(now))}. '
        if review["available"] and github["available"]
        else 'Some review or PR details were unavailable when this page was rendered. '
    )
    refresh_hash = base64.b64encode(hashlib.sha256(REFRESH_SCRIPT.encode()).digest()).decode()
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'sha256-{refresh_hash}'; img-src 'none'; connect-src 'self'; base-uri 'none'; form-action 'self'">
<title>FireMUD · local delivery status</title>
<style>
:root {{ color-scheme: light; font-family: ui-sans-serif, system-ui, sans-serif; background: #e9e5e3; color: #2d2627; }}
* {{ box-sizing: border-box; }} body {{ margin: 0; overflow-x: hidden; }} main {{ max-width: 1160px; margin: auto; padding: 2rem 1.25rem 4rem; }}
header {{ background: #612f33; color: #f4eeea; padding: 2.4rem 1.25rem; }} header div {{ max-width: 1160px; margin: auto; }}
.topline {{ display: flex; justify-content: space-between; align-items: center; gap: 1rem; }}
.repo-link {{ color: #f1dbd7; font-size: .86rem; font-weight: 650; white-space: nowrap; }} .repo-link:hover {{ color: #fff; }}
h1 {{ font-size: clamp(2rem, 4vw, 3rem); margin: .2rem 0 .5rem; letter-spacing: -.04em; }} h2 {{ margin: 0 0 1rem; font-size: 1.4rem; }} h3 {{ margin: 0; font-size: 1.12rem; }}
p {{ line-height: 1.5; }} .eyebrow {{ text-transform: uppercase; letter-spacing: .16em; font-size: .72rem; font-weight: 700; color: #efd4cf; }}
header p {{ color: #eadbd7; max-width: 58ch; margin-bottom: 0; }} .generated {{ color: #776a68; font-size: .8rem; }} header .generated {{ color: #dec9c4; }}
.refresh-form {{ display: flex; flex-wrap: wrap; gap: .65rem; align-items: center; margin-top: 1rem; color: #eadbd7; font-size: .78rem; }}
.refresh-form button {{ border: 1px solid #eadbd7; border-radius: 7px; padding: .5rem .75rem; background: #f1e9e5; color: #612f33; font: inherit; font-weight: 700; cursor: pointer; }}
.refresh-form button:hover {{ background: #e5d6d1; }}
.refresh-form button:disabled {{ cursor: wait; opacity: .75; }}
.refresh-progress {{ display: none; }} .refresh-form.loading .refresh-progress {{ display: inline; }}
section {{ margin-top: 2rem; }} .section-note {{ margin: -.35rem 0 1rem; color: #685d5d; font-size: .88rem; }}
.stack, .card {{ background: #f4f0ed; border: 1px solid #d8cfcc; border-radius: 14px; box-shadow: 0 3px 12px #3823250b; }}
.overview {{ list-style: none; padding: 0; margin: 0; display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: .8rem; }}
.overview li {{ display: grid; grid-template-columns: 2.5rem minmax(0,1fr); background: #f4f0ed; border: 1px solid #d8cfcc; border-radius: 14px; overflow: hidden; box-shadow: 0 3px 12px #3823250b; }}
.overview li.merged {{ background: #e9e1eb; }}
.overview .stage-order {{ grid-row: 1 / span 2; background: #612f33; color: #f4eeea; font-size: .72rem; font-weight: 700; text-align: center; padding-top: .85rem; }}
.overview h3 {{ line-height: 1.25; padding: .8rem .9rem 0; }} .overview p {{ margin: .45rem 0 0; padding: 0 .9rem .8rem; font-size: .9rem; }}
.stack {{ list-style: none; padding: 0; margin: 0; overflow: hidden; }} .stack li {{ display: grid; grid-template-columns: 2.5rem minmax(0,1fr); gap: 0 1rem; align-items: start; border-bottom: 1px solid #ded5d2; }} .stack li:last-child {{ border: 0; }}
.stack li.merged {{ background: #e9e1eb; }}
.stack li.closed {{ background: #ebe7e5; }}
.order {{ align-self: stretch; background: #612f33; color: #f4eeea; font-size: .78rem; font-weight: 700; text-align: center; padding-top: .9rem; }} .pr-main {{ min-width: 0; padding: .85rem 1rem .85rem 0; }}
a {{ color: #853e43; text-decoration-thickness: 1px; text-underline-offset: 3px; }} a:hover {{ color: #612f33; }}
.pr-main > a {{ color: #2d2627; font-weight: 650; }} .pr-main > a:hover {{ color: #612f33; }} .sub {{ display: block; margin-top: .25rem; color: #6c6060; font-size: .78rem; overflow-wrap: anywhere; }}
.activity-grid {{ display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: .6rem; margin-top: .7rem; }}
.activity-card {{ min-width: 0; padding: .6rem .75rem; border: 1px solid #d8cfcc; border-radius: 9px; background: #eee9e6; }}
.activity-top {{ display: flex; justify-content: space-between; gap: .5rem; font-size: .8rem; }}
.activity-caption, .activity-note {{ display: block; color: #726667; font-size: .7rem; margin-top: .32rem; }}
.round-pills {{ display: flex; flex-wrap: wrap; gap: .3rem; margin-top: .35rem; font-size: .77rem; }}
.round-pill {{ border: 1px solid #ba9c9c; border-radius: 999px; padding: .12rem .43rem; background: #eadcda; font-weight: 650; }}
.round-pill.older {{ border-style: dashed; background: #f4f0ed; color: #6c6060; }}
.round-pill.unlinked {{ border-color: #b9945a; background: #f3e9d9; color: #79562b; }}
.fresh {{ color: #746869; font-size: .72rem; white-space: nowrap; }} code {{ font-family: ui-monospace, SFMono-Regular, monospace; }}
.cards {{ display: grid; grid-template-columns: repeat(3, minmax(0,1fr)); gap: 1rem; }} .card {{ overflow: hidden; }} .card-top {{ display: flex; justify-content: space-between; gap: .5rem; align-items: baseline; background: #612f33; color: #f4eeea; padding: .9rem 1.15rem; }} .card-top .fresh {{ color: #eadbd7; }}
.task {{ font-weight: 620; min-height: 3.1em; margin: 1rem 1.15rem; }} dl {{ display: grid; grid-template-columns: 5.5rem 1fr; gap: .55rem .4rem; margin: 0 1.15rem 1.15rem; font-size: .86rem; }} dt {{ color: #6d6161; }} dd {{ margin: 0; line-height: 1.4; }}
footer {{ color: #776a68; font-size: .8rem; margin-top: 2.5rem; }}
@media (max-width: 760px) {{ .cards, .activity-grid {{ grid-template-columns: 1fr; }} .card .task {{ min-height: 0; }} .card-top {{ flex-wrap: wrap; }} }}
</style></head><body>
<header><div><div class="topline"><span class="eyebrow">Private local snapshot</span><a class="repo-link" href="{REPO_HOME}">FireMUD on GitHub ↗</a></div><h1>FireMUD delivery status</h1>
<p>Configured review queue and worker lanes. This is a manual snapshot, not a merge authorization or live monitor.</p>
<p class="generated">Page updated {safe(local_time(now))} · stack source: local status.json</p>
<form class="refresh-form" action="/refresh" method="post"><button type="submit">Refresh review data</button><span class="refresh-progress" role="status" aria-live="polite"></span><span>Takes about a minute. Updates review counts and PR sizes; stack order and worker notes stay manual.</span></form></div></header>
<main><section><h2>Worker lanes</h2><p class="section-note">Task state is maintained by hand. Check its verified time before acting.</p><div class="cards">{"".join(cards)}</div></section>
<section><h2>Stack at a glance</h2><p class="section-note">The single published review train, grouped by what the PRs are meant to deliver. Position in the train is not merge readiness.</p>
<ol class="overview">{"".join(overview)}</ol></section>
<section><h2>Configured review queue</h2><p class="section-note">{queue_time}Order, titles, and saved heads are maintained manually. Completed counts can include older-head or unlinked results; they do not establish taper or merge readiness. The controller channel status remains authoritative.</p>
<ol class="stack">{"".join(rows)}</ol></section>
<footer>To refresh: edit status.json for stack or lane changes, then run the local renderer. No credentials or private review records are embedded in this page.</footer></main><script id="local-refresh-progress">{REFRESH_SCRIPT}</script></body></html>"""


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
