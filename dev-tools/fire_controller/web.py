"""Small, explicit public and private web views for FireController jobs."""

from __future__ import annotations

import html
import json
import posixpath
import re
from urllib.parse import parse_qs, quote, unquote, urlsplit

from .context import worker_alias as _worker_alias

PUBLIC_FIELDS = (
    "id", "name", "worker", "workstream_id", "title", "status", "primary", "summary", "progress", "blocker",
)
JOB_STATUSES = frozenset({"active", "parked", "blocked", "completed"})
LANE_STATUSES = frozenset({"active", "blocked", "paused", "idle"})
HISTORY_PAGE_SIZE = 50
MAX_HISTORY_OFFSET = 1_000_000
_JOB_ID = re.compile(r"[A-Za-z0-9._-]{1,128}\Z")
_WORKSTREAM_ID = re.compile(r"[a-z0-9][a-z0-9-]{0,99}\Z")
_MARKDOWN_TOKEN = re.compile(
    r"(!?\[[^\]\n]*\]\([^\s)]+\)|\x60[^\x60\n]*\x60|\*\*[^*\n]+\*\*|"
    r"\*[^*\n]+\*|__[^_\n]+__|_[^_\n]+_|\n)"
)
_CHECKLIST_ITEM = re.compile(r"^\s*(?:[-*+]\s+|\d+\.\s+)(.*)$")
_HEADING = re.compile(r"^(#{1,6})\s+(.+?)\s*#*\s*$")



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
        projected.append({
            "worker": worker,
            "status": status,
            "paused": paused,
            "pause_reason": pause_reason,
            **counts,
            "primary": primary,
            "jobs": nested_jobs,
            "jobs_truncated": truncated,
        })
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
            not isinstance(name, str) or not isinstance(state, str)
            for name, state in fields["phase_states"].items()
        ):
            raise TypeError("public phase states must be a text mapping")
        workstreams.append({"id": identifier, **fields})
    points = []
    for row in raw_points:
        if not isinstance(row, dict):
            raise TypeError("return-point records must be objects")
        identifier, name, trigger, state = (row.get(key) for key in ("id", "name", "trigger", "state"))
        if (not isinstance(identifier, str) or not _WORKSTREAM_ID.fullmatch(identifier)
                or not isinstance(name, str) or not isinstance(trigger, str) or not isinstance(state, str)):
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
    linked = [
        attempt for attempt in attempts if isinstance(attempt, dict)
        and attempt.get("run_id") == run_id
    ] if isinstance(attempts, list) and run_id is not None else []
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
    if (not decoded.startswith("/jobs/") or normalized != decoded
            or any(segment in {".", ".."} for segment in decoded.split("/"))):
        return None
    return html.escape(target, quote=True)


def _inline_markdown(value: str, *, public: bool = False) -> str:
    pieces = []
    cursor = 0
    for match in _MARKDOWN_TOKEN.finditer(value):
        pieces.append(html.escape(value[cursor:match.start()], quote=True))
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
            blocks.append(f'<p>{_inline_markdown(chr(10).join(paragraph), public=public)}</p>')
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
                blocks.append(f'<pre><code>{html.escape(chr(10).join(code_lines), quote=True)}</code></pre>')
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
        blocks.append(f'<pre><code>{html.escape(chr(10).join(code_lines), quote=True)}</code></pre>')
    flush_paragraph()
    flush_list()
    return "\n".join(blocks)


def render_markdown(value) -> str:
    """Render the supported safe Markdown subset used by the local private site."""

    return _markdown(value)


def render_public_inline(value) -> str:
    """Render public text with the same safe inline subset and no local routes."""

    return _inline_markdown(value if isinstance(value, str) else str(value), public=True)


def _private_document(title: str, content: str) -> str:
    return (
        '<!doctype html><html lang="en"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width, initial-scale=1">'
        f'<title>{html.escape(title, quote=True)}</title>'
        '<link rel="stylesheet" href="/shared.css">'
        '<style>body{margin:0;background:#e5e7eb;color:#252a32;font:16px system-ui,sans-serif}'
        'main{max-width:900px;margin:auto;padding:1.5rem}.job-private,.job-history-entry{background:#fff;'
        'border:1px solid #d5d9df;border-radius:12px;padding:1rem;margin:1rem 0;overflow-wrap:anywhere}'
        '.job-meta,.job-links,.job-update-head{display:flex;flex-wrap:wrap;gap:.5rem 1rem;align-items:center}'
        '.job-state{font-weight:700;text-transform:capitalize}.job-checklist{padding-left:1.5rem}'
        '.job-blocker{border-left:3px solid #a5314d;padding-left:.75rem}.job-private pre{white-space:pre-wrap}'
        'a{color:#8b1e3f}.job-history-paging{display:flex;justify-content:space-between;margin:1rem 0}</style>'
        '</head><body><main><p><a href="/">← Local status page</a></p>'
        f'{content}</main></body></html>'
    )


def _field(label: str, value) -> str:
    if not isinstance(value, str) or not value:
        return ""
    return f'<section><h3>{label}</h3>{_markdown(value)}</section>'



def _render_checklist(checklist) -> str:
    if isinstance(checklist, list):
        entries = []
        for item in checklist:
            if not isinstance(item, dict):
                continue
            marker = "Done" if item.get("done") is True else "Open"
            entries.append(
                f'<li><span class="checklist-state">{marker}</span> '
                f'{html.escape(str(item.get("text", "")), quote=True)}</li>'
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
    status = html.escape(str(job.get("status", "unknown")), quote=True)
    primary = " · Primary" if job.get("primary") is True else ""
    content = [(
        f'<article class="job-private"><div class="job-meta"><h1>{name}</h1>'
        f'<span class="job-state">{status}</span><span>{html.escape(str(job.get("worker", "")), quote=True)}'
        f'{primary}</span></div><p>{html.escape(str(job.get("title", "")), quote=True)}</p>'
    )]
    for label, field in (("Summary", "summary"), ("Progress", "progress"), ("Blocker", "blocker")):
        section = _field(label, job.get(field))
        if section:
            content.append(f'<div class="job-{field}">{section}</div>')
    content.append(_render_checklist(job.get("checklist", [])))
    content.append(f'<section><h2>Private working brief</h2>{_markdown(job.get("brief", ""))}</section>')
    checkpoint = job.get("latest_checkpoint")
    if isinstance(checkpoint, dict):
        content.append('<section><h2>Latest checkpoint</h2>')
        for label, field in (("Done", "done"), ("Next steps", "next_steps"), ("Blocker", "blocker")):
            section = _field(label, checkpoint.get(field))
            if section:
                content.append(section)
        pointers = checkpoint.get("pointers")
        if pointers is not None:
            rendered = json.dumps(pointers, ensure_ascii=False, sort_keys=True, indent=2, default=str)
            content.append(f'<h3>Handoff pointers</h3><pre>{html.escape(rendered, quote=True)}</pre>')
        content.append("</section>")
    updates = job.get("updates", [])
    content.append('<section><h2>Latest updates</h2>')
    if isinstance(updates, list) and updates:
        for update in updates:
            if not isinstance(update, dict):
                continue
            content.append(
                '<article class="job-update"><div class="job-update-head">'
                f'<strong>{html.escape(str(update.get("kind", "update")), quote=True)}</strong>'
                f'<time>{html.escape(str(update.get("created_at", "")), quote=True)}</time></div>'
                f'{_markdown(update.get("body", ""))}</article>'
            )
    else:
        content.append("<p>No updates recorded.</p>")
    content.append("</section><section><h2>Notes and reminders</h2>")
    notes = job.get("notes", [])
    if isinstance(notes, list) and notes:
        for note in notes:
            if not isinstance(note, dict):
                continue
            labels = " · ".join(str(note.get(key, "")) for key in ("kind", "phase", "status") if note.get(key))
            content.append(
                f'<article class="job-note"><h3>{html.escape(labels, quote=True)}</h3>'
                f'{_markdown(note.get("body", ""))}</article>'
            )
    else:
        content.append("<p>No pending notes or reminders.</p>")
    content.append(f'</section><p class="job-links"><a href="/jobs/{encoded_id}/history">Job history</a></p></article>')
    return _private_document(f"{name} · FireController job", "".join(content))


def _render_history(data: dict) -> str:
    job = data.get("job", data)
    if not isinstance(job, dict):
        raise TypeError("history page needs current job details")
    job_id = quote(str(job.get("id", "")), safe="")
    name = html.escape(str(job.get("name", "Job")), quote=True)
    entry = data.get("revision_entry")
    if isinstance(entry, dict):
        revision = entry.get("revision", "?")
        body = [
            f'<article class="job-history-entry"><h1>{name} · Revision {html.escape(str(revision), quote=True)}</h1>',
            f'<p>{html.escape(str(entry.get("created_at", "")), quote=True)}</p>',
        ]
        body.append(
            f'<p class="job-state">{html.escape(str(entry.get("status", "")), quote=True)} · '
            f'{html.escape(str(entry.get("worker", "")), quote=True)}'
            f'{" · Primary" if entry.get("primary") is True else ""}</p>'
            f'<p>{html.escape(str(entry.get("title", "")), quote=True)}</p>'
        )
        body.append(_render_checklist(entry.get("checklist", [])))
        for label, field in (("Summary", "summary"), ("Progress", "progress"), ("Blocker", "blocker")):
            section = _field(label, entry.get(field))
            if section:
                body.append(section)
        body.append(f'<section><h2>Private working brief</h2>{_markdown(entry.get("brief", ""))}</section>')
        body.append(f'<p><a href="/jobs/{job_id}/history">All revisions</a> · <a href="/jobs/{job_id}">Current job</a></p></article>')
        return _private_document(f"{name} history", "".join(body))

    offset = data.get("offset", 0)
    rows = data.get("history", [])
    content = [f'<article class="job-private"><h1>{name} history</h1><p>Recent revisions</p><ol>']
    if isinstance(rows, list):
        for row in rows:
            if not isinstance(row, dict):
                continue
            revision = row.get("revision")
            revision_url = f"/jobs/{job_id}/history?revision={quote(str(revision), safe='')}"
            content.append(
                f'<li><a href="{revision_url}">Revision {html.escape(str(revision), quote=True)}</a>'
                f' · {html.escape(str(row.get("created_at", "")), quote=True)}'
                f' · {html.escape(str(row.get("status", "")), quote=True)}'
                f' · {html.escape(str(row.get("title", "")), quote=True)}</li>'
            )
    content.append("</ol><nav class=\"job-history-paging\">")
    if type(offset) is int and offset > 0:
        previous = max(0, offset - HISTORY_PAGE_SIZE)
        content.append(f'<a href="/jobs/{job_id}/history?offset={previous}">Newer revisions</a>')
    if isinstance(rows, list) and len(rows) == HISTORY_PAGE_SIZE and type(offset) is int:
        content.append(f'<a href="/jobs/{job_id}/history?offset={offset + HISTORY_PAGE_SIZE}">Older revisions</a>')
    content.append(f'</nav><p><a href="/jobs/{job_id}">Current job</a></p></article>')
    return _private_document(f"{name} history", "".join(content))



def _render_phases(phases) -> str:
    content = []
    if isinstance(phases, dict):
        content.append("<section><h2>Phases</h2><ul>")
        for phase, state in phases.items():
            content.append(
                f'<li><strong>{html.escape(str(phase), quote=True)}</strong> · '
                f'{html.escape(str(state), quote=True)}</li>'
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
    name = html.escape(str(record.get("name", identifier)), quote=True)
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
                    f'<time>{html.escape(str(item.get("created_at", "")), quote=True)}</time>'
                    f'{_field("Status", item.get("state"))}{_field("Where it stands", item.get("now"))}'
                    f'{_field("Next milestone", item.get("milestone"))}'
                    f'{_render_phases(item.get("phase_states", {}))}</article>'
                )
        offset = record.get("offset")
        paging = []
        if type(offset) is int and offset > 0:
            paging.append(f'<a href="/workstreams/{encoded_id}/history?offset={max(0, offset - HISTORY_PAGE_SIZE)}">Newer revisions</a>')
        if type(offset) is int and isinstance(rows, list) and len(rows) == HISTORY_PAGE_SIZE:
            paging.append(f'<a href="/workstreams/{encoded_id}/history?offset={offset + HISTORY_PAGE_SIZE}">Older revisions</a>')
        content = (
            f'<h1>{name} history</h1><p>Recent workstream revisions</p>{"".join(entries)}'
            f'<nav class="job-history-paging">{"".join(paging)}</nav>'
            f'<p><a href="/workstreams/{encoded_id}">Current workstream</a></p>'
        )
        return _private_document(f"{name} history", content)

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
                f'<strong>{html.escape(str(note.get("kind", "note")), quote=True)} · '
                f'{html.escape(str(note.get("status", "")), quote=True)}</strong>'
                f'<time>{html.escape(str(note.get("created_at", "")), quote=True)}</time></div>'
                f'{_markdown(note.get("body", ""))}</article>'
            )
    else:
        content.append("<p>No private map notes.</p>")
    content.append(
        f'</section><p class="job-links"><a href="/workstreams/{encoded_id}/history">'
        "Workstream history</a></p></article>"
    )
    return _private_document(f"{name} · local workstream", "".join(content))


def render_inbox(worker: str, messages, *, offset: int = 0, unread_count: int | None = None) -> str:
    """Render a bounded private worker inbox without acknowledging messages."""

    if not _worker_alias(worker):
        raise ValueError("worker alias is invalid")
    encoded_worker = quote(worker, safe="")
    if not isinstance(messages, list):
        raise TypeError("inbox messages must be a list")
    title = f"{html.escape(worker, quote=True)} inbox"
    count = "" if unread_count is None else f'<p>{unread_count} unread message(s).</p>'
    entries = []
    for message in messages:
        if not isinstance(message, dict):
            continue
        message_id = str(message.get("id", ""))
        if not _JOB_ID.fullmatch(message_id):
            continue
        message_url = f"/inbox/{encoded_worker}/{quote(message_id, safe='')}"
        state = "Acknowledged" if message.get("acknowledged_at") else (
            "Seen" if message.get("seen_at") else "Unread"
        )
        refs = []
        if isinstance(message.get("job"), str) and message["job"]:
            refs.append(f'Job: {html.escape(message["job"], quote=True)}')
        if isinstance(message.get("pr"), int) and not isinstance(message.get("pr"), bool):
            refs.append(f'PR: #{message["pr"]}')
        if isinstance(message.get("reply_to"), str) and message["reply_to"]:
            refs.append(f'Reply to: {html.escape(message["reply_to"], quote=True)}')
        entries.append(
            f'<li><a href="{message_url}">{html.escape(str(message.get("created_at", "Message")), quote=True)}</a>'
            f' · {state} · {html.escape(str(message.get("author") or "Unspecified sender"), quote=True)}'
            f'{(" · " + " · ".join(refs)) if refs else ""}</li>'
        )
    content = (
        f'<article class="job-private"><h1>{title}</h1>{count}'
        '<p>Messages remain private. Opening this list does not mark them seen or acknowledge them.</p>'
        f'<ol>{"".join(entries) if entries else "<li>No messages on this page.</li>"}</ol>'
    )
    if offset > 0:
        content += f'<a href="/inbox/{encoded_worker}?offset={max(0, offset - HISTORY_PAGE_SIZE)}">Newer messages</a> '
    if len(messages) == HISTORY_PAGE_SIZE:
        content += f'<a href="/inbox/{encoded_worker}?offset={offset + HISTORY_PAGE_SIZE}">Older messages</a>'
    content += "</article>"
    return _private_document(f"{title} · FireController", content)


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
            f'<p>{html.escape(str(message.get("created_at", "")), quote=True)} · '
            f'{html.escape(str(message.get("author") or "Unspecified sender"), quote=True)}</p>'
        ),
    ]
    for label, key in (("Job", "job"), ("PR", "pr"), ("Reply to", "reply_to")):
        value = message.get(key)
        if value is not None and value != "":
            display = f"#{value}" if key == "pr" else str(value)
            content.append(f'<p><strong>{label}</strong> · {html.escape(display, quote=True)}</p>')
    content.append(_markdown(message.get("body", "")))
    content.append(
        f'<p><a href="/inbox/{encoded_worker}">Back to {html.escape(worker, quote=True)} inbox</a></p></article>'
    )
    return _private_document(f"{worker} inbox message", "".join(content))


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
                return _response(200, render_workstream({"id": workstream_id, "name": entry["name"],
                                                         "history": [entry]}, history=True))
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


def _private_inbox_route(parsed, inbox_store):
    segments = parsed.path.split("/")
    if len(segments) not in {3, 4} or not segments[2]:
        return _error(404, "Inbox page not found")
    worker = unquote(segments[2])
    if not _worker_alias(worker):
        return _error(400, "Invalid worker inbox")
    if inbox_store is None:
        return _error(404, "Worker inbox is not enabled")
    is_message = len(segments) == 4 and bool(segments[3])
    if len(segments) == 4 and not is_message:
        return _error(404, "Inbox message not found")
    query, error = _query(parsed, {"offset"} if not is_message else set())
    if error:
        return error
    try:
        if is_message:
            message_id = unquote(segments[3])
            if not _JOB_ID.fullmatch(message_id):
                return _error(400, "Invalid inbox message identifier")
            # Opening an explicit message route is the explicit read action.
            message = inbox_store.read(message_id, recipient=worker)
            return _response(200, render_inbox_message(worker, message))
        offset = _history_offset(query)
        if isinstance(offset, tuple):
            return offset[1]
        messages = inbox_store.list(worker, unread=False, limit=HISTORY_PAGE_SIZE, offset=offset)
        unread = inbox_store.unread_count(worker)
        return _response(200, render_inbox(worker, messages, offset=offset, unread_count=unread))
    except (KeyError, LookupError, ValueError):
        return _error(404, "Inbox page not found")
    except (OSError, RuntimeError, TypeError):
        return _error(500, "Inbox page could not be loaded")


def private_route(path, store, *, inbox=None, workstreams=None, editorial=None):
    """Serve bounded private job, workstream-note, and inbox routes from memory."""

    try:
        parsed = urlsplit(path)
    except (TypeError, ValueError):
        return None
    if parsed.path.startswith("/jobs/"):
        return _private_job_route(parsed, store)
    if parsed.path.startswith("/workstreams/"):
        return _private_workstream_route(parsed, store, workstreams, editorial)
    if parsed.path.startswith("/inbox/"):
        return _private_inbox_route(parsed, inbox)
    return None
