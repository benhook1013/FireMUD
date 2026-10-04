# FireController

FireController keeps current jobs and handoffs in the same private SQLite database as the existing review controller, while the two capabilities remain independent. Job status, checklists, notes and text never change review allocations, taper, human stops, request admission or review locks. Architecture and process documents remain repository authority; stored briefs describe the current assignment rather than executable policy.

The generic entrance provides `jobs …`, `reviews …`, `inbox …` and `map …` under `dev-tools/fire-controller`. Existing `dev-tools/pr-review` and installed project review wrappers continue to work during rollout. Review commands delegate to the existing `pr_review` engine; it is not renamed or duplicated.

## Project-specific installation

Each project selects its own reviewed checkout through its WSL wrapper (for example `firemud-controller`). A private JSON context contains only absolute `root` and `database` paths and the `repository` owner/name. Pass it through `--context /absolute/project-context.json` or `FIRE_CONTROLLER_CONTEXT`. The wrapper invokes the selected checkout’s `dev-tools/fire-controller`; upgrades are independent per project. There is no project registry, project positional dispatch, tenant service or automatic live promotion.

```json
{
  "root": "/absolute/project-checkout",
  "database": "/absolute/git-common-directory/firemud/pr-review-stack.sqlite3",
  "repository": "owner/repository"
}
```

Jobs can instead use an explicit `--database` without a project context. When both are supplied they must agree. Job commands do not discover Git roots or call providers. Review delegation requires a context, selects its repository working directory and GitHub repository, and verifies that the database is that checkout’s canonical controller SQLite sibling. A wrapper must not silently select the executable checkout’s Git common directory. Separate projects use separate database and backup destination paths; they may share an upstream provider’s account-wide quota, which this work does not reimplement.

The live launcher, database and website are not changed by installation preparation. Promote only after the standalone implementation, isolated proof and adapter handover are reviewed. First verify the selected executable version, take the existing private backup, and select the reviewed FireController-capable snapshot/restore writer for scheduled backups before adding any job, inbox or map tables. Older scheduled backup writers safely refuse the new tables; leaving one selected would stop successful backup creation after cutover. Then explicitly bootstrap the new modules, preview imports, apply and inspect them, and verify a full combined snapshot/restore before changing normal authoring authority. Keep the original brief sources as import provenance; after the human-selected cutover, the database is the only normal job write path.

## Markdown text and automatic precheck

Briefs, updates, notes, checkpoints, inbox messages and eligible map/public paragraphs store original Markdown or plain text; plain text is valid Markdown. Titles and status labels are plain text. Every Markdown-bearing submission receives the same fast, local advisory precheck before writing, with relevant warnings in structured metadata or stderr. Warnings never reject ordinary valid Markdown or alter stored text; checker failures are errors. The website renderer escapes raw HTML and makes unsafe links nonclickable. Code examples do not generate markup warnings. `text preview --body-file FILE --json` shows that exact supported safe rendering plus diagnostics without writing or calling external services. The established `markdownlint` library is resolved from this selected checkout’s existing `config/openapi/package-lock.json`. Prepare its pinned Node toolchain and run `npm ci --ignore-scripts --no-audit --no-fund` in `config/openapi` once when preparing a reviewed runtime; workers do not each install it. A missing tool fails clearly before a write. All submitted fields share one Node invocation; reads/reviews never invoke it. There is no global service, formatting policy, automatic rewrite, network request during checking, or required warning acknowledgment. Rules are limited to reversed links, raw HTML, empty links, explicit undefined references and mismatched table columns; plain `[worker]` and `#2840` references are quiet.

## Current jobs and meaningful updates

All examples below target a disposable database. `--json` returns structured output; `--body-file -` reads Markdown from stdin. Job IDs are stable; friendly names and worker aliases are exact selectors.

Append a job update only for a material assignment or next-step change, significant milestone, blocker needing coordination, or pause, handoff or completion. Normally use 1–3 sentences explaining what changed and what comes next, with a link to existing evidence. Routine edits, tests, CI retries, commits and individual review findings stay in PR/review records and need no separate job update. No update is needed while proceeding as assigned. Revise the brief when standing instructions change; use the latest checkpoint to resume work.

```sh
dev-tools/fire-controller jobs --database /tmp/example-controller.sqlite3 --json bootstrap
dev-tools/fire-controller jobs --database /tmp/example-controller.sqlite3 --json create gate-proof --worker General --title 'Prove the gate' --summary 'Gate proof' --body-file /tmp/current-brief.md
dev-tools/fire-controller jobs --database /tmp/example-controller.sqlite3 --json assigned --worker General
dev-tools/fire-controller jobs --database /tmp/example-controller.sqlite3 --json revise gate-proof --expect-revision 1 --summary 'Proof complete' --body-file /tmp/revised-brief.md
dev-tools/fire-controller jobs --database /tmp/example-controller.sqlite3 --json update gate-proof --body 'Focused proof passed; next is handover.'
dev-tools/fire-controller jobs --database /tmp/example-controller.sqlite3 --json checkpoint gate-proof --done 'Focused proof' --next 'Handover' --pointers '{"branch":"codex/example","proof":"focused test"}'
```

Primary designation is independent of active/parked/blocked/completed status. A blocked primary stays visible with its blocker. One primary job exists per worker; `--secondary` creates other work without the designation. An explicit `revise … --primary` switches the primary atomically; parking or completing clears it. A checklist item does not force the whole job’s state. `revise` can change current instructions and public fields together. Current-state mutations require `--expect-revision`, so concurrent edits fail clearly rather than overwrite newer instructions. Appended updates, checkpoints and notes do not replace the brief.

Use `read` for the current full brief, relevant pending reminders and latest checkpoint/updates. `assigned` retrieves designated primary jobs by default; `assigned --all-jobs` explicitly includes parked and secondary full briefs. Use the lightweight `list` to select other work before reading it. `list`, `search`, `assigned`, `assign`, `park`, `resume`, `block`, `complete`, `checklist add|change|done`, `history` and `diff` cover the normal lifecycle; consult `--help` for exact options. `history` defaults to a bounded page; `--all` opts into full revision history. Brief versions are stored only when the brief changes, while automatic state revisions and chronological updates remain available. `read --full-history` is explicitly unbounded. A checkpoint supplies context pointers and does not automatically restore an AI chat’s context.

## Deferred reminders and source inputs

`note --worker Overseer --phase 'Unit 3A' --kind source --body-file …` parks a source input for a future phase. `notes` accepts explicit worker/job/phase selection. A job selector resolves its friendly name or ID to the same existing stable job ID on creation, listing and correction; an unknown explicit job fails without changing the note. Worker/phase-only future notes require no job. `note-status … --expect-revision N` consumes, dismisses with a reason, or reopens a note; `note-revise` also corrects its body or scope. Automatic `note-history` retains earlier dispositions and reasons. Stale corrections fail clearly; consumed and dismissed states are not irreversible. No intelligent trigger, scheduler or task graph runs. Future Unit 3A/3C pointers stay Overseer-owned until explicitly reassigned when relevant.

Public summary, progress, blocker and selected checklist text are intentionally public. Working briefs, detailed updates, notes, checkpoints, source files and chat IDs are private. Do not store credentials in either surface. Public export uses a field allowlist rather than dumping stored objects.

## Lane activity and private inbox

`jobs lanes` derives activity from jobs: another active job keeps the worker active after a job completes; completing the last active job makes it idle. A blocked primary remains visible. `jobs lane-pause WORKER --reason 'Short public explanation'` deliberately pauses unfinished work; the pause persists across creating, assigning and updating jobs until `jobs lane-resume WORKER`. Either transition can include `--job`, Markdown update, or `--done`/`--next` checkpoint in one transaction. There is no heartbeat, presence inference or review gate.

Explicit `--worker-identity WORKER` before the command area, or `FIRE_CONTROLLER_WORKER`, identifies the command actor for a cheap unread notice. It is supplied metadata and grants no human permission. Unread notices never gate jobs or reviews. Job/inbox/map structured output with known identity adds `_fire_controller` metadata containing worker and unread count; a list/scalar result uses a `result` envelope. Without identity, existing result shapes remain unchanged. `jobs public-export` always retains its complete public allowlist without caller inbox/diagnostic metadata; its unread notice uses stderr even in JSON mode. Readable notices go to stderr. Delegated reviews always keep their existing stdout JSON contract and stream provider output immediately; their unread notice uses stderr only.

```sh
dev-tools/fire-controller inbox --database /tmp/example-controller.sqlite3 bootstrap
dev-tools/fire-controller --worker-identity General inbox --database /tmp/example-controller.sqlite3 send Overseer --body 'The isolated proof is ready.' --pr 123
dev-tools/fire-controller --worker-identity Overseer inbox --database /tmp/example-controller.sqlite3 --json list --unread
dev-tools/fire-controller inbox --database /tmp/example-controller.sqlite3 read MESSAGE_ID
dev-tools/fire-controller inbox --database /tmp/example-controller.sqlite3 ack MESSAGE_ID
```

Messages may reference a job, PR and `--reply-to` message. Reading marks seen; acknowledgment describes message handling and does not complete requested work or any job. There are no delivery guarantees, automated reminders, escalation, chat-service calls or automatic waking. Message bodies are private and never public exports.

## Project map and website source migration

A job may carry `--workstream-id` to connect its existing public assignment fields to a curated map track. Minimal SQL workstream records hold mutable track/phase/return-point state and current work/milestone values with guarded revisions. Curated headings, purpose, links, domain tracker filenames and editorial return triggers stay file-owned; canonical tracker Markdown still supplies completion/proof counts. The map is a grouped projection of the same jobs and notes, not another brief manager.

The private site's two JSON inputs have field-level boundaries. `status.json` lane assignment/status/up-next/blocker values become jobs and worker pause state; brief filenames and verification timestamps remain import provenance. Mutable review-front/stack fallback mirrors are removed in favour of the controller snapshot; `review_tool` remains local configuration. `progress.json` mutable track/phase/return-point states and current work/milestones migrate to SQL; editorial fields stay files. Import previews exact changes, target job revisions and source fingerprints, preserving imported original field values and provenance. Repeated completed apply is idempotent and never replaces an existing private brief or replays old public fields over later progress. A conflicting source/schema fails clearly. Job reconciliation and map import are staged transactions; if interrupted before the map import record, inspect the preview and retry. Source fingerprint checks reject source changes between preview and map apply. The original files remain the retained import sources; after cutover their mutable values are ignored at runtime. Validate the complete migration in a disposable database before the separately authorised live cutover.

```sh
dev-tools/fire-controller map --database /tmp/example-controller.sqlite3 bootstrap
dev-tools/fire-controller map --database /tmp/example-controller.sqlite3 --json import /tmp/status.json /tmp/progress.json
dev-tools/fire-controller map --database /tmp/example-controller.sqlite3 --json import /tmp/status.json /tmp/progress.json --apply
dev-tools/fire-controller map --database /tmp/example-controller.sqlite3 --editorial /tmp/progress.json read delivery
dev-tools/fire-controller map --database /tmp/example-controller.sqlite3 --editorial /tmp/progress.json update delivery --expect-revision 1 --now 'Focused proof complete'
```

Bootstrap/import current briefs first, then reconcile legacy site public fields. Associate a job with an imported editorial track ID using `jobs revise … --workstream-id ID --expect-revision N`; unknown IDs are refused. Editorial mapping validates phase labels and return-point headings; it does not copy tracker completion into SQL.

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

The [private status adapter](integration/README.md) is a narrow patch against a fingerprinted private source baseline. It reads public job fields in one batch for lane cards. Full job details/history are local HTTP routes and never static publish files; local job links are injected only for local responses. Public staging retains an explicit allowlist. Markdown rendering escapes raw HTML and rejects unsafe link schemes and embedded images. The standalone PR plus adapter handover is the delivered boundary; the live site is not already upgraded.

The existing SQLite snapshot/restore helper includes optional job, lane, inbox and map schemas in its validated allowlist, logical readback and persisted-text screening. It preserves current briefs, all historical versions, updates, note corrections, messages, map revisions and import provenance. Old SQLite review writers update their own state document and retain these independent tables; old backup tools refuse unknown schema rather than drop it. Job schema/version compatibility is validated independently and does not become a review admission condition.

## Subagent model provenance

New native review attempts require `records subagent start --model <actual-tool-model>` and optionally `--reasoning-effort <actual-effort>`. Use tool metadata; do not infer a model from a reviewer label. The API enforces the same rule. Existing historical records/imports remain readable with unknown model, and no old counts are changed or fabricated metadata backfilled. The browser review header displays declared model information from the exact linked attempt. This metadata does not supply provider taper credit or change admission policy. The new flag takes effect only when the reviewed controller is selected for that project.

## Focused proof

Run `python3 -m unittest discover -s dev-tools/validation -p 'test_fire_controller_*.py'`, the affected existing SQLite backup/store/controller and review-record test modules, and canonical Ruff on changed Python files. Independent hands-on proof uses disposable databases and a full isolated website copy; no live provider requests, controller writes or public publication. Measure normal command/page operations on three-worker and larger fixtures, including cold/warm processes, review baseline delegation, and full refresh/public staging. Record slow exceptions honestly rather than introducing speculative caching.
