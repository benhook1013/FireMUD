#!/usr/bin/env python3
"""Render the manually maintained long-range map and tracker-row overview."""

from __future__ import annotations

import base64
import hashlib
import html
import json
import re
from collections import Counter
from datetime import datetime
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parent
SHARED_CSS = (ROOT / "shared.css").read_text(encoding="utf-8")
TRACKERS = ROOT.parents[1] / "design/project-management/implementation-tracking"
TRACKER_URL = "https://github.com/benhook1013/FireMUD/blob/develop/design/project-management/implementation-tracking/"
IMPLEMENTATION_STATES = frozenset({"implemented", "partial", "not-implemented", "design-unresolved", "not-applicable"})
VERIFICATION_STATES = frozenset({"proven", "audited", "unverified", "drift-found", "not-applicable"})
AGE_SCRIPT = """(() => {
  const labels = document.querySelectorAll('.relative-age[datetime]');
  const update = () => {
    const now = Date.now();
    for (const label of labels) {
      const then = Date.parse(label.dateTime);
      if (!Number.isFinite(then) || then > now) {
        label.textContent = 'time unavailable';
        continue;
      }
      const minutes = Math.floor((now - then) / 60000);
      label.textContent = minutes < 1 ? 'just now'
        : minutes < 60 ? `${minutes}m ago`
        : minutes < 2880 ? `${Math.floor(minutes / 60)}h ago`
        : `${Math.floor(minutes / 1440)}d ago`;
    }
  };
  update();
  setInterval(update, 30000);
})();"""


def safe(value: object) -> str:
    return html.escape(str(value), quote=True)


def safe_link(label: str, url: str) -> str:
    parsed = urlsplit(url)
    if url.startswith("/") and not url.startswith("//"):
        target = url
    elif parsed.scheme == "https" and parsed.hostname == "github.com" and not parsed.username and not parsed.password:
        target = url
    else:
        raise ValueError(f"unsupported project-map link: {url}")
    return f'<a href="{safe(target)}">{safe(label)}</a>'


def tracker_counts(path: Path) -> tuple[Counter, Counter]:
    """Read only primary capability rows from the tracker's status table."""
    content = path.read_text(encoding="utf-8")
    match = re.search(r"(?m)^## Capability Status\s*$([\s\S]*?)(?=^## |\Z)", content)
    if match is None:
        raise ValueError(f"missing Capability Status table: {path.name}")
    implementation: Counter = Counter()
    verification: Counter = Counter()
    for line in match.group(1).splitlines():
        if not re.match(r"^\| (?:`)?[A-Z]{2,4}-\d", line):
            continue
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if len(cells) < 3:
            raise ValueError(f"invalid capability status row in {path.name}")
        implementation_state = re.match(r"^`([^`]+)`", cells[1])
        verification_state = re.match(r"^`([^`]+)`", cells[2])
        if (implementation_state is None or verification_state is None
                or implementation_state.group(1) not in IMPLEMENTATION_STATES
                or verification_state.group(1) not in VERIFICATION_STATES):
            raise ValueError(f"invalid capability status row in {path.name}")
        implementation[implementation_state.group(1)] += 1
        verification[verification_state.group(1)] += 1
    if not sum(implementation.values()):
        raise ValueError(f"no capability status rows: {path.name}")
    return implementation, verification


def rough_percent(counts: Counter) -> int:
    """A coarse navigation proxy, never a measured completion or effort estimate."""
    total = sum(value for state, value in counts.items() if state != "not-applicable")
    if not total:
        return 0
    weighted = counts["implemented"] + counts["partial"] * 0.5
    return int((weighted / total * 100 + 5) // 10 * 10)


def render(data: dict, now: datetime, tracker_root: Path = TRACKERS) -> str:
    tracks = []
    for track in data["tracks"]:
        phases = "".join(
            f'<li><span>{safe(phase["name"])}</span><b>{safe(phase["state"])}</b></li>'
            for phase in track["phases"]
        )
        links = " · ".join(safe_link(link["label"], link["url"]) for link in track.get("links", []))
        tracks.append(f'''<article class="track" id="{safe(track["id"])}">
<div class="track-head"><div><span class="eyebrow">Programme track</span><h3>{safe(track["name"])}</h3></div><span class="state {safe(track["tone"])}">{safe(track["state"])}</span></div>
<p class="purpose">{safe(track["purpose"])}</p>
<div class="track-body"><div><h4>Where it stands</h4><p>{safe(track["now"])}</p><h4>Next milestone</h4><p>{safe(track["milestone"])}</p><h4>Return when</h4><p>{safe(track["return_when"])}</p></div><ol class="phases">{phases}</ol></div>
<div class="track-links">{links}</div></article>''')

    domains = []
    overall_implementation: Counter = Counter()
    overall_verification: Counter = Counter()
    for domain in data["domains"]:
        filename = domain["file"]
        if Path(filename).name != filename or not filename.endswith(".md"):
            raise ValueError("domain file must be a tracker basename")
        implementation, verification = tracker_counts(tracker_root / filename)
        overall_implementation.update(implementation)
        overall_verification.update(verification)
        total = sum(implementation.values())
        percent = rough_percent(implementation)
        counts = f'{implementation["implemented"]} complete · {implementation["partial"]} partial · {implementation["not-implemented"]} not started'
        proof = f'{verification["proven"]}/{total} proven'
        domains.append(f'''<article class="domain"><div class="domain-top"><h3>{safe_link(domain["name"], TRACKER_URL + filename)}</h3><strong>≈{percent}%</strong></div>
<div class="bar" aria-label="Rough status-based proxy {percent} percent"><span style="width:{percent}%"></span></div>
<p class="domain-counts">{safe(counts)} <span>· {safe(proof)}</span></p><p>{safe(domain["note"])}</p></article>''')

    total = sum(overall_implementation.values())
    overall_percent = rough_percent(overall_implementation)
    summary = f'''<div class="domain-summary"><div class="summary-lead"><div><span class="eyebrow">Across all ten domains</span><strong>≈{overall_percent}%</strong><span>rough tracker progress</span></div>
<div class="bar" aria-label="Rough status-based proxy {overall_percent} percent"><span style="width:{overall_percent}%"></span></div></div>
<div class="summary-stats"><div><strong>{overall_implementation["implemented"]}/{total}</strong><span>fully implemented</span></div><div><strong>{overall_implementation["partial"]}</strong><span>partial</span></div><div><strong>{overall_implementation["not-implemented"]}</strong><span>not started</span></div><div><strong>{overall_verification["proven"]}/{total}</strong><span>proven</span></div></div></div>'''
    returns = "".join(
        f'<li><div><strong>{safe(item["name"])}</strong><span>{safe(item["state"])}</span></div><p>{safe(item["trigger"])}</p></li>'
        for item in data["return_points"]
    )
    reviewed_at = datetime.fromisoformat(data["reviewed_at"].replace("Z", "+00:00"))
    age_hash = base64.b64encode(hashlib.sha256(AGE_SCRIPT.encode()).digest()).decode()
    reviewed = f'<time class="relative-age" datetime="{safe(reviewed_at.isoformat())}">checked recently</time>'
    refreshed = f'<time class="relative-age" datetime="{safe(now.isoformat())}">just now</time>'
    return f'''<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'sha256-{age_hash}'; img-src 'self'; base-uri 'none'; form-action 'none'">
<link rel="icon" type="image/svg+xml" href="/flame-ember.svg"><title>FireMUD Project Map</title>
<style>
{SHARED_CSS}
a {{ color:#9a1e31; text-underline-offset:3px; }} a:hover {{ color:#6a1827; }}
.mast {{ display:grid; grid-template-columns:minmax(0,1fr) auto minmax(0,1fr); align-items:center; gap:1rem; }}
.mast-meta {{ text-align:center; white-space:nowrap; }} .mast nav {{ display:flex; flex-wrap:wrap; gap:.5rem 1.2rem; justify-self:end; justify-content:flex-end; }}
.hero {{ margin-top:2rem; background:var(--smoke); color:white; padding:clamp(1.5rem,4vw,3rem); border-radius:0; }} .hero h1 {{ margin:.4rem 0 1rem; font-size:clamp(2rem,4vw,3.3rem); line-height:1.05; max-width:24ch; }} .hero p {{ max-width:78ch; line-height:1.5; }} .hero .meta {{ color:#f1dce1; font-size:.8rem; }}
section {{ margin-top:2.2rem; }} main section > h2 {{ margin:0 0 .45rem; }} .section-intro {{ color:var(--muted); line-height:1.5; margin:.2rem 0 1rem; }} h3 {{ margin:0; font-size:1.18rem; }} h4 {{ margin:1rem 0 .25rem; font-size:.7rem; text-transform:uppercase; letter-spacing:.09em; color:#636a73; }} p {{ line-height:1.48; }}
.tracks {{ display:grid; gap:.85rem; }} .track,.domain {{ background:#f9faf9; border:1px solid #bfccd0; border-radius:0; overflow:hidden; }} .track-head {{ display:flex; justify-content:space-between; gap:1rem; align-items:center; background:#f0f2f3; padding:1rem 1.2rem; }} .eyebrow {{ color:#9a1e31; text-transform:uppercase; letter-spacing:.13em; font-size:.66rem; font-weight:800; }} .track-head h3 {{ margin:.22rem 0 0; }} .state {{ font-size:.66rem; font-weight:850; letter-spacing:.045em; padding:.35rem .55rem; border-radius:0; white-space:normal; text-align:right; }} .state.done {{ background:#e7deec; color:#55346e; }} .state.active {{ background:#a51f27; color:#fff; }} .state.waiting {{ background:#f6e8d8; color:#744518; }} .state.paused {{ background:#dfe4e7; color:#414b55; }}
.purpose {{ margin:0; padding:.75rem 1.2rem; color:#4f5963; border-bottom:1px solid #dce2e5; }} .track-body {{ display:grid; grid-template-columns:minmax(0,1.5fr) minmax(260px,1fr); gap:1.5rem; padding:.2rem 1.2rem 1rem; }} .track-body p {{ margin:.2rem 0 .65rem; }} .phases {{ list-style:none; margin:1rem 0 0; padding:0; }} .phases li {{ display:flex; justify-content:space-between; gap:1rem; padding:.6rem 0; border-bottom:1px solid #dce2e5; font-size:.84rem; }} .phases b {{ color:#8b2636; font-size:.68rem; white-space:nowrap; }} .track-links {{ padding:.75rem 1.2rem; border-top:1px solid #dce2e5; font-size:.8rem; }}
.domains {{ display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:.75rem; }} .domain {{ padding:1rem 1.15rem; }} .domain-top {{ display:flex; justify-content:space-between; align-items:baseline; gap:.75rem; }} .domain-top strong {{ color:#a51f27; font-size:1.45rem; }} .domain h3 a {{ color:#242832; }} .bar {{ height:6px; background:#dfe4e7; border-radius:9px; overflow:hidden; margin:.7rem 0; }} .bar span {{ display:block; height:100%; background:#b71d35; }} .domain p {{ margin:.45rem 0 0; font-size:.84rem; }} .domain-counts {{ color:#59636e; }} .domain-counts span {{ white-space:nowrap; }}
.domain-summary {{ display:grid; grid-template-columns:minmax(200px,.7fr) minmax(0,1.3fr); gap:1.2rem; align-items:center; padding:1rem 1.15rem; margin-bottom:.8rem; background:#f9faf9; border:1px solid #bfccd0; border-radius:0; }} .summary-lead>div:first-child {{ display:flex; align-items:baseline; flex-wrap:wrap; gap:.35rem .65rem; }} .summary-lead strong {{ font-size:1.75rem; color:#a51f27; line-height:1; }} .summary-lead>div:first-child>span:last-child {{ color:#57636c; font-size:.78rem; }} .summary-stats {{ display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:.5rem; }} .summary-stats>div {{ display:flex; flex-direction:column; gap:.15rem; padding:.5rem .65rem; background:#edf0f1; border:1px solid #d5dde0; border-radius:0; }} .summary-stats strong {{ color:#a51f27; font-size:1rem; }} .summary-stats span {{ color:#57636c; font-size:.71rem; white-space:nowrap; }}
.returns {{ list-style:none; padding:0; margin:0; display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:.65rem; }} .returns li {{ background:#f9faf9; border:1px solid #bfccd0; border-radius:0; padding:.85rem 1rem; }} .returns li>div {{ display:flex; justify-content:space-between; gap:.7rem; }} .returns span {{ font-size:.67rem; font-weight:850; color:#9a1e31; }} .returns p {{ margin:.4rem 0 0; font-size:.82rem; }} footer {{ margin-top:2rem; color:#57636c; font-size:.8rem; }}
@media(max-width:900px) {{ .mast {{ grid-template-columns:minmax(0,1fr) auto; gap:.45rem .8rem; }} .mast-meta {{ grid-column:1 / -1; grid-row:2; text-align:left; white-space:normal; }} .mast nav {{ grid-column:2; grid-row:1; }} }}
@media(max-width:780px) {{ .track-head {{ align-items:flex-start; flex-direction:column; }} .track-body,.domains,.returns,.domain-summary {{ grid-template-columns:1fr; }} .summary-stats {{ grid-template-columns:repeat(2,minmax(0,1fr)); }} .domain-top {{ align-items:flex-start; }} .returns li>div {{ flex-wrap:wrap; }} }}
</style></head><body><header class="mast"><div class="mast-brand"><img src="/flame-ember.svg" alt=""><strong>FireMUD Project Map</strong></div><span class="mast-meta">Programme notes checked {reviewed} · Page rendered {refreshed}</span><nav><a href="/"><span class="nav-full">PR Delivery ↗</span><span class="nav-short">PRs ↗</span></a><a href="https://github.com/benhook1013/FireMUD"><span class="nav-full">FireMUD on GitHub ↗</span><span class="nav-short">GitHub ↗</span></a></nav></header>
<main><div class="hero"><h1>{safe(data["headline"])}</h1><p>{safe(data["orientation"])}</p></div>
<section><h2>Programme tracks</h2><div class="tracks">{"".join(tracks)}</div></section>
<section id="domains"><h2>Implementation by domain</h2><p class="section-intro">A very rough status proxy from the ten primary implementation trackers. Each complete row counts as 1 and each partial row as ½, rounded to the nearest 10%. Capabilities differ greatly in size, so this is a reading aid, not measured effort remaining or a release-readiness score. Proof is counted separately. Tracker changes appear after this local checkout is updated.</p>{summary}<div class="domains">{"".join(domains)}</div></section>
<section><h2>Return points</h2><p class="section-intro">These remain visible when a worker follows a different tangent for weeks or months. {safe(data["parked_note"])}</p><ul class="returns">{returns}</ul></section>
<footer>Programme notes are manually curated. Capability counts come from the tracker rows in this checkout; the linked trackers and <a href="/">PR Delivery Page</a> hold the detailed evidence.</footer></main><script id="relative-age-updates">{AGE_SCRIPT}</script></body></html>'''


def render_current(now: datetime) -> str:
    data = json.loads((ROOT / "progress.json").read_text(encoding="utf-8"))
    return render(data, now)
