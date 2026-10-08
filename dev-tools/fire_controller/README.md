# FireController

FireController keeps current jobs and handoffs in the same private SQLite database as the existing review controller, while the two capabilities remain independent. Job status, checklists, notes and text never change review allocations, taper, human stops, request admission or review locks. Architecture and process documents remain repository authority; stored briefs describe the current assignment rather than executable policy.

The generic entrance provides `jobs …`, `reviews …`, `inbox …` and `map …` under `dev-tools/fire-controller`. Existing `dev-tools/pr-review` and installed project review wrappers continue to work during rollout. Review commands delegate to the existing `pr_review` engine; it is not renamed or duplicated.

For daily use, start with the installed project wrapper's `jobs assigned --worker WORKER` and `jobs read JOB --json`; the latter includes the latest checkpoint and current revision, with revision history omitted from the CLI response by default. Use `jobs history JOB` for bounded revision history or `jobs read JOB --full-history` for the complete archive, including historical briefs. Use `AREA COMMAND --help` for the relevant options and examples instead of relying on remembered syntax. Installed FireMUD review commands are `firemud-controller reviews …` and `firemud-pr-review …`; their delegated help retains the existing `dev-tools/pr-review` usage label.

## Project-specific installation

Each project selects its own reviewed checkout through its WSL wrapper (for example `firemud-controller`). A private JSON context contains only absolute `root` and `database` paths and the `repository` owner/name. Pass it through `--context /absolute/project-context.json` or `FIRE_CONTROLLER_CONTEXT`. The wrapper invokes the selected checkout’s `dev-tools/fire-controller`; upgrades are independent per project. There is no project registry, project positional dispatch, tenant service or automatic live promotion.

The FireMUD WSL installation exposes `firemud-controller` and `firemud-pr-review` through stable `/usr/local/bin` symlinks to the existing `~/.local/bin` project entries. This makes the native names available in non-login shells without requiring shell-profile initialization or changing the selected runtime. Verify both names with `--version` and `--help`; refuse to overwrite a different existing installation entry.

```json
{
  "root": "/absolute/project-checkout",
  "database": "/absolute/git-common-directory/firemud/pr-review-stack.sqlite3",
  "repository": "owner/repository"
}
```

Jobs can instead use an explicit `--database` without a project context. When both are supplied they must agree. Job commands do not discover Git roots or call providers. Review delegation requires a context, selects its repository working directory and GitHub repository, and verifies that the database is that checkout’s canonical controller SQLite sibling. A wrapper must not silently select the executable checkout’s Git common directory. Separate projects use separate database and backup destination paths; they may share an upstream provider’s account-wide quota, which this work does not reimplement.

The live launcher, database and website change only at an explicitly reviewed runtime cutover. Review the standalone implementation and its private-site source and test companion first. Verify the selected executable version, take the existing private backup, and select the reviewed FireController-capable snapshot/restore writer for scheduled backups before adding any job, inbox or map tables. Older scheduled backup writers safely refuse the new tables; leaving one selected would stop successful backup creation after cutover. Then explicitly bootstrap the new modules, preview imports, apply and inspect them, and verify a full combined snapshot/restore before changing normal authoring authority. Keep the original brief sources as import provenance; after the human-selected cutover, the database is the only normal job write path.

## Markdown text and automatic precheck

Briefs, updates, notes, checkpoints, inbox messages and eligible map/public paragraphs store original Markdown or plain text; plain text is valid Markdown. Titles and status labels are plain text. Every Markdown-bearing submission receives the same fast, local advisory precheck before writing, with relevant warnings in structured metadata or stderr. Warnings never reject ordinary valid Markdown or alter stored text; checker failures are errors. The website renderer escapes raw HTML and makes unsafe links nonclickable. Code examples do not generate markup warnings. `text preview --body-file FILE --json` shows that exact supported safe rendering plus diagnostics without writing or calling external services. The established `markdownlint` library is resolved from this selected checkout’s existing `config/openapi/package-lock.json`. Prepare its pinned Node toolchain and run `npm ci --ignore-scripts --no-audit --no-fund` in `config/openapi` once when preparing a reviewed runtime; workers do not each install it. A missing tool fails clearly before a write. All submitted fields share one Node invocation; reads/reviews never invoke it. There is no global service, formatting policy, automatic rewrite, network request during checking, or required warning acknowledgment. Rules are limited to reversed links, raw HTML, empty links, explicit undefined references and mismatched table columns; plain `[worker]` and `#2840` references are quiet.

## Current jobs and meaningful updates

All examples below target a disposable database. `--json` returns structured output; `--body-file -` reads Markdown from stdin. Job IDs are stable; friendly names and worker aliases are exact selectors.

Append a job update only for a material assignment or next-step change, significant milestone, blocker needing coordination, or pause, handoff or completion. Normally use 1–3 sentences explaining what changed and what comes next, with a link to existing evidence. Routine edits, tests, CI retries, commits and individual review findings stay in PR/review records and need no separate job update. No update is needed while proceeding as assigned. Revise the brief when standing instructions change; use the latest checkpoint to resume work.

```sh
dev-tools/fire-controller jobs --database /tmp/example-controller.sqlite3 --json bootstrap
dev-tools/fire-controller jobs --database /tmp/example-controller.sqlite3 --json create merge-train --worker General --title 'Keep the merge train moving' --summary 'Land and prove the current merge-train slice.' --progress 'Current focus: #2898 Smoke. Prerequisite PRs: #2861, #2872. Likely 2 more unpublished PRs (estimate).' --body-file /tmp/current-brief.md
dev-tools/fire-controller jobs --database /tmp/example-controller.sqlite3 --json assigned --worker General
dev-tools/fire-controller jobs --database /tmp/example-controller.sqlite3 --json revise merge-train --expect-revision 1 --summary 'Smoke proof complete' --body-file /tmp/revised-brief.md
dev-tools/fire-controller jobs --database /tmp/example-controller.sqlite3 --json update merge-train --body 'Smoke proof passed; next is handover.'
dev-tools/fire-controller jobs --database /tmp/example-controller.sqlite3 --json checkpoint merge-train --done 'Smoke proof' --next 'Handover' --pointers '{"branch":"codex/example","proof":"focused test"}'
```

Primary designation is independent of active/parked/blocked/completed status. A blocked primary stays visible with its blocker. One primary job exists per worker; `--secondary` creates other work without the designation. An explicit `revise … --primary` switches the primary atomically; parking or completing clears it. A checklist item does not force the whole job’s state. `revise` can change current instructions and public fields together. Current-state mutations require `--expect-revision`, so concurrent edits fail clearly rather than overwrite newer instructions. Appended updates, checkpoints and notes do not replace the brief.

Use `read` for the current full brief, relevant pending reminders and latest checkpoint/updates. Its `history` field is an empty list unless `--full-history` is requested; the `JobStore.get()` API and private website continue to expose their existing history behavior. `assigned` retrieves designated primary jobs by default; `assigned --all-jobs` explicitly includes parked and secondary full briefs. Use the lightweight `list` to select other work before reading it. `list`, `search`, `assigned`, `assign`, `park`, `resume`, `block`, `complete`, `checklist add|change|done`, `history` and `diff` cover the normal lifecycle; consult `--help` for exact options. `history` defaults to a bounded page; `--all` opts into full revision history. Brief versions are stored only when the brief changes, while automatic state revisions and chronological updates remain available. `read --full-history` returns the full unbounded archive, including historical brief bodies. A checkpoint supplies context pointers and does not automatically restore an AI chat’s context.

Job creation, revision and checklist commands return a compact receipt with the job ID, name, worker, title, status, current revisions and timestamps. Checklist receipts also include `item_id`; update and checkpoint commands retain their sequence-based item receipts. Read the job when the current brief or full checklist is needed. These CLI receipts do not change the `JobStore` API or private website projections.

Checkpoint `--pointers` accepts only a JSON object with `branch`, `worktree`, `pr`, `source` and `proof` keys. Values are nonblank strings of at most 1000 characters; `pr` also accepts a positive integer, and null or empty values are omitted. Extra evidence such as exact SHAs or CI results belongs in the checkpoint's `--done`/`--next` prose or the working brief, rather than invented pointer keys. The same allowlist applies when a lane pause/resume writes a combined checkpoint with `--job`, `--done` and `--next`; supplying pointers alone does not write a checkpoint.

## Deferred reminders and source inputs

`note --worker Overseer --phase 'Unit 3A' --kind source --body-file …` parks a source input for a future phase. `notes` accepts explicit worker/job/phase selection. A job selector resolves its friendly name or ID to the same existing stable job ID on creation, listing and correction; an unknown explicit job fails without changing the note. Worker/phase-only future notes require no job. `note-status … --expect-revision N` consumes, dismisses with a reason, or reopens a note; `note-revise` also corrects its body or scope. Automatic `note-history` retains earlier dispositions and reasons. Stale corrections fail clearly; consumed and dismissed states are not irreversible. No intelligent trigger, scheduler or task graph runs. Future Unit 3A/3C pointers stay Overseer-owned until explicitly reassigned when relevant.

Public title, summary, progress, blocker and selected checklist text are intentionally public. Lead with the durable mission, use the summary to state the outcome, then use progress and selected checklist items for the current focus and 1–2 broad milestones. When merge-train dependencies matter, name the current PR, list prerequisite PR numbers explicitly, and give the likely remaining unpublished PR count as an estimate; this is useful content, not a required format or field. For example: `Keep the merge train moving`; `Current focus: #2898 Smoke. Prerequisite PRs: #2861, #2872. Likely 2 more unpublished PRs (estimate).` Keep service-level execution steps, SHAs, run IDs, test inventories and detailed issue lists in the private brief, checkpoint or review records. Working briefs, detailed updates, notes, checkpoints, source files and chat IDs are private. Do not store credentials in either surface. Public export uses a field allowlist rather than dumping stored objects.

## Lane activity and private inbox

`jobs lanes` derives activity from jobs: another active job keeps the worker active after a job completes; completing the last active job makes it idle. Lane cards group active, blocked and parked jobs in that order, then sort each group by newest Last activity with name and ID as stable tie-breakers. Primary designation remains visible but does not change card order, and a blocked primary remains visible. `jobs lane-pause WORKER --reason 'Short public explanation'` deliberately pauses unfinished work; the pause persists across creating, assigning and updating jobs until `jobs lane-resume WORKER`. Either transition can include `--job`, Markdown update, or `--done`/`--next` checkpoint in one transaction. There is no heartbeat, presence inference or review gate.

Explicit `--worker-identity WORKER` before the command area, or `FIRE_CONTROLLER_WORKER`, identifies the command actor for a cheap unread notice. It is supplied metadata and grants no human permission. Unread notices never gate jobs or reviews. Job/inbox/map structured output with known identity adds `_fire_controller` metadata containing worker and unread count; a list/scalar result uses a `result` envelope. Without identity, existing result shapes remain unchanged. `jobs public-export` always retains its complete public allowlist without caller inbox/diagnostic metadata; its unread notice uses stderr even in JSON mode. Readable notices go to stderr. Delegated reviews always keep their existing stdout JSON contract and stream provider output immediately; their unread notice uses stderr only.

Always supply your own lane identity on normal worker commands. For interactive review work, prefer `firemud-controller --worker-identity WORKER reviews …`; direct `firemud-pr-review` calls bypass inbox notices. Help, version, errors and text previews do not provide a successful-command inbox notice.

Check pending messages on resume and at the next safe boundary when an unread notice appears: `firemud-controller --worker-identity WORKER inbox list --worker WORKER --json` lists messages without marking them seen; `--unread` filters to unseen messages. Use `inbox thread MESSAGE_ID` to read the original message and all replies across recipients in chronological order; it follows `reply_to` to the conversation root and returns bounded pages with author, recipient, time, job, PR, seen/acknowledged state and body. Thread reads do not mark messages seen or acknowledged. Read the relevant messages, handle work already authorized by their controlling directions, and acknowledge only after the requested action or coordination is handled. Leave unfinished requests unacknowledged even after reading them; return consequential decisions to Overseer. Messages do not wake chats. Avoid per-edit polling and acknowledgment loops, and do not wait for an entire long provider review when other safe work can read the inbox.

```sh
dev-tools/fire-controller inbox --database /tmp/example-controller.sqlite3 bootstrap
dev-tools/fire-controller --worker-identity General inbox --database /tmp/example-controller.sqlite3 send Overseer --body 'The isolated proof is ready.' --pr 123
dev-tools/fire-controller --worker-identity Overseer inbox --database /tmp/example-controller.sqlite3 --json list --unread
dev-tools/fire-controller inbox --database /tmp/example-controller.sqlite3 read MESSAGE_ID
dev-tools/fire-controller inbox --database /tmp/example-controller.sqlite3 --json thread MESSAGE_ID --limit 50 --offset 0
dev-tools/fire-controller inbox --database /tmp/example-controller.sqlite3 ack MESSAGE_ID
```

Messages may reference a job, PR and `--reply-to` message. `read` marks one message seen; `ack` describes message handling and does not complete requested work or any job. `thread` is a read-only conversation view and does not change either state. There are no delivery guarantees, automated reminders, escalation, chat-service calls or automatic waking. Message bodies and conversations are private and never public exports.

The private website's worker inbox opens on **Conversations**, grouped by the existing reply roots and ordered by the latest message sent by or addressed to that worker. Other-participant branches do not move that worker's inbox ordering. Each entry shows the full conversation's message count and its unseen incoming count; opening it shows the complete cross-recipient history in chronological pages. **All messages** is a secondary newest-first list of incoming and outgoing messages with explicit direction. Listing and opening conversations are read-only; **Read message** explicitly marks only an incoming message seen, while **View sent message** opens its chronological thread without changing the recipient's state. Worker selection filters navigation by participation and is not authentication or additional permission. Agent commands retain their incoming-only `inbox list` default and unrestricted full-thread lookup.

Both inbox landing views also show pending notes and reminders assigned to that exact worker or attached to one of the worker's jobs. Each entry retains its status, kind, phase, Markdown body, and job link when associated. Notes have an independent, bounded 50-item page control that preserves the current inbox view and message/conversation page, so older worker-wide and job-attached notes remain reachable. Viewing notes does not change note, seen, or acknowledged state. If the job store is unavailable, the inbox remains usable and identifies the notes section as unavailable. These private notes remain outside public worker and static projections.

Private job, inbox, and history pages reuse the Delivery and Project Map theme, grouped navigation, responsive cards and empty states. Worker History lists the current primary first, then orders all other jobs by latest activity, and shows durable **Created** and **Last activity** times in Pacific/Auckland with relative age calculated when rendering. Last activity aggregates job-state revisions, appended updates and checkpoints, and job-associated notes including their historical scopes; worker-only reminders have no specific job association. These are controller timestamps: importing a retained brief does not establish the original file's creation time. `last_activity_at` is a read-only projection and does not change the state-revision meaning of `updated_at` or write/backfill any timestamps.

## Project map and private status site

Jobs and map state are stored in the controller SQLite database. Worker assignment, status, public progress and blockers are changed with revision-guarded `jobs` commands; lane pauses use `jobs lane-pause` and `jobs lane-resume`. Workstream state, current work, milestones, phases and return-point state use revision-guarded `map` commands. `progress.json` is editorial input for track and phase names, descriptions, links, domain tracker paths and return-point wording. It is not the ongoing writer for mutable map state. The private `status.json` is runtime configuration, including the review-tool path; it is not a worker, job or programme-state source. The one-time `map import` command remains for a separately authorized migration from retained legacy inputs; it is not part of normal updates.

```sh
dev-tools/fire-controller map --database /tmp/example-controller.sqlite3 bootstrap
dev-tools/fire-controller map --database /tmp/example-controller.sqlite3 --json import /tmp/status.json /tmp/progress.json
dev-tools/fire-controller map --database /tmp/example-controller.sqlite3 --json import /tmp/status.json /tmp/progress.json --apply
dev-tools/fire-controller map --database /tmp/example-controller.sqlite3 --editorial /tmp/progress.json read delivery
dev-tools/fire-controller map --database /tmp/example-controller.sqlite3 --editorial /tmp/progress.json update delivery --expect-revision 1 --state Active --now 'Focused proof complete'
dev-tools/fire-controller jobs revise current-general --database /tmp/example-controller.sqlite3 --expect-revision 2 --status blocked --blocker 'Waiting for the reviewed dependency.'
```

Read a job or map row first and copy its current revision into `--expect-revision`; stale writes fail. Associate a job with a curated track using `jobs revise … --workstream-id ID --expect-revision N`; unknown IDs are refused. Editorial mapping validates phase labels and return-point headings; tracker completion and proof counts continue to come from the canonical tracker Markdown.

## Existing brief import

A versioned manifest names jobs, their latest brief file and optional source files, public fields and deferred notes. Paths resolve relative to the manifest. Preview is read-only; `--apply` is explicit and repeated application of identical content is idempotent. Source content and manifest provenance are retained in the database for historical attribution, not as a second runtime write path. Review-specific requested quantities still belong to review allocation commands; imported text never executes them.

```json
{
  "version": 1,
  "provenance": {"source": "current worker briefs", "version": "2026-10-03"},
  "jobs": [
    {"name": "current-general", "worker": "General", "title": "Current General work", "brief_file": "general.md", "summary": "Current proof", "source_files": ["general-history.md"]}
  ],
  "notes": [
    {"worker": "Overseer", "phase": "Unit 3A", "kind": "source", "body": "Revisit the retained source at this boundary."}
  ]
}
```

```sh
dev-tools/fire-controller jobs --database /tmp/example-controller.sqlite3 --json import /tmp/import.json
dev-tools/fire-controller jobs --database /tmp/example-controller.sqlite3 --json import /tmp/import.json --apply
```

## Website and backup boundaries

The private status-site source is maintained separately in [FireMUD-status-page](https://github.com/benhook1013/FireMUD-status-page). Its renderer and local server use the controller projections and private routes; private links are expanded only in local HTTP responses, while public staging rejects private routes. The [native site handoff](integration/README.md) records the source boundary and focused proof commands. Site and controller upgrades are separate: this code does not update the live site or its selected controller runtime.

The existing SQLite snapshot/restore helper includes optional job, lane, inbox and map schemas in its validated allowlist, logical readback and persisted-text screening. It preserves current briefs, all historical versions, updates, note corrections, messages, map revisions and import provenance. Old SQLite review writers update their own state document and retain these independent tables; old backup tools refuse unknown schema rather than drop it. Job schema/version compatibility is validated independently and does not become a review admission condition.

## Subagent model provenance

New native review attempts require `records subagent start --model <actual-tool-model>` and optionally `--reasoning-effort <actual-effort>`. Use tool metadata; do not infer a model from a reviewer label. The API enforces the same rule. Existing historical records/imports remain readable with unknown model, and no old counts are changed or fabricated metadata backfilled. The browser review header displays declared model information from the exact linked attempt. This metadata does not supply provider taper credit or change admission policy. The new flag takes effect only when the reviewed controller is selected for that project.

## Focused proof

Run `python3 -m unittest discover -s dev-tools/validation -p 'test_fire_controller_*.py'`, the affected existing SQLite backup/store/controller and review-record test modules, and canonical Ruff on changed Python files. Independent hands-on proof uses disposable databases and a full isolated website copy; no live provider requests, controller writes or public publication. Measure normal command/page operations on three-worker and larger fixtures, including cold/warm processes, review baseline delegation, and full refresh/public staging. Record slow exceptions honestly rather than introducing speculative caching.
