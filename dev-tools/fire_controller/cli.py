"""Local job operations and compatibility delegation to the review engine."""
from __future__ import annotations

import argparse
import json
import os
import sqlite3
import sys
from pathlib import Path

from .context import worker_alias


def _body(args, *, optional=False):
    if getattr(args, "body_file", None):
        if hasattr(args, "_body_cache"):
            return args._body_cache
        if args.body_file == "-":
            stream = getattr(sys.stdin, "buffer", None)
            args._body_cache = stream.read().decode("utf-8") if stream is not None else sys.stdin.read()
        else:
            args._body_cache = Path(args.body_file).read_bytes().decode("utf-8")
        return args._body_cache
    if getattr(args, "body", None) is not None:
        return args.body
    return None if optional else ""


def _body_options(parser, *, label="Markdown"):
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--body", help=f"{label} text")
    group.add_argument("--body-file", help=f"{label} file, or - for stdin")


def _object_file(path, label):
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise TypeError(f"{label} must be a JSON object")
    return value


def _parser():
    parser = argparse.ArgumentParser(
        prog="fire-controller", description=__doc__,
        epilog=("Use AREA COMMAND --help for details and examples. Installed FireMUD aliases: "
                "firemud-controller reviews ... and firemud-pr-review ... use the same review engine. "
                "Project wrappers select their own context; --context is for the generic entrypoint."),
    )
    parser.add_argument("--version", action="store_true")
    parser.add_argument("--context", type=Path, default=os.environ.get("FIRE_CONTROLLER_CONTEXT"),
                        help="single-project JSON configuration selected by the project wrapper")
    parser.add_argument("--worker-identity", default=os.environ.get("FIRE_CONTROLLER_WORKER"),
                        help="worker metadata for unread inbox notices; grants no authority")
    areas = parser.add_subparsers(dest="area")
    areas.add_parser("reviews", help="delegate all following arguments to the existing review engine")
    jobs = areas.add_parser(
        "jobs", help="local SQLite jobs; never changes review policy",
        description="Read assigned work with assigned/read; revise standing instructions and write checkpoints explicitly.",
    )
    jobs.add_argument("--database", type=Path, help="explicit controller SQLite path; required without --context")
    jobs.add_argument("--json", action="store_true", help="structured output for agents")
    commands = jobs.add_subparsers(dest="command", required=True)
    commands.add_parser("bootstrap", help="explicitly initialise the job schema")
    create = commands.add_parser("create", help="create a job and its initial working brief")
    create.add_argument("name")
    create.add_argument("--worker", required=True)
    create.add_argument("--title", required=True, help="public standing-job title")
    create.add_argument("--status", choices=["active", "parked", "blocked", "completed"], default="active")
    create.add_argument("--summary", default="", help="public high-level assignment")
    create.add_argument("--progress", default="", help="public current next step")
    create.add_argument("--blocker", default="", help="public blocker")
    create.add_argument("--chat-id")
    create.add_argument("--workstream-id", help="curated project-map track association")
    create.add_argument("--secondary", action="store_true", help="create without the primary designation")
    create.add_argument("--checklist", type=json.loads, default=[])
    _body_options(create, label="Private working brief (Markdown)")
    for name in ("list", "assigned", "search", "public-export"):
        description = {
            "list": "List lightweight job state before selecting a job to read.",
            "assigned": "Read designated primary jobs with full briefs and latest checkpoints by default.",
            "search": "Search public job fields before selecting a job to read.",
            "public-export": "Export only allowlisted public job fields; excludes working briefs and checkpoints.",
        }[name]
        command = commands.add_parser(name, help=description, description=description)
        command.add_argument("--worker", help="exact worker name or alias")
        command.add_argument("--status", choices=["active", "parked", "blocked", "completed"])
        command.add_argument("--workstream-id", help="select associated project-map track")
        command.add_argument("--primary", action="store_true", help="select designated primary jobs")
        if name == "assigned":
            command.add_argument("--all-jobs", action="store_true", help="include secondary and parked jobs with full briefs")
            command.epilog = "Example: firemud-controller jobs assigned --worker General --json"
            command.formatter_class = argparse.RawDescriptionHelpFormatter
        if name == "search":
            command.add_argument("query")
    read = commands.add_parser(
        "read", help="current brief, notes, checkpoint and newest updates",
        description="Read the full current brief, latest checkpoint, relevant notes and current revision without writing.",
        epilog="Example: firemud-controller jobs read current-general --json",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    read.add_argument("job", help="stable job ID or exact friendly name")
    read.add_argument("--latest", type=int, default=10,
                      help="maximum newest updates to include (default: 10); does not limit the brief or latest checkpoint")
    read.add_argument("--full-history", action="store_true",
                      help="include all updates, checkpoints and notes without the usual history bounds")
    for name in ("revise", "assign", "park", "resume", "complete", "block"):
        command = commands.add_parser(
            name, help="change current state with a revision guard",
            description="Change job state with a revision guard; omitted text fields retain their current values.",
        )
        command.add_argument("job", help="stable job ID or exact friendly name")
        command.add_argument("--expect-revision", type=int, required=True,
                             help="current revision returned by jobs read; stale revisions are refused")
        command.add_argument("--worker", required=name == "assign")
        command.add_argument("--title", help="public standing-job title")
        command.add_argument("--summary", help="public high-level assignment")
        command.add_argument("--progress", help="public current next step")
        command.add_argument("--blocker", help="public blocker")
        command.add_argument("--chat-id", help="private chat context pointer")
        command.add_argument("--workstream-id")
        command.add_argument("--status", choices=["active", "parked", "blocked", "completed"])
        command.add_argument("--primary", action="store_true", default=None, help="explicitly switch the worker primary job")
        command.add_argument("--checklist", type=json.loads, help="JSON checklist; selected item text is public")
        _body_options(command, label="Private working brief (Markdown)")
        if name == "revise":
            command.epilog = (
                "Read the job first, then use its revision in --expect-revision. Example if revision is 1: "
                "firemud-controller jobs revise current-general --expect-revision 1 --body-file /tmp/revised-brief.md"
            )
            command.formatter_class = argparse.RawDescriptionHelpFormatter
    update = commands.add_parser("update", help="append a meaningful update without replacing the brief")
    update.add_argument("job")
    update.add_argument("--kind", default="progress")
    _body_options(update)
    checklist = commands.add_parser("checklist")
    checklist.add_argument("job")
    checklist.add_argument("action", choices=["add", "change", "done"])
    checklist.add_argument("--expect-revision", type=int, required=True)
    checklist.add_argument("--item-id")
    checklist.add_argument("--text")
    pointer_help = (
        "JSON object; allowed keys: branch, worktree, pr, source, proof. "
        "Values are nonblank strings up to 1000 characters; pr also accepts a positive integer. "
        "Null/empty values are ignored. Put extra head/CI evidence in checkpoint prose."
    )
    checkpoint = commands.add_parser(
        "checkpoint", help="write a private done/next/blocker checkpoint",
        description="Write a private checkpoint without replacing the working brief. Use jobs read to retrieve the latest checkpoint.",
        epilog=("Example: firemud-controller jobs checkpoint current-general --done 'Focused proof' "
                "--next 'Handover' --pointers '{\"branch\":\"codex/example\",\"proof\":\"focused test\"}'"),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    checkpoint.add_argument("job", help="stable job ID or exact friendly name")
    checkpoint.add_argument("--done", required=True, help="private completed-work summary")
    checkpoint.add_argument("--next", dest="next_steps", required=True, help="private resume or handoff next step")
    checkpoint.add_argument("--blocker", default="", help="private checkpoint blocker")
    checkpoint.add_argument("--pointers", type=json.loads, default={}, help=pointer_help)
    history = commands.add_parser(
        "history", help="list/show/search automatic revisions",
        description="Read automatic job state revisions; a bounded page is returned by default.",
        epilog="Example: firemud-controller jobs history current-general --revision 1 --json",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    history.add_argument("job", help="stable job ID or exact friendly name")
    history.add_argument("--revision", type=int, help="show one exact state revision and its brief")
    history.add_argument("--search")
    history.add_argument("--limit", type=int, default=50, help="maximum revisions per page (default: 50)")
    history.add_argument("--offset", type=int, default=0)
    history.add_argument("--all", action="store_true", help="read full unbounded revision history")
    diff = commands.add_parser("diff", help="compare two recorded job revisions")
    diff.add_argument("job", help="stable job ID or exact friendly name")
    diff.add_argument("old", type=int, help="older state revision number")
    diff.add_argument("new", type=int, help="newer state revision number")
    note = commands.add_parser("note", help="deferred reminder or source input")
    note.add_argument("--worker")
    note.add_argument("--job")
    note.add_argument("--phase", default="")
    note.add_argument("--kind", choices=["reminder", "source"], default="reminder")
    _body_options(note)
    notes = commands.add_parser("notes", help="explicit worker/job/phase selection")
    notes.add_argument("--worker")
    notes.add_argument("--job")
    notes.add_argument("--phase")
    notes.add_argument("--status", choices=["pending", "consumed", "dismissed"], default="pending")
    state = commands.add_parser("note-status")
    state.add_argument("note_id")
    state.add_argument("status", choices=["pending", "consumed", "dismissed"])
    state.add_argument("--expect-revision", required=True, type=int)
    state.add_argument("--reason", default="")
    note_revise = commands.add_parser("note-revise", help="correct or reopen a note with its current revision")
    note_revise.add_argument("note_id")
    note_revise.add_argument("--expect-revision", required=True, type=int)
    note_revise.add_argument("--worker")
    note_revise.add_argument("--job")
    note_revise.add_argument("--phase")
    note_revise.add_argument("--kind", choices=["reminder", "source", "instruction"])
    note_revise.add_argument("--status", choices=["pending", "consumed", "dismissed"])
    note_revise.add_argument("--reason")
    _body_options(note_revise)
    note_history = commands.add_parser("note-history", help="automatic note state revisions and old reasons")
    note_history.add_argument("note_id")
    note_history.add_argument("--revision", type=int)
    note_history.add_argument("--limit", type=int, default=50)
    note_history.add_argument("--offset", type=int, default=0)
    note_history.add_argument("--all", action="store_true")
    imp = commands.add_parser("import", help="preview by default; explicitly apply a versioned manifest")
    imp.add_argument("manifest", type=Path)
    imp.add_argument("--apply", action="store_true")
    lanes = commands.add_parser("lanes", help="derived public worker activity and explicit pauses")
    lanes.add_argument("--worker", action="append", help="select aliases; repeat for multiple workers")
    for action in ("lane-pause", "lane-resume"):
        description = (
            "Explicitly pause a worker lane; job changes do not clear the pause."
            if action == "lane-pause" else
            "Explicitly clear a worker lane pause; does not change job status or review policy."
        )
        lane = commands.add_parser(action, help=description, description=description)
        lane.add_argument("worker", help="exact worker name or alias")
        lane.add_argument("--job", help="optional job for a combined update/checkpoint")
        if action == "lane-pause":
            lane.add_argument("--reason", default="", help="short public pause explanation")
        lane.add_argument("--done", help="private checkpoint summary; requires --job and --next")
        lane.add_argument("--next", dest="next_steps", help="private checkpoint next step; requires --job and --done")
        lane.add_argument("--blocker", default="")
        lane.add_argument("--pointers", type=json.loads, default={}, help=pointer_help)
        lane.epilog = (
            f"Example: firemud-controller jobs {action} General"
            + (" --reason 'Paused for handoff'" if action == "lane-pause" else "")
            + "\nOptional pointers accompany --job JOB --done TEXT --next TEXT; they do not create a checkpoint alone."
        )
        lane.formatter_class = argparse.RawDescriptionHelpFormatter
        _body_options(lane)
    for command_parser in commands.choices.values():
        command_parser.add_argument("--json", action="store_true", default=argparse.SUPPRESS,
                                    help="structured output for agents")
        command_parser.add_argument("--database", type=Path, default=argparse.SUPPRESS,
                                    help="explicit controller SQLite path")
    inbox = areas.add_parser("inbox", help="private inter-worker messages; no automatic waking or work completion")
    inbox.add_argument("--database", type=Path)
    inbox.add_argument("--json", action="store_true")
    inbox_commands = inbox.add_subparsers(dest="command", required=True)
    inbox_commands.add_parser("bootstrap")
    send = inbox_commands.add_parser(
        "send", help="store a private message without waking the recipient",
        description="Store a private message; does not wake the recipient or complete work.",
        epilog=("Example: firemud-controller --worker-identity General inbox send Overseer "
                "--body 'The isolated proof is ready.'"),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    send.add_argument("recipient", help="exact recipient worker name or alias")
    send.add_argument("--author", help="supplied metadata, not human permission")
    send.add_argument("--job")
    send.add_argument("--pr", type=int)
    send.add_argument("--reply-to")
    _body_options(send)
    messages = inbox_commands.add_parser(
        "list", help="list messages without marking them seen",
        description="List private messages without changing seen or acknowledged state.",
        epilog="Example: firemud-controller --worker-identity General inbox list --unread --json",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    messages.add_argument("--worker", help="recipient; defaults to configured worker identity")
    messages.add_argument("--unread", action="store_true")
    messages.add_argument("--limit", type=int, default=50)
    messages.add_argument("--offset", type=int, default=0)
    for action in ("read", "ack"):
        description = (
            "Return a private message and mark it seen; does not acknowledge handling or complete a job."
            if action == "read" else
            "Mark a private message acknowledged and seen; records handling, not job completion."
        )
        message = inbox_commands.add_parser(action, help=description, description=description)
        message.add_argument("message_id", help="message ID returned by inbox list or send")
        message.add_argument("--worker", help="optional recipient selector, not authentication")
    for command_parser in inbox_commands.choices.values():
        command_parser.add_argument("--database", type=Path, default=argparse.SUPPRESS)
        command_parser.add_argument("--json", action="store_true", default=argparse.SUPPRESS)
    text = areas.add_parser("text", help="local safe Markdown preview; diagnostics are advisory")
    text_commands = text.add_subparsers(dest="command", required=True)
    preview = text_commands.add_parser("preview", help="render the exact website-supported Markdown subset")
    _body_options(preview)
    preview.add_argument("--json", action="store_true", help="HTML and advisory diagnostics")
    maps = areas.add_parser("map", help="mutable map state; editorial headings and tracker contracts stay files")
    maps.add_argument("--database", type=Path)
    maps.add_argument("--editorial", type=Path, default=os.environ.get("FIRE_CONTROLLER_EDITORIAL"),
                      help="curated progress JSON mapping, required for list/read/update")
    maps.add_argument("--json", action="store_true")
    map_commands = maps.add_subparsers(dest="command", required=True)
    map_commands.add_parser("bootstrap")
    map_commands.add_parser("list")
    map_read = map_commands.add_parser("read")
    map_read.add_argument("workstream_id")
    map_update = map_commands.add_parser("update")
    map_update.add_argument("workstream_id")
    map_update.add_argument("--expect-revision", required=True, type=int)
    map_update.add_argument("--state")
    map_update.add_argument("--now")
    map_update.add_argument("--milestone")
    map_update.add_argument("--phase-states", type=json.loads)
    return_update = map_commands.add_parser("return-point")
    return_update.add_argument("point_id")
    return_update.add_argument("--expect-revision", required=True, type=int)
    return_update.add_argument("--state", required=True)
    map_history = map_commands.add_parser("history")
    map_history.add_argument("record_type", choices=["workstream", "return_point"])
    map_history.add_argument("record_id")
    map_history.add_argument("--revision", type=int)
    map_history.add_argument("--limit", type=int, default=50)
    map_history.add_argument("--offset", type=int, default=0)
    map_import = map_commands.add_parser("import", help="preview site source migration; --apply reconciles jobs then records map import")
    map_import.add_argument("status_source", type=Path)
    map_import.add_argument("progress_source", type=Path)
    map_import.add_argument("--apply", action="store_true")
    for command_parser in map_commands.choices.values():
        command_parser.add_argument("--database", type=Path, default=argparse.SUPPRESS)
        command_parser.add_argument("--editorial", type=Path, default=argparse.SUPPRESS)
        command_parser.add_argument("--json", action="store_true", default=argparse.SUPPRESS)
    return parser


def _database(args):
    database = args.database
    if args.context:
        from .context import ProjectContext
        database = ProjectContext.load(args.context).select_database(database)
    if database is None:
        raise ValueError(f"{args.area} requires --database or a selected --context")
    return database


def _dispatch_inbox(args):
    from .inbox import InboxStore
    store = InboxStore(_database(args))
    if args.command == "bootstrap":
        store.bootstrap()
        return {"status": "bootstrapped"}
    if args.command == "send":
        return store.send(args.recipient, _body(args), author=args.author or args.worker_identity,
                          job=args.job, pr=args.pr, reply_to=args.reply_to)
    if args.command == "list":
        worker = args.worker or args.worker_identity
        if not worker:
            raise ValueError("inbox list requires --worker or --worker-identity/FIRE_CONTROLLER_WORKER")
        return store.list(worker, unread=args.unread, limit=args.limit, offset=args.offset)
    return getattr(store, args.command)(args.message_id, recipient=args.worker or args.worker_identity)


def _dispatch_map(args):
    from .map import WorkstreamStore
    store = WorkstreamStore(_database(args))
    if args.command == "bootstrap":
        store.bootstrap()
        return {"status": "bootstrapped"}
    if args.command == "history":
        return store.history(args.record_type, args.record_id, revision=args.revision,
                             limit=args.limit, offset=args.offset)
    if args.command == "import":
        plan = store.import_site(args.status_source, args.progress_source, apply=False)
        if plan["conflicts"]:
            raise ValueError("; ".join(plan["conflicts"]))
        plan["lane_targets"] = _site_lane_targets(_database(args), plan)
        if not args.apply:
            return plan
        if plan["already_applied"]:
            return plan
        _apply_site_lanes(_database(args), plan)
        return store.import_site(args.status_source, args.progress_source, apply=True,
                                 expected_fingerprint=plan["source_fingerprint"])
    if args.editorial is None:
        raise ValueError("map list/read/update requires --editorial or FIRE_CONTROLLER_EDITORIAL")
    editorial = _object_file(args.editorial, "map editorial mapping")
    if args.command == "list":
        return store.list(editorial)
    if args.command == "read":
        return store.get(args.workstream_id, editorial)
    if args.command == "return-point":
        return store.update_return_point(args.point_id, args.expect_revision, editorial, state=args.state)
    changes = {field: getattr(args, field) for field in ("state", "now", "milestone", "phase_states")
               if getattr(args, field) is not None}
    return store.update(args.workstream_id, args.expect_revision, editorial, **changes)


def _site_lane_targets(database, plan):
    from .jobs import JobStore
    store = JobStore(database)
    rows = store.list()
    targets = []
    for lane in plan["lane_jobs"]:
        primary = next((row for row in rows if row["worker"] == lane["worker"] and row["primary"]), None)
        named = next((row for row in rows if row["name"] == lane["name"]), None)
        target = primary or named
        if target is not None and target["worker"] != lane["worker"]:
            raise ValueError(f"imported site name {lane['name']} belongs to another worker")
        targets.append({"worker": lane["worker"], "job_id": target["id"] if target else None,
                        "expected_revision": target["revision"] if target else None,
                        "action": "reconcile_public_fields" if target else "create_site_assignment"})
    return targets


def _apply_site_lanes(database, plan):
    from .jobs import JobStore
    store = JobStore(database)
    for lane, target in zip(plan["lane_jobs"], plan["lane_targets"], strict=True):
        if target["job_id"] is None:
            store.create(lane["name"], lane["worker"], lane["title"], status=lane["status"],
                         primary=lane["primary"], summary=lane["summary"], progress=lane["progress"],
                         blocker=lane["blocker"], checklist=lane["checklist"])
        else:
            current = store.get(target["job_id"], latest=0)
            if current["revision"] != target["expected_revision"]:
                raise ValueError("site-import job revision changed; preview again before applying")
            changes = {field: lane[field] for field in ("status", "summary", "progress", "blocker", "checklist")
                       if current[field] != lane[field]}
            if changes:
                store.revise(target["job_id"], target["expected_revision"], **changes)
        if lane["worker_paused"]:
            state = store.lane_state(lane["worker"])
            if not state["paused"]:
                store.pause(lane["worker"], reason="Imported deliberate pause")


def _dispatch(args):
    from .jobs import JobStore
    database = _database(args)
    store = JobStore(database)
    command = args.command
    if getattr(args, "workstream_id", None) is not None and command in {"create", "revise", "assign", "park", "resume", "complete", "block"}:
        _require_workstream(database, args.workstream_id)
    if command == "bootstrap":
        store.bootstrap()
        return {"status": "bootstrapped"}
    if command == "lanes":
        return store.lanes(workers=args.worker)
    if command in {"lane-pause", "lane-resume"}:
        checkpoint = None
        if args.done is not None or args.next_steps is not None:
            if args.done is None or args.next_steps is None:
                raise ValueError("combined checkpoint requires both --done and --next")
            checkpoint = {"done": args.done, "next_steps": args.next_steps,
                          "blocker": args.blocker, "pointers": args.pointers}
        options = {"job": args.job, "checkpoint": checkpoint, "update": _body(args, optional=True)}
        if command == "lane-pause":
            options["reason"] = args.reason
        return getattr(store, "pause" if command == "lane-pause" else "resume")(args.worker, **options)
    if command == "create":
        return store.create(args.name, args.worker, args.title, brief=_body(args), summary=args.summary,
                            status=args.status, primary=False if args.secondary else None, chat_id=args.chat_id, checklist=args.checklist,
                            progress=args.progress, blocker=args.blocker, workstream_id=args.workstream_id)
    if command == "read":
        return store.get(args.job, latest=args.latest, full_history=args.full_history)
    if command in {"list", "search", "public-export", "assigned"}:
        rows = store.list(worker=args.worker, status=args.status,
                          search=args.query if command == "search" else None,
                          workstream_id=args.workstream_id,
                          primary=True if args.primary or (command == "assigned" and not args.all_jobs) else None)
        if command == "public-export":
            from .web import public_jobs
            return public_jobs(rows)
        if command == "assigned":
            return [store.get(row["id"]) for row in rows if args.status or row["status"] != "completed"]
        return rows
    if command in {"revise", "assign", "park", "resume", "complete", "block"}:
        changes = {key: getattr(args, key) for key in
                   ("worker", "title", "summary", "progress", "blocker", "chat_id", "status", "primary", "checklist", "workstream_id")
                   if getattr(args, key) is not None}
        body = _body(args, optional=True)
        if body is not None:
            changes["brief"] = body
        statuses = {"park": "parked", "resume": "active", "complete": "completed", "block": "blocked"}
        if command in statuses:
            changes["status"] = statuses[command]
        return store.revise(args.job, args.expect_revision, **changes)
    if command == "update":
        return store.append_update(args.job, _body(args), kind=args.kind)
    if command == "checklist":
        return store.checklist(args.job, args.expect_revision, args.action, item_id=args.item_id, text=args.text)
    if command == "checkpoint":
        return store.checkpoint(args.job, args.done, args.next_steps, blocker=args.blocker, pointers=args.pointers)
    if command == "history":
        return store.history(args.job, revision=args.revision, search=args.search,
                             limit=None if args.all else args.limit, offset=args.offset)
    if command == "diff":
        return store.diff(args.job, args.old, args.new)
    if command == "note":
        return store.note(_body(args), worker=args.worker, job=args.job, phase=args.phase, kind=args.kind)
    if command == "notes":
        return store.notes(worker=args.worker, job=args.job, phase=args.phase, status=args.status)
    if command == "note-status":
        return store.note_status(args.note_id, args.status, reason=args.reason, expected_revision=args.expect_revision)
    if command == "note-revise":
        changes = {name: getattr(args, name) for name in
                   ("worker", "job", "phase", "kind", "status", "reason")
                   if getattr(args, name) is not None}
        body = _body(args, optional=True)
        if body is not None:
            changes["body"] = body
        return store.revise_note(args.note_id, args.expect_revision, **changes)
    if command == "note-history":
        return store.note_history(args.note_id, revision=args.revision,
                                  limit=None if args.all else args.limit, offset=args.offset)
    if command == "import":
        manifest = _object_file(args.manifest, "brief import manifest")
        # Resolve source paths against the manifest, never process-working-directory guesses.
        for job in manifest.get("jobs", []):
            if job.get("workstream_id") is not None:
                _require_workstream(database, job["workstream_id"])
            if "brief_file" in job:
                job["brief_file"] = str((args.manifest.parent / job["brief_file"]).resolve())
            if "source_files" in job:
                job["source_files"] = [str((args.manifest.parent / p).resolve()) for p in job["source_files"]]
        return store.import_briefs(manifest, apply=args.apply)
    raise ValueError("unsupported job command")


def _require_workstream(database, identifier):
    from .map import WorkstreamStore
    if not WorkstreamStore(database).has_workstream(identifier):
        raise ValueError(f"unknown workstream {identifier}; use an imported editorial track ID")


def _unread_metadata(database, worker):
    if not worker:
        return None
    from .inbox import InboxStore
    try:
        return {"worker": worker, "unread_count": InboxStore(database).unread_count(worker)}
    except (ValueError, RuntimeError, OSError, sqlite3.Error):
        # A private unread notice cannot turn a successful review/job command
        # into a failure or gate admission; its unavailable state is explicit.
        return {"worker": worker, "unread_count": None, "inbox_status": "unavailable"}


def _validate_identity(worker):
    if worker is not None and not worker_alias(worker):
        raise ValueError("worker identity must be nonblank bounded text without control characters")


def _precheck(args):
    """Fast advisory submission check, before any Markdown-bearing write."""
    writes = {"create", "revise", "assign", "park", "resume", "complete", "block", "update",
              "checklist", "checkpoint", "note", "note-revise", "note-status", "send",
              "lane-pause", "lane-resume", "import"}
    if args.command not in writes:
        return []
    from .markdown import diagnostics
    values = {}
    body = _body(args, optional=True)
    if body is not None:
        values["body"] = body
    for name in ("summary", "progress", "blocker", "done", "next_steps", "text", "reason",
                 "dismissal_reason", "now", "milestone"):
        value = getattr(args, name, None)
        if isinstance(value, str):
            values[name] = value
    checklist = getattr(args, "checklist", None)
    if isinstance(checklist, list):
        for index, item in enumerate(checklist):
            if isinstance(item, dict) and isinstance(item.get("text"), str):
                values[f"checklist[{index}]"] = item["text"]
    if args.area == "jobs" and args.command == "import":
        manifest = _object_file(args.manifest, "brief import manifest")
        for index, job in enumerate(manifest.get("jobs", [])):
            if isinstance(job, dict) and isinstance(job.get("brief_file"), str):
                values[f"jobs[{index}].brief"] = (args.manifest.parent / job["brief_file"]).read_text(encoding="utf-8")
            if isinstance(job, dict):
                for field in ("summary", "progress", "blocker"):
                    if isinstance(job.get(field), str):
                        values[f"jobs[{index}].{field}"] = job[field]
                for item_index, item in enumerate(job.get("checklist", []) if isinstance(job.get("checklist", []), list) else []):
                    if isinstance(item, dict) and isinstance(item.get("text"), str):
                        values[f"jobs[{index}].checklist[{item_index}]"] = item["text"]
        for index, note in enumerate(manifest.get("notes", [])):
            if isinstance(note, dict) and isinstance(note.get("body"), str):
                values[f"notes[{index}].body"] = note["body"]
    if args.area == "map" and args.command == "import":
        status = _object_file(args.status_source, "site status source")
        progress = _object_file(args.progress_source, "site progress source")
        for index, lane in enumerate(status.get("lanes", [])):
            if not isinstance(lane, dict):
                continue
            for field in ("task", "up_next", "blocker"):
                value = lane.get(field)
                if isinstance(value, str):
                    values[f"lanes[{index}].{field}"] = value
                elif isinstance(value, list):
                    for item_index, item in enumerate(value):
                        if isinstance(item, str):
                            values[f"lanes[{index}].{field}[{item_index}]"] = item
        for index, track in enumerate(progress.get("tracks", [])):
            if isinstance(track, dict):
                for field in ("now", "milestone"):
                    if isinstance(track.get(field), str):
                        values[f"tracks[{index}].{field}"] = track[field]
    return diagnostics(values)


def _with_metadata(result, metadata):
    if metadata is None:
        return result
    if isinstance(result, dict):
        return {**result, "_fire_controller": metadata}
    return {"result": result, "_fire_controller": metadata}


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    # Generic options precede the command area; review options remain untouched.
    prefix_end = 0
    while prefix_end < len(argv):
        token = argv[prefix_end]
        if token in {"--context", "--worker-identity"}:
            prefix_end += 2
        elif token.startswith(("--context=", "--worker-identity=")):
            prefix_end += 1
        else:
            break
    boundary = prefix_end if prefix_end < len(argv) else None
    if boundary is not None and argv[boundary] == "reviews":
        context_parser = argparse.ArgumentParser(prog="fire-controller reviews")
        context_parser.add_argument("--context", type=Path, default=os.environ.get("FIRE_CONTROLLER_CONTEXT"))
        context_parser.add_argument("--worker-identity", default=os.environ.get("FIRE_CONTROLLER_WORKER"))
        options = context_parser.parse_args(argv[:boundary])
        review_args = argv[boundary + 1:]
        if options.context is None:
            if "--help" in review_args:
                from pr_review.cli import main as reviews_help
                return reviews_help(review_args)
            context_parser.error("reviews requires --context (or FIRE_CONTROLLER_CONTEXT)")
        try:
            from pr_review.cli import main as reviews_main

            from .context import ProjectContext
            context = ProjectContext.load(options.context)
            _validate_identity(options.worker_identity)
            context.validate_review_arguments(review_args)
            # Review output streams directly with its existing JSON contract.
            # Wrapper inbox notices belong on stderr, never in provider output.
            metadata = _unread_metadata(context.database, options.worker_identity)
            if metadata and metadata["unread_count"]:
                print(f"Inbox: {metadata['unread_count']} unread for {metadata['worker']}", file=sys.stderr)
            with context.review_environment():
                return reviews_main(review_args)
        except (ValueError, RuntimeError, OSError) as error:
            print(f"fire-controller: {error}", file=sys.stderr)
            return 2
    parser = _parser()
    args = parser.parse_args(argv)
    if args.version:
        from pr_review.sqlite_store import SQLITE_SCHEMA_VERSION, WRITER_BUILD
        print(f"fire-controller sqlite_schema_version={SQLITE_SCHEMA_VERSION} writer_build={WRITER_BUILD}")
        return 0
    if args.area is None:
        parser.print_help()
        return 0
    try:
        _validate_identity(args.worker_identity)
        if args.area == "text":
            from .markdown import diagnostics as lint_diagnostics
            from .web import render_markdown
            body = _body(args)
            diagnostics = lint_diagnostics({"body": body})
            rendered = render_markdown(body)
            if args.json:
                print(json.dumps({"html": rendered, "diagnostics": diagnostics}, ensure_ascii=False))
            else:
                print(rendered)
                for diagnostic in diagnostics:
                    print(f"Markdown advisory: {diagnostic['message']}", file=sys.stderr)
            return 0
        diagnostics = _precheck(args)
        dispatch = {"inbox": _dispatch_inbox, "map": _dispatch_map, "jobs": _dispatch}
        result = dispatch[args.area](args)
        metadata = _unread_metadata(_database(args), args.worker_identity)
        public_export = args.area == "jobs" and args.command == "public-export"
        if diagnostics:
            metadata = {**(metadata or {}), "markdown_diagnostics": diagnostics}
        if args.json:
            print(json.dumps(result if public_export else _with_metadata(result, metadata), ensure_ascii=False, sort_keys=True))
        elif isinstance(result, str):
            print(result)
        else:
            print(json.dumps(result, ensure_ascii=False, indent=2))
        if (not args.json or public_export) and metadata and metadata.get("unread_count"):
            print(f"Inbox: {metadata['unread_count']} unread for {metadata['worker']}", file=sys.stderr)
        if not args.json:
            for diagnostic in diagnostics:
                print(f"Markdown advisory ({diagnostic['field']}): {diagnostic['message']}", file=sys.stderr)
        return 0
    except (ValueError, RuntimeError, OSError, TypeError, KeyError) as error:
        print(f"fire-controller: {error}", file=sys.stderr)
        return 2
