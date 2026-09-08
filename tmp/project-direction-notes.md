# Project Direction Entrypoint

Last refreshed: 2026-09-07. These notes are non-normative continuity guidance. Architecture/design documents own target-state contracts; implementation trackers own implementation, proof, gaps, and handoffs; repository workflow guidance selects process. Refresh owner-reported PR, review, CI, and SHA facts at decision time.

## Confirmed direction

- Continue the separate documentation/corpus-consolidation lane and implementation/proof lanes. Preserve useful cross-boundary findings, but keep substantial persistence, migration, concurrency, runtime, and proof capabilities in explicit owner slices.
- Natural whole-domain PR growth is the default. Split only for a 100-file ceiling or a large coherent dependency. A file cap never suppresses a verified useful finding.
- Root/Astra directs, adjudicates, delegates bulk work, and makes final strategic decisions. Sol/medium is the synthesis lane; Luna is preferred for bounded routine reading and execution.
- Hosted finding trends and significance determine PR taper; CLI is independent supplementary defect discovery/verification and does not establish taper. A fresh whole-domain assessment establishes domain completion separately. Unchanged HEAD alone never makes another independent CLI pass redundant; judge usefulness from findings.
- Broad domain search and CodeRabbit-fix review may run concurrently. Consume and adjudicate prior search before rerunning it. Final fresh domain assessment is separate from hosted PR-closure taper.
- Do not automatically restart old paused work unless the current assignment explicitly continues it. Existing hourly automation is rescue-only; create no new mechanism. Side questions and status requests do not terminate active Overseer work; finish authorized work unless Ben explicitly pauses it.

## Active lanes (owner-reported/current-session facts)

- Worker `01a07037-e403-7b91-a500-34fc9f876a12` (Sol/high) owns the current implementation lane; its active worktrees and protected artifacts remain under worker control.
- Worker active worktrees include `corpus-review-5b-admission-authority` and its suffix-integration worktree. Worker and Gameplay report no active external `tmp` use; their own artifacts are protected.
- Gameplay `01a071ae-5336-7853-9960-8087e668dd71` (Sol/high) owns the playable-demo lane, including #2686 front and #2694 controller successor, with active worktrees `gameplay-demo` and `hosted-identity-controller`.
- Available reports say #2680, #2682, and #2693 migration-squash work merged. Treat exact readiness, review coverage, and SHAs as transient; obtain compact owner reports before decisions. Do not claim independent live verification from this index.
- [Gameplay implementation/mechanics queue](./overseer-gameplay-followups.md) owns the Gameplay queue and is the source to consult at delivery or phase completion.

## Current work and pending decisions

- **5B:** Current 5B review scopes and completion are tracked in [PR #2698](https://github.com/benhook1013/FireMUD/pull/2698), [PR #2677](https://github.com/benhook1013/FireMUD/pull/2677), [PR #2678](https://github.com/benhook1013/FireMUD/pull/2678), and [PR #2679](https://github.com/benhook1013/FireMUD/pull/2679). Worker executes them; no parallel 5B ledger or final extra sweep. #2698 is current; the others are parked.
- **Pending future discussion:** the shipped-game/profile idea remains a product/content topic, not a selected repository assignment. The manual-testing tracker is largely covered by Gameplay's purpose; retain it as dated source material, not a new lane.
- **Pending separate review:** `/home/ben/src/FireMUD-coderabbit-rate-limit-fix` requires its own current semantic review/PR. Record it only; do not execute it here. Older process-document patches may be superseded.
- **Dated/unverified carry-forward:** retained Weather/identity/recovery dispositions, old corpus-plan/status ledgers, historical branch/worktree preservation reports, and prior PR/review/merge claims remain source material until a current owner report resolves them. Do not blanket-drop unknown actions from the archived notes.

## Source and authority pointers

- This entrypoint is an index, not a technical authority. The [canonical design-alignment operating model](../design/project-management/design-alignment/README.md) is authoritative for process; current technical status remains in owning implementation trackers.
- Dated handoffs remain unchanged at [worker-handoff-2026-09-05.txt](./worker-handoff-2026-09-05.txt) and [worker-final-handoff-pr2682-2026-09-05.md](./worker-final-handoff-pr2682-2026-09-05.md).
- Consolidation context is [tmp/consolidation-2026-09-07/README.md](./consolidation-2026-09-07/README.md). The exact pre-refresh notes are archived at [tmp/archive/project-direction-notes-before-2026-09-07.md](./archive/project-direction-notes-before-2026-09-07.md).
- Historical FireMUD-wsl-copy plans and ledgers are provenance only; their status may be stale. Meaningful findings are tracked in normal Worker records.

## Safety and reporting

- Preserve unrelated dirty/conflicted worktrees and worker artifacts. Do not revert, delete, overwrite, stage, or commit unrelated edits.
- **Validation for this edit:** only archive hash comparison, existing-link checks, and `git diff --check` are appropriate; no tests or review/CI/PR operations were run.
