# Project Direction Entrypoint

Last refreshed: 2026-09-10. These notes are non-normative continuity guidance. Architecture/design documents own target-state contracts; implementation trackers own implementation, proof, gaps, and handoffs; repository workflow guidance selects process. Refresh owner-reported PR, review, CI, and SHA facts at decision time.

## Confirmed direction

- Continue the separate documentation/corpus-consolidation lane and implementation/proof lanes. Preserve useful cross-boundary findings, but keep substantial persistence, migration, concurrency, runtime, and proof capabilities in explicit owner slices.
- Natural whole-domain PR growth is the default. Split only for a 100-file ceiling or a large coherent dependency. A file cap never suppresses a verified useful finding.
- Root/Astra directs, adjudicates, delegates bulk work, and makes final strategic decisions. Sol/medium is the synthesis lane; Luna is preferred for bounded routine reading and execution.
- Hosted finding trends and significance determine PR taper; CLI is independent supplementary defect discovery/verification and does not establish taper. A fresh whole-domain assessment establishes domain completion separately. Unchanged HEAD alone never makes another independent CLI pass redundant; judge usefulness from findings.
- Broad domain search and CodeRabbit-fix review may run concurrently. Consume and adjudicate prior search before rerunning it. Final fresh domain assessment is separate from hosted PR-closure taper.
- Do not automatically restart old paused work unless the current assignment explicitly continues it. Existing hourly automation is rescue-only; create no new mechanism. Side questions and status requests do not terminate active Overseer work; finish authorized work unless Ben explicitly pauses it.

## Active lanes (owner-reported/current-session facts)

- Worker `01a07037-e403-7b91-a500-34fc9f876a12` (Sol/high) remains explicitly human-paused. Previously recorded Worker worktrees and protected artifacts remain preserved context; they do not represent an active implementation assignment or authorize work. Worker and Gameplay report no active external `tmp` use; their own artifacts are protected.
- Gameplay `01a071ae-5336-7853-9960-8087e668dd71` (Sol/high) owns the playable-demo lane, with intended delivery order #2694 inert controller/trusted lifecycle prerequisite on `develop` -> #2713 secured bridge/producer activation -> #2686 final gameplay. This is sequencing intent, not a claim that the current PR bases already form that stack; active Gameplay worktrees remain `gameplay-demo` and `hosted-identity-controller`.
- Available reports say #2680, #2682, and #2693 migration-squash work merged. Treat exact readiness, review coverage, and SHAs as transient; obtain compact owner reports before decisions. Do not claim independent live verification from this index.
- [Gameplay implementation/mechanics queue](./overseer-gameplay-followups.md) owns the Gameplay queue and is the source to consult at delivery or phase completion.

## Current work and pending decisions

- **5B:** Current 5B review scopes and completion are tracked in [PR #2698](https://github.com/benhook1013/FireMUD/pull/2698), [PR #2677](https://github.com/benhook1013/FireMUD/pull/2677), [PR #2678](https://github.com/benhook1013/FireMUD/pull/2678), and [PR #2679](https://github.com/benhook1013/FireMUD/pull/2679). #2698 is merged. Worker’s selected next 5B lane is #2677, explicitly paused while Gameplay finishes its stack; #2678 and #2679 remain parked. No parallel 5B ledger or final extra sweep.
- **Current Gameplay/CI transition:** Gameplay continues the #2694 fixes and full Hosted/CLI proof toward inert prerequisite merge readiness. Real Kubernetes admission-family proof belongs inside the existing activation successor #2713, preferably without an extra PR; request a split only for concrete scope, cap, or dependency evidence. The pinned disposable Kind proof runs production policies/bindings with a minimal faithful Certificate CRD fixture and no certificate issuance; its matrix and policy-denial evidence are defined in the [Gameplay queue](./overseer-gameplay-followups.md). It must pass before trusted bootstrap/controller activation, while its absence alone does not block inert #2694 merge. Hetzner installed-environment integration remains required afterward. The human prerequisite merge to verified default `develop`, reviewed immutable controller image plus trust-anchor bootstrap, activation retargeting `develop`, and exact-head hosted preview proof still follow the current prerequisite work. The CI mini-project and later refactor queue remain as recorded in the Gameplay queue; Worker remains human-paused.
- **Pending future discussion:** the shipped-game/profile idea remains a product/content topic, not a selected repository assignment. The archived manual-testing tracker proposed one read-only preview/dev-demo pass through player bootstrap/login/`PLAY`/`LOOK` with reproducible deployed-commit evidence; it does not establish a recurring test programme. Old 5B follow-up material carried residual scope/authentication, migration-proof, and routing candidates requiring fresh develop-head adjudication if revived. Historical main-slice/whole-corpus plans record review sequencing only and do not authorize restart. These source details are checkpointed history, not active lanes.
- **Pending separate review:** `/home/ben/src/FireMUD-coderabbit-rate-limit-fix` requires its own current semantic review/PR. Record it only; do not execute it here. Older process-document patches may be superseded.
- **Dated/unverified carry-forward:** retained Weather/identity/recovery dispositions remain source material until a current owner report resolves them. Historical corpus plans/status ledgers and branch/worktree inventories are checkpointed provenance only and do not authorize work; prior PR/review/merge claims remain unverified. Do not blanket-drop unknown actions from the archived notes.
- **Overseer coordination reminder:** hold completed future assignments privately until the recipient can act at a natural handoff, unless the information affects a current decision.
- **Private-note practice:** these notes are tracked on the local `codex/project-direction` branch; checkpoint meaningful updates and commit cleanups separately so removals remain recoverable, with no publication implied.

## Source and authority pointers

- This entrypoint is an index, not a technical authority. The [canonical design-alignment operating model](../design/project-management/design-alignment/README.md) is authoritative for process; current technical status remains in owning implementation trackers.
- Dated handoffs remain unchanged at [worker-handoff-2026-09-05.txt](./worker-handoff-2026-09-05.txt) and [worker-final-handoff-pr2682-2026-09-05.md](./worker-final-handoff-pr2682-2026-09-05.md).
- Consolidation context and the exact pre-refresh notes are historical provenance retained in the local checkpoint commit; current authority remains this index, the Gameplay queue, and owning implementation trackers.
- Historical FireMUD-wsl-copy plans and ledgers are checkpointed provenance only; their status may be stale. Meaningful findings are tracked in normal Worker records.

## Safety and reporting

- Preserve unrelated dirty/conflicted worktrees and worker artifacts. Do not revert, delete, overwrite, stage, or commit unrelated edits.
- **Validation for this edit:** `git diff --check` and the scoped diff for the two private notes are the only checks run; no tests, lints, or review/CI/PR operations were run.
