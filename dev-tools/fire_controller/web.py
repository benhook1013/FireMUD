"""Small, explicit public and private web views for FireController jobs."""

from __future__ import annotations

import html
import json
import posixpath
import re
from datetime import datetime, timezone
from urllib.parse import parse_qs, quote, unquote, urlsplit
from zoneinfo import ZoneInfo

from .context import worker_alias as _worker_alias

PUBLIC_FIELDS = (
    "id",
    "name",
    "worker",
    "workstream_id",
    "title",
    "status",
    "primary",
    "summary",
    "progress",
    "blocker",
    "created_at",
    "updated_at",
    "last_activity_at",
)
JOB_STATUSES = frozenset({"active", "parked", "blocked", "completed"})
LANE_STATUSES = frozenset({"active", "blocked", "paused", "idle"})
HISTORY_PAGE_SIZE = 50
INBOX_NOTE_PAGE_SIZE = 50
MAX_HISTORY_OFFSET = 1_000_000
_JOB_ID = re.compile(r"[A-Za-z0-9._-]{1,128}\Z")
_WORKSTREAM_ID = re.compile(r"[a-z0-9][a-z0-9-]{0,99}\Z")
_MARKDOWN_TOKEN = re.compile(
    r"(!?\[[^\]\n]*\]\([^\s)]+\)|\x60[^\x60\n]*\x60|\*\*[^*\n]+\*\*|"
    r"\*[^*\n]+\*|__[^_\n]+__|_[^_\n]+_|\n)"
)
_CHECKLIST_ITEM = re.compile(r"^\s*(?:[-*+]\s+|\d+\.\s+)(.*)$")
_HEADING = re.compile(r"^(#{1,6})\s+(.+?)\s*#*\s*$")
_LOCAL_TIMEZONE = ZoneInfo("Pacific/Auckland")


def public_jobs(rows) -> list[dict]:
    """Project stored job rows to the deliberately small public allowlist."""

    if not isinstance(rows, (list, tuple)):
        raise TypeError("job rows must be a list")
    projected = []
    for row in rows:
        if not isinstance(row, dict):
            raise TypeError("each job row must be an object")
        item = {}
        for field in PUBLIC_FIELDS:
            if field in {"primary", "workstream_id"}:
                continue
            value = row.get(field)
            if not isinstance(value, str):
                raise TypeError(f"public job field {field} must be text")
            item[field] = value
        if item["status"] not in JOB_STATUSES:
            raise ValueError("public job status is invalid")
        if type(row.get("primary")) is not bool:
            raise TypeError("public job field primary must be a boolean")
        item["primary"] = row["primary"]
        workstream_id = row.get("workstream_id")
        if workstream_id is not None and (
            not isinstance(workstream_id, str) or not _WORKSTREAM_ID.fullmatch(workstream_id)
        ):
            raise TypeError("public job workstream_id must be a stable id or null")
        item["workstream_id"] = workstream_id
        checklist = row.get("checklist", [])
        if not isinstance(checklist, list):
            raise TypeError("public job checklist must be a list")
        item["checklist"] = []
        for entry in checklist:
            if not isinstance(entry, dict):
                raise TypeError("public checklist entries must be objects")
            item_id, text, done = entry.get("id"), entry.get("text"), entry.get("done")
            if not isinstance(item_id, str) or not isinstance(text, str) or type(done) is not bool:
                raise TypeError("public checklist entries need text id, text and boolean done fields")
            item["checklist"].append({"id": item_id, "text": text, "done": done})
        projected.append(item)
    return projected


def load_public_jobs(database) -> list[dict]:
    """Read lightweight active, blocked, and parked rows in one query pass."""

    from .jobs import JobStore

    store = JobStore(database)
    return [row for row in public_jobs(store.list(primary=True)) if row["status"] in {"active", "blocked", "parked"}]


def public_lanes(rows) -> list[dict]:
    """Select the explicitly public lane state and nested job summaries."""

    if not isinstance(rows, (list, tuple)):
        raise TypeError("lane rows must be a list")
    projected = []
    for row in rows:
        if not isinstance(row, dict):
            raise TypeError("each lane row must be an object")
        worker = row.get("worker")
        status = row.get("status")
        if not _worker_alias(worker) or status not in LANE_STATUSES:
            raise ValueError("lane worker or status is invalid")
        paused = row.get("paused")
        truncated = row.get("jobs_truncated")
        if type(paused) is not bool or type(truncated) is not bool:
            raise TypeError("lane pause and truncation fields must be booleans")
        counts = {}
        for field in ("active_count", "blocked_count", "parked_count", "completed_count"):
            value = row.get(field)
            if isinstance(value, bool) or not isinstance(value, int) or value < 0:
                raise TypeError(f"lane {field} must be a nonnegative integer")
            counts[field] = value
        pause_reason = row.get("pause_reason", "")
        if not isinstance(pause_reason, str):
            raise TypeError("lane pause_reason must be text")
        nested_jobs = public_jobs(row.get("jobs", []))
        if any(job["worker"] != worker or job["status"] == "completed" for job in nested_jobs):
            raise ValueError("lane jobs must belong to the lane and be active, blocked, or parked")
        primary = row.get("primary")
        if primary is not None:
            primary = public_jobs([primary])[0]
            if primary["worker"] != worker or not primary["primary"]:
                raise ValueError("lane primary does not match its worker")
        projected.append(
            {
                "worker": worker,
                "status": status,
                "paused": paused,
                "pause_reason": pause_reason,
                **counts,
                "primary": primary,
                "jobs": nested_jobs,
                "jobs_truncated": truncated,
            }
        )
    return projected


def load_public_lanes(database) -> list[dict]:
    """Read lane and active/blocked/parked job summaries in one batched call."""

    from .jobs import JobStore

    return public_lanes(JobStore(database).lanes(workers=None))


def public_workstreams(snapshot) -> dict:
    """Project SQL-backed workstream state to explicit public map fields."""

    if not isinstance(snapshot, dict):
        raise TypeError("workstream snapshot must be an object")
    raw_workstreams = snapshot.get("workstreams")
    raw_points = snapshot.get("return_points")
    if not isinstance(raw_workstreams, list) or not isinstance(raw_points, list):
        raise TypeError("workstream snapshot needs workstreams and return_points lists")
    workstreams = []
    for row in raw_workstreams:
        if not isinstance(row, dict):
            raise TypeError("workstream records must be objects")
        identifier = row.get("id")
        if not isinstance(identifier, str) or not _WORKSTREAM_ID.fullmatch(identifier):
            raise TypeError("workstream id is invalid")
        fields = {key: row.get(key) for key in ("name", "state", "now", "milestone", "phase_states")}
        if not isinstance(fields["name"], str):
            raise TypeError("public workstream editorial name must be text")
        if any(not isinstance(fields[key], str) for key in ("state", "now", "milestone")):
            raise TypeError("public workstream text fields must be strings")
        if not isinstance(fields["phase_states"], dict) or any(
            not isinstance(name, str) or not isinstance(state, str) for name, state in fields["phase_states"].items()
        ):
            raise TypeError("public phase states must be a text mapping")
        workstreams.append({"id": identifier, **fields})
    points = []
    for row in raw_points:
        if not isinstance(row, dict):
            raise TypeError("return-point records must be objects")
        identifier, name, trigger, state = (row.get(key) for key in ("id", "name", "trigger", "state"))
        if (
            not isinstance(identifier, str)
            or not _WORKSTREAM_ID.fullmatch(identifier)
            or not isinstance(name, str)
            or not isinstance(trigger, str)
            or not isinstance(state, str)
        ):
            raise TypeError("public return-point fields are invalid")
        points.append({"id": identifier, "name": name, "trigger": trigger, "state": state})
    return {"workstreams": workstreams, "return_points": points}


def load_public_workstreams(database, editorial) -> dict:
    """Read curated-ID-matched workstream state, then apply the public allowlist."""

    from .map import WorkstreamStore

    return public_workstreams(WorkstreamStore(database).list(editorial))


def review_model_label(run, attempts) -> str:
    """Return an escaped model label for a subagent run and its exact linked attempt."""

    if not isinstance(run, dict) or run.get("channel") != "subagent":
        return ""
    run_id = run.get("run_id")
    linked = (
        [attempt for attempt in attempts if isinstance(attempt, dict) and attempt.get("run_id") == run_id]
        if isinstance(attempts, list) and run_id is not None
        else []
    )
    model = linked[0].get("model") if len(linked) == 1 else None
    effort = linked[0].get("reasoning_effort") if len(linked) == 1 else None
    if not isinstance(model, str) or not model.strip():
        return '<small class="review-model">Model not recorded</small>'
    parts = [f"Model: {html.escape(model.strip(), quote=True)[:120]}"]
    if isinstance(effort, str) and effort.strip():
        parts.append(html.escape(effort.strip().title(), quote=True)[:40])
    return f'<small class="review-model">{" · ".join(parts)}</small>'


def _safe_href(value: str) -> str | None:
    target = html.unescape(value).strip()
    if not target or "\\" in target or any(ord(char) < 32 for char in target):
        return None
    try:
        parsed = urlsplit(target)
    except ValueError:
        return None
    if parsed.scheme:
        if parsed.scheme.lower() not in {"http", "https"} or not parsed.netloc:
            return None
        if parsed.username or parsed.password:
            return None
        return html.escape(target, quote=True)
    if not target.startswith("/") or target.startswith("//"):
        return None
    path = parsed.path
    decoded = path
    for _ in range(2):
        decoded = unquote(decoded)
    normalized = posixpath.normpath(decoded)
    if (
        not decoded.startswith("/jobs/")
        or normalized != decoded
        or any(segment in {".", ".."} for segment in decoded.split("/"))
    ):
        return None
    return html.escape(target, quote=True)


def _inline_markdown(value: str, *, public: bool = False) -> str:
    pieces = []
    cursor = 0
    for match in _MARKDOWN_TOKEN.finditer(value):
        pieces.append(html.escape(value[cursor : match.start()], quote=True))
        token = match.group(0)
        if token == "\n":
            pieces.append("<br>\n")
        elif token.startswith("!["):
            image = re.fullmatch(r"!\[([^\]]*)\]\(([^\s)]+)\)", token)
            pieces.append(html.escape(image.group(1), quote=True) if image else html.escape(token))
        elif token.startswith("["):
            link = re.fullmatch(r"\[([^\]]*)\]\(([^\s)]+)\)", token)
            href = _safe_href(link.group(2)) if link else None
            if public and href is not None and href.startswith("/"):
                href = None
            if link and href:
                pieces.append(f'<a href="{href}">{html.escape(link.group(1), quote=True)}</a>')
            elif link:
                pieces.append(html.escape(link.group(1), quote=True))
            else:
                pieces.append(html.escape(token, quote=True))
        elif token.startswith("\x60"):
            pieces.append(f"<code>{html.escape(token[1:-1], quote=True)}</code>")
        elif token.startswith(("**", "__")):
            pieces.append(f"<strong>{html.escape(token[2:-2], quote=True)}</strong>")
        elif token.startswith(("*", "_")):
            pieces.append(f"<em>{html.escape(token[1:-1], quote=True)}</em>")
        cursor = match.end()
    pieces.append(html.escape(value[cursor:], quote=True))
    return "".join(pieces)


def _markdown(value, *, public: bool = False) -> str:
    """Render a small Markdown subset while treating source HTML as plain text."""

    if not isinstance(value, str) or not value:
        return "<p></p>"
    blocks = []
    paragraph = []
    list_kind = None
    list_items = []
    code_lines = []
    in_code = False

    def flush_paragraph():
        if paragraph:
            blocks.append(f"<p>{_inline_markdown(chr(10).join(paragraph), public=public)}</p>")
            paragraph.clear()

    def flush_list():
        nonlocal list_kind, list_items
        if list_kind:
            tag = "ul" if list_kind == "unordered" else "ol"
            blocks.append(
                f"<{tag}>{''.join(f'<li>{_inline_markdown(item, public=public)}</li>' for item in list_items)}</{tag}>"
            )
        list_kind, list_items = None, []

    for line in value.splitlines():
        if line.strip().startswith("\x60\x60\x60"):
            flush_paragraph()
            flush_list()
            if in_code:
                blocks.append(f"<pre><code>{html.escape(chr(10).join(code_lines), quote=True)}</code></pre>")
                code_lines = []
                in_code = False
            else:
                in_code = True
            continue
        if in_code:
            code_lines.append(line)
            continue
        if not line.strip():
            flush_paragraph()
            flush_list()
            continue
        heading = _HEADING.match(line)
        if heading:
            flush_paragraph()
            flush_list()
            level = len(heading.group(1))
            blocks.append(f"<h{level}>{_inline_markdown(heading.group(2), public=public)}</h{level}>")
            continue
        item = _CHECKLIST_ITEM.match(line)
        if item:
            flush_paragraph()
            kind = "ordered" if re.match(r"^\s*\d+\.", line) else "unordered"
            if list_kind and kind != list_kind:
                flush_list()
            list_kind = kind
            list_items.append(item.group(1))
            continue
        flush_list()
        paragraph.append(line)
    if in_code:
        blocks.append(f"<pre><code>{html.escape(chr(10).join(code_lines), quote=True)}</code></pre>")
    flush_paragraph()
    flush_list()
    return "\n".join(blocks)


def render_markdown(value) -> str:
    """Render the supported safe Markdown subset used by the local private site."""

    return _markdown(value)


def render_public_inline(value) -> str:
    """Render public text with the same safe inline subset and no local routes."""

    return _inline_markdown(value if isinstance(value, str) else str(value), public=True)


def _private_document(title: str, content: str, *, delivery_link: bool = True) -> str:
    navigation = (
        '<nav class="private-actions" aria-label="Local navigation"><a href="/">← Local Delivery Status page</a></nav>'
        if delivery_link
        else ""
    )
    return (
        '<!doctype html><html lang="en"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width, initial-scale=1">'
        f"<title>{html.escape(title, quote=True)}</title>"
        '<link rel="stylesheet" href="/shared.css">'
        "<style>body{margin:0;background:var(--ash,#d4d4d4);color:var(--ink,#242832);"
        "font:16px ui-sans-serif,system-ui,sans-serif}*{box-sizing:border-box}"
        "main.private-pages{max-width:1440px;margin:auto;padding:1rem clamp(1rem,4vw,3.5rem) 4rem;line-height:1.5}"
        ".private-pages .job-private,.private-pages .job-history-entry{background:var(--paper,#e7e7e7);"
        "border:1px solid var(--line,#b9b9b9);border-radius:0;padding:clamp(1rem,2vw,1.5rem);margin:1rem 0;"
        "overflow-wrap:anywhere;min-width:0}"
        ".private-pages h1{margin:.15rem 0 1rem;font-size:clamp(1.65rem,3vw,2.4rem);line-height:1.2;letter-spacing:-.03em}"
        ".private-pages .job-meta{display:flex;flex-wrap:wrap;gap:.5rem .75rem;align-items:center;"
        "padding:1rem;background:var(--surface-muted,#dedede);border-bottom:1px solid var(--line,#b9b9b9)}"
        ".private-pages .job-meta h1,.private-pages .job-meta h2{flex-basis:100%;margin:0 0 .35rem}"
        ".private-pages .job-meta h2{font-size:1.3rem;line-height:1.25}"
        ".private-pages .job-state,.private-pages .job-badge{display:inline-block;border:1px solid var(--line,#b9b9b9);"
        "padding:.12rem .45rem;background:var(--surface,#f0f0f0);font-size:.75rem;font-weight:750;text-transform:capitalize}"
        ".private-pages .job-alias,.private-pages .job-times{color:var(--muted,#57636c);font-size:.85rem}"
        ".private-pages .job-times{display:flex;flex-wrap:wrap;gap:.35rem 1.5rem;margin:.8rem 0}"
        ".private-pages section{margin-top:1.25rem;padding-top:1rem;border-top:1px solid var(--line,#b9b9b9)}"
        ".private-pages section>h2{margin:0 0 .75rem;font-size:1.2rem;letter-spacing:normal}"
        ".private-pages section.job-brief>h2{font-size:1.4rem}"
        ".private-pages .job-brief-body{font-size:.95rem}"
        ".private-pages .job-brief-body h1{font-size:1.15rem;line-height:1.3;letter-spacing:normal}"
        ".private-pages .job-brief-body h2{font-size:1.05rem;line-height:1.3}"
        ".private-pages .job-brief-body h3{font-size:1rem;line-height:1.3}"
        ".private-pages .job-brief-body h4{font-size:.95rem;line-height:1.3}"
        ".private-pages .job-brief-body h5{font-size:.9rem;line-height:1.3}"
        ".private-pages .job-brief-body h6{font-size:.85rem;line-height:1.3}"
        ".private-pages h3{margin:.75rem 0 .4rem;font-size:1rem}.private-pages p{margin:.5rem 0 .85rem}"
        ".private-pages .job-update,.private-pages .job-note{padding:1rem;margin:.75rem 0;"
        "border:1px solid var(--line,#b9b9b9);background:var(--surface,#f0f0f0)}"
        ".private-pages .inbox-notes .job-note,.private-pages .conversation-card{border-radius:9px}"
        ".private-pages .job-update-head{display:flex;flex-wrap:wrap;justify-content:space-between;gap:.5rem 1rem;"
        "color:var(--muted,#57636c);font-size:.85rem}.private-pages .job-note h3{margin-top:0}"
        ".private-pages .job-checklist{padding-left:1.5rem}.private-pages .job-checklist li{padding:.3rem 0}"
        ".private-pages .job-blocker{border-left:4px solid var(--fire,#c3262d);padding-left:1rem}"
        ".private-pages .job-blocker section{border-top:0}.private-pages pre{white-space:pre-wrap;"
        "padding:1rem;max-width:100%;background:var(--surface-muted,#dedede);overflow-wrap:anywhere}"
        ".private-pages table{display:block;max-width:100%;overflow-x:auto;border-collapse:collapse}"
        ".private-pages th,.private-pages td{padding:.5rem .65rem;border:1px solid var(--line,#b9b9b9);text-align:left}"
        ".private-pages a{color:var(--red-ink,#963149);text-underline-offset:3px}"
        ".private-pages a:focus-visible{outline:3px solid #f6aa61;outline-offset:3px}"
        ".private-pages .private-actions,.private-pages .job-links,.private-pages .job-history-paging{display:flex;"
        "flex-wrap:wrap;gap:.5rem .75rem;align-items:center;margin:1rem 0}"
        ".private-pages .private-actions a,.private-pages .job-links a,.private-pages .job-history-paging a{"
        "display:inline-block;border:1px solid var(--line,#b9b9b9);padding:.4rem .7rem;"
        "background:var(--surface,#f0f0f0);font-size:.85rem;font-weight:650;text-decoration:none}"
        ".private-pages .private-actions a[aria-current=page]{background:var(--surface-muted,#dedede);"
        "border-color:var(--muted,#57636c);color:var(--ink,#242832)}"
        ".private-pages .private-actions a:hover,.private-pages .job-links a:hover,.private-pages .job-history-paging a:hover{"
        "background:var(--surface-muted,#dedede);text-decoration:underline}"
        ".private-pages .private-list{list-style:none;padding:0;display:grid;grid-template-columns:minmax(0,1fr);gap:.75rem;margin:1.25rem 0}"
        ".private-pages .private-list>li{min-width:0;max-width:100%}"
        ".private-pages .conversation-list{gap:.5rem;margin:.75rem 0}"
        ".private-pages .conversation-card{display:flex;align-items:center;justify-content:space-between;min-width:0;max-width:100%;"
        "gap:.5rem 1rem;padding:.65rem .8rem!important}"
        ".private-pages .conversation-copy{min-width:0;flex:1}"
        ".private-pages .job-private.conversation-card .conversation-copy>h3{margin:0;font-size:1rem;line-height:1.3}"
        ".private-pages .conversation-meta{display:flex;flex-wrap:wrap;gap:.15rem .65rem;align-items:baseline;"
        "margin:.2rem 0 0!important;color:var(--muted,#57636c);font-size:.85rem}"
        ".private-pages .conversation-open{display:inline-flex;align-items:center;justify-content:center;"
        "min-height:44px;padding:.45rem .75rem;border:1px solid var(--line,#b9b9b9);"
        "background:var(--surface-muted,#dedede);font-size:.85rem;font-weight:650;text-decoration:none;white-space:nowrap}"
        ".private-pages .private-row{padding:1rem;border:1px solid var(--line,#b9b9b9);background:var(--surface,#f0f0f0)}"
        ".private-pages .inbox-messages .private-row>h3{margin:0 0 .5rem;font-size:1.15rem}"
        ".private-pages .private-list .job-private{margin:0;background:var(--surface,#f0f0f0)}"
        ".private-pages .private-empty{padding:1.2rem;border:1px dashed var(--line,#b9b9b9);"
        "background:var(--surface,#f0f0f0);color:var(--muted,#57636c)}"
        "@media(max-width:760px){.private-pages .job-meta{align-items:flex-start}.private-pages .job-times{"
        "flex-direction:column}.private-pages .job-update-head{justify-content:flex-start}"
        ".private-pages .conversation-card{align-items:flex-start;flex-direction:column}"
        ".private-pages .conversation-open{max-width:100%;white-space:normal}"
        ".private-pages .private-actions a,.private-pages .job-links a{max-width:100%;overflow-wrap:anywhere}}</style>"
        f'</head><body><main class="private-pages">{navigation}'
        f"{content}</main></body></html>"
    )


def _time_metadata(value, *, relative: bool = False) -> str:
    """Render a validated ISO timestamp as an NZ-local time with machine-readable source."""

    timestamp = str(value or "")
    try:
        parsed = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            return html.escape(timestamp, quote=True)
        local = parsed.astimezone(_LOCAL_TIMEZONE)
        display = local.strftime("%d %b %Y %H:%M %Z").lstrip("0")
    except (ValueError, OverflowError):
        return html.escape(timestamp, quote=True)
    rendered = f'<time datetime="{html.escape(timestamp, quote=True)}">{html.escape(display, quote=True)}</time>'
    if relative:
        minutes = max(0, int((datetime.now(timezone.utc) - parsed).total_seconds()) // 60)
        if minutes == 0:
            age = "just now"
        elif minutes < 60:
            age = f"{minutes}m ago"
        elif minutes < 1440:
            age = f"{minutes // 60}h {minutes % 60}m ago"
        else:
            age = f"{minutes // 1440}d {(minutes % 1440) // 60}h ago"
        rendered += f' <span class="relative-age">({age})</span>'
    return rendered


def _job_times(job: dict) -> str:
    items = []
    for label, field in (("Created", "created_at"), ("Last activity", "last_activity_at")):
        timestamp = job.get(field)
        rendered = _time_metadata(timestamp, relative=True) if timestamp else "Not recorded"
        items.append(f"<span><strong>{label}</strong> · {rendered}</span>")
    return f'<div class="job-times">{"".join(items)}</div>'


def _field(label: str, value) -> str:
    if not isinstance(value, str) or not value:
        return ""
    return f"<section><h3>{label}</h3>{_markdown(value)}</section>"


def _render_checklist(checklist) -> str:
    if isinstance(checklist, list):
        entries = []
        for item in checklist:
            if not isinstance(item, dict):
                continue
            marker = "Done" if item.get("done") is True else "Open"
            entries.append(
                f'<li><span class="checklist-state">{marker}</span> '
                f"{html.escape(str(item.get('text', '')), quote=True)}</li>"
            )
        return f'<section><h2>Checklist</h2><ul class="job-checklist">{"".join(entries)}</ul></section>'
    return ""


def render_job(job, history=False) -> str:
    """Render a private job detail page or a bounded history page as safe HTML."""

    if not isinstance(job, dict):
        raise TypeError("job page data must be an object")
    if history:
        return _render_history(job)
    job_id = str(job.get("id", ""))
    encoded_id = quote(job_id, safe="")
    name = html.escape(str(job.get("name", "Job")), quote=True)
    raw_title = str(job.get("title") or job.get("name") or "Job")
    title = html.escape(raw_title, quote=True)
    status = html.escape(str(job.get("status", "unknown")), quote=True)
    primary = " · Primary" if job.get("primary") is True else ""
    content = [
        (
            f'<article class="job-private"><div class="job-meta"><h1>{title}</h1>'
            f'<span class="job-state">{status}</span><span>{html.escape(str(job.get("worker", "")), quote=True)}'
            f'{primary}</span><span class="job-alias">Alias: {name}</span></div>'
        )
    ]
    content.append(_job_times(job))
    for label, field in (("Summary", "summary"), ("Progress", "progress"), ("Blocker", "blocker")):
        section = _field(label, job.get(field))
        if section:
            content.append(f'<div class="job-{field}">{section}</div>')
    content.append(_render_checklist(job.get("checklist", [])))
    content.append(
        f'<section class="job-brief"><h2>Private working brief</h2>'
        f'<div class="job-brief-body">{_markdown(job.get("brief", ""))}</div></section>'
    )
    checkpoint = job.get("latest_checkpoint")
    if isinstance(checkpoint, dict):
        content.append("<section><h2>Latest checkpoint</h2>")
        for label, field in (("Done", "done"), ("Next steps", "next_steps"), ("Blocker", "blocker")):
            section = _field(label, checkpoint.get(field))
            if section:
                content.append(section)
        pointers = checkpoint.get("pointers")
        if pointers is not None:
            rendered = json.dumps(pointers, ensure_ascii=False, sort_keys=True, indent=2, default=str)
            content.append(f"<h3>Handoff pointers</h3><pre>{html.escape(rendered, quote=True)}</pre>")
        content.append("</section>")
    updates = job.get("updates", [])
    content.append("<section><h2>Latest updates</h2>")
    if isinstance(updates, list) and updates:
        for update in updates:
            if not isinstance(update, dict):
                continue
            content.append(
                '<article class="job-update"><div class="job-update-head">'
                f"<strong>{html.escape(str(update.get('kind', 'update')), quote=True)}</strong>"
                f"{_time_metadata(update.get('created_at', ''))}</div>"
                f"{_markdown(update.get('body', ''))}</article>"
            )
    else:
        content.append('<p class="private-empty">No updates recorded.</p>')
    content.append("</section><section><h2>Notes and reminders</h2>")
    notes = job.get("notes", [])
    if isinstance(notes, list) and notes:
        for note in notes:
            if not isinstance(note, dict):
                continue
            labels = " · ".join(str(note.get(key, "")) for key in ("kind", "phase", "status") if note.get(key))
            content.append(
                f'<article class="job-note"><h3>{html.escape(labels, quote=True)}</h3>'
                f"{_markdown(note.get('body', ''))}</article>"
            )
    else:
        content.append('<p class="private-empty">No pending notes or reminders.</p>')
    content.append(f'</section><p class="job-links"><a href="/jobs/{encoded_id}/history">Job history</a></p></article>')
    return _private_document(f"{raw_title} · FireController job", "".join(content))


def _render_history(data: dict) -> str:
    job = data.get("job", data)
    if not isinstance(job, dict):
        raise TypeError("history page needs current job details")
    job_id = quote(str(job.get("id", "")), safe="")
    name = html.escape(str(job.get("name", "Job")), quote=True)
    raw_title = str(job.get("title") or job.get("name") or "Job")
    history_title = f"Job History for {raw_title}"
    entry = data.get("revision_entry")
    if isinstance(entry, dict):
        revision = entry.get("revision", "?")
        body = [
            f'<article class="job-history-entry"><div class="job-meta"><h1>{html.escape(history_title, quote=True)} · Revision {html.escape(str(revision), quote=True)}</h1>',
            f'<span class="job-alias">Alias: {name}</span></div>',
            f"<p>{_time_metadata(entry.get('created_at', ''))}</p>",
        ]
        body.append(
            f'<p class="job-state">{html.escape(str(entry.get("status", "")), quote=True)} · '
            f"{html.escape(str(entry.get('worker', '')), quote=True)}"
            f"{' · Primary' if entry.get('primary') is True else ''}</p>"
            f"<p>{html.escape(str(entry.get('title', '')), quote=True)}</p>"
        )
        body.append(_render_checklist(entry.get("checklist", [])))
        for label, field in (("Summary", "summary"), ("Progress", "progress"), ("Blocker", "blocker")):
            section = _field(label, entry.get(field))
            if section:
                body.append(section)
        body.append(f"<section><h2>Private working brief</h2>{_markdown(entry.get('brief', ''))}</section>")
        body.append(
            f'<p class="job-links"><a href="/jobs/{job_id}/history">All revisions</a> <a href="/jobs/{job_id}">Current job</a></p></article>'
        )
        return _private_document(f"{history_title} · Revision {revision}", "".join(body))

    offset = data.get("offset", 0)
    rows = data.get("history", [])
    content = [
        (
            f'<article class="job-private"><div class="job-meta"><h1>{html.escape(history_title, quote=True)}</h1>'
            f'<span class="job-alias">Alias: {name}</span></div><p>Recent revisions</p><ol class="private-list">'
        )
    ]
    if isinstance(rows, list):
        for row in rows:
            if not isinstance(row, dict):
                continue
            revision = row.get("revision")
            revision_url = f"/jobs/{job_id}/history?revision={quote(str(revision), safe='')}"
            content.append(
                f'<li class="private-row"><a href="{revision_url}">Revision {html.escape(str(revision), quote=True)}</a>'
                f" · {_time_metadata(row.get('created_at', ''))}"
                f" · {html.escape(str(row.get('status', '')), quote=True)}"
                f" · {html.escape(str(row.get('title', '')), quote=True)}</li>"
            )
    if not rows:
        content.append('<li class="private-empty">No revisions recorded.</li>')
    content.append('</ol><nav class="job-history-paging">')
    if type(offset) is int and offset > 0:
        previous = max(0, offset - HISTORY_PAGE_SIZE)
        content.append(f'<a href="/jobs/{job_id}/history?offset={previous}">Newer revisions</a>')
    if isinstance(rows, list) and len(rows) == HISTORY_PAGE_SIZE and type(offset) is int:
        content.append(f'<a href="/jobs/{job_id}/history?offset={offset + HISTORY_PAGE_SIZE}">Older revisions</a>')
    content.append(f'</nav><p class="job-links"><a href="/jobs/{job_id}">Current job</a></p></article>')
    return _private_document(history_title, "".join(content))


def _render_phases(phases) -> str:
    content = []
    if isinstance(phases, dict):
        content.append("<section><h2>Phases</h2><ul>")
        for phase, state in phases.items():
            content.append(
                f"<li><strong>{html.escape(str(phase), quote=True)}</strong> · "
                f"{html.escape(str(state), quote=True)}</li>"
            )
        content.append("</ul></section>")
    return "".join(content)


def render_workstream(record: dict, notes=(), history=False) -> str:
    """Render local-only workstream state and its bounded private map notes."""

    if not isinstance(record, dict):
        raise TypeError("workstream page data must be an object")
    identifier = str(record.get("id", ""))
    if not _WORKSTREAM_ID.fullmatch(identifier):
        raise ValueError("workstream id is invalid")
    encoded_id = quote(identifier, safe="")
    raw_name = str(record.get("name", identifier))
    name = html.escape(raw_name, quote=True)
    if history:
        rows = record.get("history", [])
        entries = []
        if isinstance(rows, list):
            for item in rows:
                if not isinstance(item, dict):
                    continue
                revision = html.escape(str(item.get("revision", "?")), quote=True)
                entries.append(
                    f'<article class="job-history-entry"><h2>Revision {revision}</h2>'
                    f"{_time_metadata(item.get('created_at', ''))}"
                    f"{_field('Status', item.get('state'))}{_field('Where it stands', item.get('now'))}"
                    f"{_field('Next milestone', item.get('milestone'))}"
                    f"{_render_phases(item.get('phase_states', {}))}</article>"
                )
        offset = record.get("offset")
        paging = []
        if type(offset) is int and offset > 0:
            paging.append(
                f'<a href="/workstreams/{encoded_id}/history?offset={max(0, offset - HISTORY_PAGE_SIZE)}">Newer revisions</a>'
            )
        if type(offset) is int and isinstance(rows, list) and len(rows) == HISTORY_PAGE_SIZE:
            paging.append(
                f'<a href="/workstreams/{encoded_id}/history?offset={offset + HISTORY_PAGE_SIZE}">Older revisions</a>'
            )
        history_entries = "".join(entries) if entries else '<p class="private-empty">No revisions recorded.</p>'
        content = (
            f"<h1>{name} history</h1><p>Recent workstream revisions</p>"
            f"{history_entries}"
            f'<nav class="job-history-paging">{"".join(paging)}</nav>'
            f'<p class="job-links"><a href="/workstreams/{encoded_id}">Current workstream</a></p>'
        )
        return _private_document(f"{raw_name} history", content)

    content = [
        f'<article class="job-private"><h1>{name}</h1>',
        f'<p class="job-state">{html.escape(str(record.get("state", "")), quote=True)}</p>',
    ]
    for label, field in (("Where it stands", "now"), ("Next milestone", "milestone")):
        section = _field(label, record.get(field))
        if section:
            content.append(section)
    content.append(_render_phases(record.get("phase_states", {})))
    content.append("<section><h2>Private Overseer notes</h2>")
    note_rows = notes if isinstance(notes, list) else []
    if note_rows:
        for note in note_rows:
            if not isinstance(note, dict):
                continue
            content.append(
                '<article class="job-update"><div class="job-update-head">'
                f"<strong>{html.escape(str(note.get('kind', 'note')), quote=True)} · "
                f"{html.escape(str(note.get('status', '')), quote=True)}</strong>"
                f"{_time_metadata(note.get('created_at', ''))}</div>"
                f"{_markdown(note.get('body', ''))}</article>"
            )
    else:
        content.append('<p class="private-empty">No private map notes.</p>')
    content.append(
        f'</section><p class="job-links"><a href="/workstreams/{encoded_id}/history">'
        "Workstream history</a></p></article>"
    )
    return _private_document(f"{raw_name} · local workstream", "".join(content))


def _inbox_page_url(
    worker: str, *, messages: bool, inbox_offset: int = 0, notes_offset: int = 0, preserve_zero_offset: bool = False
) -> str:
    base = f"/inbox/{quote(worker, safe='')}"
    query = []
    if messages:
        query.append("view=messages")
    if inbox_offset > 0 or preserve_zero_offset:
        query.append(f"offset={inbox_offset}")
    if notes_offset > 0:
        query.append(f"notes_offset={notes_offset}")
    return base if not query else f"{base}?{'&amp;'.join(query)}"


def _inbox_views(worker: str, *, messages: bool = False, notes_offset: int = 0) -> str:
    conversations = ' aria-current="page"' if not messages else ""
    all_messages = ' aria-current="page"' if messages else ""
    conversations_url = _inbox_page_url(worker, messages=False, notes_offset=notes_offset)
    messages_url = _inbox_page_url(worker, messages=True, notes_offset=notes_offset)
    return (
        f'<nav class="private-actions" aria-label="Inbox views"><a href="{conversations_url}"{conversations}>Conversations</a> '
        f'<a href="{messages_url}"{all_messages}>All messages</a></nav>'
    )


def _inbox_direction(worker: str, message: dict) -> str:
    if message.get("recipient") == worker:
        return "Self" if message.get("author") == worker else "Incoming"
    return "Outgoing"


def _render_inbox_notes(
    worker: str, notes, *, available: bool, notes_offset: int, has_more: bool, messages: bool, inbox_offset: int
) -> str:
    content = ['<section class="inbox-notes"><h2>Pending notes and reminders</h2>']
    if not available:
        content.append('<p class="private-empty">Pending notes are unavailable.</p>')
    elif not notes:
        content.append('<p class="private-empty">No pending notes or reminders.</p>')
    else:
        for note in notes:
            if not isinstance(note, dict):
                continue
            labels = [str(note.get("status", "")), str(note.get("kind", "note"))]
            phase = note.get("phase")
            if isinstance(phase, str) and phase:
                labels.append(f"Phase: {phase}")
            metadata = [f'<span class="job-badge">{html.escape(labels[0], quote=True)}</span>']
            metadata.extend(html.escape(label, quote=True) for label in labels[1:])
            job = note.get("job")
            if isinstance(job, str) and job:
                escaped_job = html.escape(job, quote=True)
                if _JOB_ID.fullmatch(job) and job not in {".", ".."}:
                    job_ref = f'<a href="/jobs/{quote(job, safe="")}">Job: {escaped_job}</a>'
                else:
                    job_ref = f"Job: {escaped_job}"
                metadata.append(job_ref)
            content.append(
                '<article class="job-note"><h3>' + " · ".join(metadata) + "</h3>"
                f"<div>{_markdown(note.get('body', ''))}</div></article>"
            )
        if has_more:
            content.append(
                '<p class="job-note-truncation">Showing up to 50 pending notes; older notes are available below.</p>'
            )
    if available:
        paging = []
        if notes_offset > 0:
            previous = max(0, notes_offset - INBOX_NOTE_PAGE_SIZE)
            previous_url = _inbox_page_url(
                worker,
                messages=messages,
                inbox_offset=inbox_offset,
                notes_offset=previous,
            )
            paging.append(f'<a href="{previous_url}">Newer notes</a>')
        if has_more:
            next_url = _inbox_page_url(
                worker,
                messages=messages,
                inbox_offset=inbox_offset,
                notes_offset=notes_offset + INBOX_NOTE_PAGE_SIZE,
            )
            paging.append(f'<a href="{next_url}">Older notes</a>')
        content.append(f'<nav class="job-history-paging" aria-label="Pending note pages">{"".join(paging)}</nav>')
    content.append("</section>")
    return "".join(content)


def render_inbox_conversations(
    worker: str,
    conversations,
    *,
    offset: int = 0,
    unread_count: int | None = None,
    has_more: bool = False,
    pending_notes=(),
    notes_available: bool = True,
    notes_has_more: bool = False,
    notes_offset: int = 0,
) -> str:
    """Render reply conversations ordered by this worker's latest participation."""

    if not _worker_alias(worker) or not isinstance(conversations, list):
        raise ValueError("inbox conversation data is invalid")
    base = f"/inbox/{quote(worker, safe='')}"
    raw_title = f"{worker} inbox"
    title = html.escape(raw_title, quote=True)
    count = (
        ""
        if unread_count is None
        else (f"<p>{unread_count} unread incoming {'message' if unread_count == 1 else 'messages'}.</p>")
    )
    entries = []
    for conversation in conversations:
        root_id = conversation["root_id"]
        message = conversation["latest_message"]
        if not _JOB_ID.fullmatch(root_id):
            continue
        author = html.escape(str(message.get("author") or "Unspecified sender"), quote=True)
        recipient = html.escape(str(message.get("recipient") or "Unspecified recipient"), quote=True)
        message_count = conversation["message_count"]
        unread_message_count = conversation["unread_count"]
        entries.append(
            '<li class="conversation-entry"><article class="job-private conversation-card">'
            f'<div class="conversation-copy"><h3>{author} → {recipient}</h3>'
            '<p class="conversation-meta">'
            f"<span>{message_count} {'message' if message_count == 1 else 'messages'}</span>"
            f"<span>{unread_message_count} unread incoming "
            f"{'message' if unread_message_count == 1 else 'messages'}</span>"
            f"<span>{_inbox_direction(worker, message)}</span>"
            f"<span>Latest {_time_metadata(message.get('created_at', 'Message'))}</span>"
            "</p></div>"
            f'<a class="conversation-open" href="{base}/thread/{quote(root_id, safe="")}">Open conversation</a>'
            "</article></li>"
        )
    rendered_entries = "".join(entries) if entries else '<li class="private-empty">No conversations on this page.</li>'
    notes_section = _render_inbox_notes(
        worker,
        pending_notes,
        available=notes_available,
        notes_offset=notes_offset,
        has_more=notes_has_more,
        messages=False,
        inbox_offset=offset,
    )
    content = (
        f'<article class="job-private"><h1>{title}</h1>'
        f"{notes_section}<section class=\"inbox-conversations\"><h2>Conversations</h2>"
        f'{_inbox_views(worker, notes_offset=notes_offset)}{count}'
        "<p>Conversations are ordered by your latest incoming or outgoing message. "
        "Opening this list or a conversation does not mark messages seen or acknowledge them.</p>"
        f'<ol class="private-list conversation-list">{rendered_entries}</ol>'
        '<nav class="job-history-paging" aria-label="Conversation pages">'
    )
    if offset > 0:
        content += f'<a href="{_inbox_page_url(worker, messages=False, inbox_offset=max(0, offset - HISTORY_PAGE_SIZE), notes_offset=notes_offset, preserve_zero_offset=True)}">Newer conversations</a> '
    if has_more:
        content += f'<a href="{_inbox_page_url(worker, messages=False, inbox_offset=offset + HISTORY_PAGE_SIZE, notes_offset=notes_offset)}">Older conversations</a>'
    return _private_document(f"{raw_title} · FireController", content + "</nav></section></article>", delivery_link=False)


def render_inbox(
    worker: str,
    messages,
    *,
    offset: int = 0,
    unread_count: int | None = None,
    has_more: bool = False,
    pending_notes=(),
    notes_available: bool = True,
    notes_has_more: bool = False,
    notes_offset: int = 0,
) -> str:
    """Render a bounded private worker inbox without acknowledging messages."""

    if not _worker_alias(worker):
        raise ValueError("worker alias is invalid")
    encoded_worker = quote(worker, safe="")
    if not isinstance(messages, list):
        raise TypeError("inbox messages must be a list")
    raw_title = f"{worker} inbox"
    title = html.escape(raw_title, quote=True)
    count = (
        ""
        if unread_count is None
        else (f"<p>{unread_count} unread incoming {'message' if unread_count == 1 else 'messages'}.</p>")
    )
    entries = []
    for message in messages:
        if not isinstance(message, dict):
            continue
        message_id = str(message.get("id", ""))
        if not _JOB_ID.fullmatch(message_id):
            continue
        message_url = f"/inbox/{encoded_worker}/{quote(message_id, safe='')}"
        if message.get("recipient") != worker:
            message_url = (
                f"/inbox/{encoded_worker}/thread/{quote(message_id, safe='')}"
                f"?focus={quote(message_id, safe='')}#message-{quote(message_id, safe='')}"
            )
        state = "Acknowledged" if message.get("acknowledged_at") else ("Seen" if message.get("seen_at") else "Unread")
        refs = []
        if isinstance(message.get("job"), str) and message["job"]:
            refs.append(f"Job: {html.escape(message['job'], quote=True)}")
        if isinstance(message.get("pr"), int) and not isinstance(message.get("pr"), bool):
            refs.append(f"PR: #{message['pr']}")
        if isinstance(message.get("reply_to"), str) and message["reply_to"]:
            parent_id = message["reply_to"]
            parent_url = (
                f"/inbox/{encoded_worker}/thread/{quote(parent_id, safe='')}"
                f"?focus={quote(parent_id, safe='')}#message-{quote(parent_id, safe='')}"
            )
            refs.append(f'Reply to <a href="{parent_url}">{html.escape(parent_id, quote=True)}</a>')
        entries.append(
            '<li class="private-row">'
            f"<h3>{html.escape(str(message.get('author') or 'Unspecified sender'), quote=True)} → "
            f"{html.escape(str(message.get('recipient') or 'Unspecified recipient'), quote=True)}</h3>"
            f"<p>{_time_metadata(message.get('created_at', 'Message'))} · {_inbox_direction(worker, message)} · "
            f'<span class="job-badge">{state}</span>{(" · " + " · ".join(refs)) if refs else ""}</p>'
            f'<p class="job-links"><a href="{message_url}">'
            f"{'Read message' if message.get('recipient') == worker else 'View sent message'}</a>"
            f'<a href="/inbox/{encoded_worker}/thread/{quote(message_id, safe="")}">Conversation</a></p></li>'
        )
    rendered_entries = "".join(entries) if entries else '<li class="private-empty">No messages on this page.</li>'
    notes_section = _render_inbox_notes(
        worker,
        pending_notes,
        available=notes_available,
        notes_offset=notes_offset,
        has_more=notes_has_more,
        messages=True,
        inbox_offset=offset,
    )
    content = (
        f'<article class="job-private"><h1>{title}</h1>'
        f"{notes_section}<section class=\"inbox-messages\"><h2>Messages</h2>"
        f'{_inbox_views(worker, messages=True, notes_offset=notes_offset)}{count}'
        "<p>Incoming and outgoing messages are shown newest first. Seen and acknowledged state belongs to the recipient. "
        "Messages remain private. Opening this list does not mark them seen or acknowledge them.</p>"
        f'<ol class="private-list">{rendered_entries}</ol><nav class="job-history-paging" aria-label="Message pages">'
    )
    if offset > 0:
        content += (
            f'<a href="{_inbox_page_url(worker, messages=True, inbox_offset=max(0, offset - HISTORY_PAGE_SIZE), notes_offset=notes_offset, preserve_zero_offset=True)}">'
            "Newer messages</a> "
        )
    if has_more:
        content += (
            f'<a href="{_inbox_page_url(worker, messages=True, inbox_offset=offset + HISTORY_PAGE_SIZE, notes_offset=notes_offset)}">'
            "Older messages</a>"
        )
    content += "</nav></section></article>"
    return _private_document(f"{raw_title} · FireController", content, delivery_link=False)


def render_inbox_thread(worker: str, messages, *, message_id: str, offset: int = 0, has_more: bool = False) -> str:
    """Render a bounded private conversation without changing message state."""

    if not _worker_alias(worker):
        raise ValueError("worker alias is invalid")
    if not _JOB_ID.fullmatch(message_id):
        raise ValueError("message id is invalid")
    if not isinstance(messages, list):
        raise TypeError("thread messages must be a list")
    encoded_worker = quote(worker, safe="")
    encoded_id = quote(message_id, safe="")
    title_text = f"{worker} conversation"
    title = html.escape(title_text, quote=True)
    entries = []
    for message in messages:
        if not isinstance(message, dict):
            continue
        current_id = str(message.get("id", ""))
        if not _JOB_ID.fullmatch(current_id):
            continue
        state = "Acknowledged" if message.get("acknowledged_at") else ("Seen" if message.get("seen_at") else "Unread")
        recipient = html.escape(str(message.get("recipient") or "Unspecified recipient"), quote=True)
        author = html.escape(str(message.get("author") or "Unspecified sender"), quote=True)
        details = [f"{_time_metadata(message.get('created_at', 'Message'))} · {author} → {recipient} · {state}"]
        if isinstance(message.get("job"), str) and message["job"]:
            details.append(f"Job: {html.escape(message['job'], quote=True)}")
        if isinstance(message.get("pr"), int) and not isinstance(message.get("pr"), bool):
            details.append(f"PR: #{message['pr']}")
        reply_to = message.get("reply_to")
        if isinstance(reply_to, str) and reply_to and _JOB_ID.fullmatch(reply_to):
            parent_url = (
                f"/inbox/{encoded_worker}/thread/{quote(reply_to, safe='')}"
                f"?focus={quote(reply_to, safe='')}#message-{quote(reply_to, safe='')}"
            )
            details.append(f'<span>Reply to <a href="{parent_url}">{html.escape(reply_to, quote=True)}</a></span>')
        entries.append(
            f'<li id="message-{html.escape(current_id, quote=True)}"><article class="job-private inbox-thread-message">'
            f"<p>{' · '.join(details)}</p>{_markdown(message.get('body', ''))}</article></li>"
        )
    rendered_entries = "".join(entries) if entries else '<li class="private-empty">No messages on this page.</li>'
    content = (
        f'<article class="job-private"><h1>{title}</h1>'
        "<p>Messages from every recipient are shown in chronological order. Opening this conversation does not mark messages seen or acknowledge them.</p>"
        f'<ol class="private-list">{rendered_entries}</ol><nav class="job-history-paging" aria-label="Conversation message pages">'
    )
    base = f"/inbox/{encoded_worker}/thread/{encoded_id}"
    if offset > 0:
        content += f'<a href="{base}?offset={max(0, offset - HISTORY_PAGE_SIZE)}">Earlier messages</a> '
    if has_more:
        content += f'<a href="{base}?offset={offset + HISTORY_PAGE_SIZE}">Later messages</a>'
    content += f'</nav><p class="job-links"><a href="/inbox/{encoded_worker}">Back to {html.escape(worker, quote=True)} inbox</a></p></article>'
    return _private_document(f"{title_text} · FireController", content, delivery_link=False)


def render_worker_history(worker: str, jobs) -> str:
    """Render every worker job from the lightweight read-only job projection."""

    if not _worker_alias(worker):
        raise ValueError("worker alias is invalid")
    if not isinstance(jobs, (list, tuple)):
        raise TypeError("worker jobs must be a list")
    entries = []
    ordered_jobs = [job for job in jobs if isinstance(job, dict)]
    ordered_jobs.sort(
        key=lambda job: (job.get("primary") is True, str(job.get("last_activity_at") or "")), reverse=True
    )
    for job in ordered_jobs:
        job_id = str(job.get("id", ""))
        if not _JOB_ID.fullmatch(job_id):
            continue
        status = job.get("status")
        if status not in JOB_STATUSES:
            raise ValueError("worker job status is invalid")
        name = html.escape(str(job.get("name", "")), quote=True)
        title = html.escape(str(job.get("title", "")), quote=True)
        summary = job.get("summary")
        summary_html = (
            f"<p><strong>Summary</strong> · {_markdown(summary)}</p>" if isinstance(summary, str) and summary else ""
        )
        primary = "<span>Primary</span>" if job.get("primary") is True else ""
        encoded_job = quote(job_id, safe="")
        entries.append(
            '<article class="job-private worker-job-entry"><div class="job-meta">'
            f'<h2><a href="/jobs/{encoded_job}">{title}</a></h2>'
            f'<span class="job-state">{html.escape(status, quote=True)}</span>'
            f"<span>{name}</span>{primary}</div>{_job_times(job)}{summary_html}"
            f'<p class="job-links"><a href="/jobs/{encoded_job}/history">Job history</a></p></article>'
        )
    content = (
        f'<article class="job-private"><h1>{html.escape(worker, quote=True)} worker history</h1>'
        "<p>All statuses are shown. The current primary is listed first, followed by the jobs with the most recent activity.</p>"
        f"{''.join(entries) if entries else '<p class=private-empty>No jobs recorded.</p>'}</article>"
    )
    return _private_document(f"{worker} worker history · FireController", content, delivery_link=False)


def render_inbox_message(worker: str, message: dict) -> str:
    """Render one explicitly opened inbox message; this route may mark it seen."""

    if not _worker_alias(worker) or not isinstance(message, dict):
        raise ValueError("inbox message route data is invalid")
    message_id = str(message.get("id", ""))
    if not _JOB_ID.fullmatch(message_id):
        raise ValueError("message id is invalid")
    encoded_worker = quote(worker, safe="")
    content = [
        '<article class="job-private"><h1>Private worker message</h1>',
        (
            f"<p>{_time_metadata(message.get('created_at', ''))} · "
            f"{html.escape(str(message.get('author') or 'Unspecified sender'), quote=True)}</p>"
        ),
    ]
    for label, key in (("Job", "job"), ("PR", "pr")):
        value = message.get(key)
        if value is not None and value != "":
            display = f"#{value}" if key == "pr" else str(value)
            content.append(f"<p><strong>{label}</strong> · {html.escape(display, quote=True)}</p>")
    reply_to = message.get("reply_to")
    if isinstance(reply_to, str) and reply_to and _JOB_ID.fullmatch(reply_to):
        parent_url = (
            f"/inbox/{encoded_worker}/thread/{quote(reply_to, safe='')}"
            f"?focus={quote(reply_to, safe='')}#message-{quote(reply_to, safe='')}"
        )
        content.append(
            f'<p><strong>Reply to</strong> · <a href="{parent_url}">{html.escape(reply_to, quote=True)}</a></p>'
        )
    content.append(_markdown(message.get("body", "")))
    content.append(
        f'<p class="job-links"><a href="/inbox/{encoded_worker}/thread/{quote(message_id, safe="")}">Conversation</a> '
        f'<a href="/inbox/{encoded_worker}">Back to {html.escape(worker, quote=True)} inbox</a></p></article>'
    )
    return _private_document(f"{worker} inbox message", "".join(content), delivery_link=False)


def _response(status: int, document: str) -> tuple[int, dict[str, str], bytes]:
    body = document.encode("utf-8")
    headers = {
        "Content-Type": "text/html; charset=utf-8",
        "Content-Length": str(len(body)),
        "Cache-Control": "no-store",
        "X-Content-Type-Options": "nosniff",
        "Referrer-Policy": "no-referrer",
        "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
    }
    return status, headers, body


def _error(status: int, message: str) -> tuple[int, dict[str, str], bytes]:
    return _response(status, _private_document("FireController job", f"<h1>{status}</h1><p>{html.escape(message)}</p>"))


def _query(path, allowed: set[str]):
    try:
        query = parse_qs(path.query, keep_blank_values=True, strict_parsing=True) if path.query else {}
    except ValueError:
        return None, _error(400, "Invalid page query")
    if any(len(values) != 1 for values in query.values()) or set(query) - allowed:
        return None, _error(400, "Unsupported page query")
    return query, None


def _history_offset(query: dict) -> int | tuple[int, tuple]:
    raw_offset = query.get("offset", ["0"])[0]
    if not re.fullmatch(r"(?:0|[1-9][0-9]*)", raw_offset):
        return 0, _error(400, "Offset must be a nonnegative integer")
    offset = int(raw_offset)
    if offset > MAX_HISTORY_OFFSET:
        return 0, _error(400, "Offset is outside the supported range")
    return offset


def _pending_worker_notes(jobs_store, worker: str, offset: int) -> tuple[list[dict], bool, bool]:
    """Return one bounded page of pending notes assigned to the worker or their jobs."""

    if jobs_store is None:
        return [], False, False
    page_limit = INBOX_NOTE_PAGE_SIZE + 1
    try:
        rows = jobs_store.notes(
            worker=worker,
            status="pending",
            limit=page_limit,
            offset=offset,
            include_assigned_jobs=True,
        )
        if not isinstance(rows, list) or any(not isinstance(note, dict) for note in rows):
            raise TypeError("pending notes must be a list of objects")
        return rows[:INBOX_NOTE_PAGE_SIZE], len(rows) > INBOX_NOTE_PAGE_SIZE, True
    except (OSError, RuntimeError, TypeError, ValueError, KeyError):
        # Keep the inbox usable when the job schema is absent or incompatible.
        return [], False, False


def _private_job_route(parsed, store):
    segments = parsed.path.split("/")
    if len(segments) not in {3, 4} or not segments[2]:
        return _error(404, "Job page not found")
    try:
        job_id = unquote(segments[2], errors="strict")
    except UnicodeDecodeError:
        return _error(400, "Invalid job identifier")
    if not _JOB_ID.fullmatch(job_id) or job_id in {".", ".."}:
        return _error(400, "Invalid job identifier")
    is_history = len(segments) == 4 and segments[3] == "history"
    if len(segments) == 4 and not is_history:
        return _error(404, "Job page not found")
    query, error = _query(parsed, {"offset", "revision"} if is_history else set())
    if error:
        return error
    if is_history and "offset" in query and "revision" in query:
        return _error(400, "Unsupported history query")
    if store is None:
        return _error(404, "Job pages are not enabled")
    try:
        if not is_history:
            return _response(200, render_job(store.get(job_id, latest=10)))
        current = store.get(job_id, latest=1)
        if "revision" in query:
            raw_revision = query["revision"][0]
            if not re.fullmatch(r"[1-9][0-9]*", raw_revision):
                return _error(400, "Revision must be a positive integer")
            entry = store.history(job_id, revision=int(raw_revision))
            return _response(200, render_job({"job": current, "revision_entry": entry}, history=True))
        offset = _history_offset(query)
        if isinstance(offset, tuple):
            return offset[1]
        revisions = store.history(job_id, limit=HISTORY_PAGE_SIZE, offset=offset)
        return _response(200, render_job({"job": current, "history": revisions, "offset": offset}, history=True))
    except (KeyError, LookupError, ValueError):
        return _error(404, "Job page not found")
    except (OSError, RuntimeError, TypeError):
        return _error(500, "Job page could not be loaded")


def _private_workstream_route(parsed, jobs_store, workstream_store, editorial):
    segments = parsed.path.split("/")
    if len(segments) not in {3, 4} or not segments[2]:
        return _error(404, "Workstream page not found")
    workstream_id = unquote(segments[2])
    if not _WORKSTREAM_ID.fullmatch(workstream_id):
        return _error(400, "Invalid workstream identifier")
    is_history = len(segments) == 4 and segments[3] == "history"
    if len(segments) == 4 and not is_history:
        return _error(404, "Workstream page not found")
    query, error = _query(parsed, {"offset", "revision"} if is_history else set())
    if error:
        return error
    if is_history and "offset" in query and "revision" in query:
        return _error(400, "Unsupported history query")
    if workstream_store is None or editorial is None:
        return _error(404, "Workstream pages are not enabled")
    try:
        if is_history:
            if "revision" in query:
                raw_revision = query["revision"][0]
                if not re.fullmatch(r"[1-9][0-9]*", raw_revision):
                    return _error(400, "Revision must be a positive integer")
                entry = workstream_store.history("workstream", workstream_id, revision=int(raw_revision))
                entry["name"] = workstream_store.get(workstream_id, editorial)["name"]
                return _response(
                    200,
                    render_workstream({"id": workstream_id, "name": entry["name"], "history": [entry]}, history=True),
                )
            offset = _history_offset(query)
            if isinstance(offset, tuple):
                return offset[1]
            records = workstream_store.history("workstream", workstream_id, limit=HISTORY_PAGE_SIZE, offset=offset)
            current = workstream_store.get(workstream_id, editorial)
            return _response(200, render_workstream({**current, "history": records, "offset": offset}, history=True))
        record = workstream_store.get(workstream_id, editorial)
        notes = [] if jobs_store is None else jobs_store.notes(phase=workstream_id, status=None, limit=50, offset=0)
        return _response(200, render_workstream(record, notes))
    except (KeyError, LookupError, ValueError):
        return _error(404, "Workstream page not found")
    except (OSError, RuntimeError, TypeError):
        return _error(500, "Workstream page could not be loaded")


def _private_inbox_route(parsed, inbox_store, jobs_store):
    segments = parsed.path.split("/")
    if len(segments) not in {3, 4, 5} or not segments[2]:
        return _error(404, "Inbox page not found")
    worker = unquote(segments[2])
    if not _worker_alias(worker):
        return _error(400, "Invalid worker inbox")
    if inbox_store is None:
        return _error(404, "Worker inbox is not enabled")
    is_message = len(segments) == 4 and bool(segments[3])
    is_thread = len(segments) == 5 and segments[3] == "thread" and bool(segments[4])
    if (len(segments) == 4 and not is_message) or (len(segments) == 5 and not is_thread):
        return _error(404, "Inbox message not found")
    allowed_query = (
        {"focus", "offset"} if is_thread else {"view", "offset", "notes_offset"} if not is_message else set()
    )
    query, error = _query(parsed, allowed_query)
    if error:
        return error
    if is_thread and "focus" in query and "offset" in query:
        return _error(400, "Choose either a focus message or a page offset")
    try:
        if is_message:
            message_id = unquote(segments[3])
            if not _JOB_ID.fullmatch(message_id):
                return _error(400, "Invalid inbox message identifier")
            # Opening an explicit message route is the explicit read action.
            message = inbox_store.read(message_id, recipient=worker)
            return _response(200, render_inbox_message(worker, message))
        if is_thread:
            message_id = unquote(segments[4])
            if not _JOB_ID.fullmatch(message_id):
                return _error(400, "Invalid inbox message identifier")
            focus_id = query.get("focus", [None])[0]
            if focus_id is not None and not _JOB_ID.fullmatch(focus_id):
                return _error(400, "Invalid focus message identifier")
            offset = _history_offset(query) if focus_id is None else 0
            if isinstance(offset, tuple):
                return offset[1]
            page = inbox_store.thread_page(
                message_id, limit=HISTORY_PAGE_SIZE, offset=offset, focus_id=focus_id, worker=worker
            )
            return _response(
                200,
                render_inbox_thread(
                    worker,
                    page["messages"],
                    message_id=message_id,
                    offset=page["offset"],
                    has_more=page["has_more"],
                ),
            )
        view = query.get("view", ["conversations"])[0]
        if view not in {"conversations", "messages"}:
            return _error(400, "Unsupported inbox view")
        offset = _history_offset(query)
        if isinstance(offset, tuple):
            return offset[1]
        notes_offset = _history_offset({"offset": query.get("notes_offset", ["0"])})
        if isinstance(notes_offset, tuple):
            return notes_offset[1]
        pending_notes, notes_has_more, notes_available = _pending_worker_notes(jobs_store, worker, notes_offset)
        unread = inbox_store.unread_count(worker)
        if view == "messages":
            page = inbox_store.messages_page(worker, limit=HISTORY_PAGE_SIZE, offset=offset)
            document = render_inbox(
                worker,
                page["messages"],
                offset=offset,
                unread_count=unread,
                has_more=page["has_more"],
                pending_notes=pending_notes,
                notes_available=notes_available,
                notes_has_more=notes_has_more,
                notes_offset=notes_offset,
            )
        else:
            page = inbox_store.conversations_page(worker, limit=HISTORY_PAGE_SIZE, offset=offset)
            document = render_inbox_conversations(
                worker,
                page["conversations"],
                offset=offset,
                unread_count=unread,
                has_more=page["has_more"],
                pending_notes=pending_notes,
                notes_available=notes_available,
                notes_has_more=notes_has_more,
                notes_offset=notes_offset,
            )
        return _response(200, document)
    except (KeyError, LookupError, ValueError):
        return _error(404, "Inbox page not found")
    except (OSError, RuntimeError, TypeError):
        return _error(500, "Inbox page could not be loaded")


def _private_worker_jobs_route(parsed, store):
    segments = parsed.path.split("/")
    if len(segments) != 4 or segments[3] != "jobs" or not segments[2]:
        return _error(404, "Worker history page not found")
    if not segments[2].startswith("@"):
        return _error(404, "Worker history page not found")
    try:
        worker = unquote(segments[2][1:], errors="strict")
    except UnicodeDecodeError:
        return _error(400, "Invalid worker history")
    if not _worker_alias(worker):
        return _error(400, "Invalid worker history")
    _query_args, error = _query(parsed, set())
    if error:
        return error
    if store is None:
        return _error(404, "Worker history is not enabled")
    try:
        return _response(200, render_worker_history(worker, store.list(worker=worker)))
    except (KeyError, LookupError, ValueError):
        return _error(404, "Worker history page not found")
    except (OSError, RuntimeError, TypeError):
        return _error(500, "Worker history could not be loaded")


def private_route(path, store, *, inbox=None, workstreams=None, editorial=None):
    """Serve private job, worker-history, workstream-note, and inbox routes."""

    try:
        parsed = urlsplit(path)
    except (TypeError, ValueError):
        return None
    if parsed.path.startswith("/jobs/"):
        return _private_job_route(parsed, store)
    if parsed.path.startswith("/workers/"):
        return _private_worker_jobs_route(parsed, store)
    if parsed.path.startswith("/workstreams/"):
        return _private_workstream_route(parsed, store, workstreams, editorial)
    if parsed.path.startswith("/inbox/"):
        return _private_inbox_route(parsed, inbox, store)
    return None
