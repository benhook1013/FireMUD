# FireMUD Main Worker Handover

Local ignored continuity context for the main Codex worker. Canonical architecture, implementation trackers, `AGENTS.md`, and repository workflows outrank this file. Refresh all live PR, review, CI, branch, and worktree facts before acting on them.

## Current Boundary

PR #2658 completed the licensing and hosted-policy integration. The whole-corpus authority phase remains paused before unit 5A while its execution plan is reviewed; do not start corpus review or edits until Ben explicitly starts it.

- PR `https://github.com/benhook1013/FireMUD/pull/2658` merged on 2026-08-29 NZ time with head `f62db01d3`.
- The final known hosted and CLI findings were fixed before merge; review threads were resolved. The last hosted checkpoints continued to produce one small useful alignment correction each, and Ben explicitly chose the faster merge boundary.
- Final required CI passed. The merged licensing worktree plus local and remote `codex/license-policy-reconciliation` branches were removed after containment proof.
- `develop` is clean and fast-forwarded to `7c5098c97`. The approved tracked `.codex/config.toml` retains the intentional memory-disable settings without the redundant explanatory comment Ben asked to trim.
- CI-reliability PR #2659 is the current implementation/review boundary on `codex/ci-reliability-current`. It ports the real six-file dev-demo/ERD fix onto current `develop`. Its first full CLI review returned zero findings; the first substantive hosted review returned two useful ERD timeout/contract findings, which are being fixed locally. AI never merges or enables or schedules auto-merge.

## Operating Method

- The main `gpt-5.6-sol` worker owns design judgment, decomposition, adjudication, integration, validation, GitHub coordination, and final decisions.
- Use Luna subagents for bounded bulk reading, mechanical edits, focused investigation, and independent review. Every edit is independently inspected and validated by the main worker.
- CodeRabbit hosted review is the merge-readiness signal. CodeRabbit CLI is another review step and may run concurrently; it does not replace hosted taper evidence.
- During an active hosted review, prepare fixes locally but do not push review-invalidating commits until the review terminates.
- Keep hosted review, one CLI review, disjoint delegated fix/verification lanes, and preparation of the next dependency-safe local slice running concurrently whenever safe useful work exists. Publish a coherent parcel as soon as primary validation is green; do not serialize publication behind redundant verification when the independent checks can overlap hosted review and CI. Preserve one preemptible lane for edits or verification; if all lanes are busy when a finding needs edits, wait or interrupt the least valuable preemptible review. The main worker never performs bulk/manual fixes. Do not duplicate reviews or manufacture lane-filling work; record why any otherwise-useful lane is intentionally idle.
- A PR parcel is a packaging, merge, and hosted-review boundary. It is not a deep-review scope.
- A cognitive review unit is a coherent contract family, normally about 8–18 substantive owner, consumer, ADR, product, and tracker documents. Dense shared-contract hubs may be smaller. Each pass is recorded as either `whole-section-unrestricted` over the complete unit manifest or `narrow-focused`; narrow work supplements broad discovery and never counts toward its floor or terminality. Material claims may be followed into unchanged authority, implementation, schemas/migrations, tests, trackers, and proof.
- Each ordinary unit has a three-pass fresh unrestricted whole-section Luna floor; 2B, 5A, and 6C have four. Continue beyond the floor until the recorded unrestricted whole-section useful-finding taper is terminal; a narrow zero cannot satisfy it, and Ben may explicitly stop earlier.
- CodeRabbit CLI and hosted review inspect the complete active PR, not one unit at a time. Attribute findings to unit-local progress. Between Luna passes, fix and cycle PR-wide CLI to zero useful work for the affected units. Hosted runs concurrently and must not park local Luna/fix/CLI work; never push while it is active.
- Review correctness, completeness, authority, and cross-document consistency first. Surface consequential deduplication secondarily when documents compete to own a rule or copied normative text can drift. Preserve local API, persistence, transport, operational, user-facing, example, evidence, and proof consequences. Discovery is unrestricted; implementation is adjudicated per finding: fix PR-introduced defects and bounded, low-risk, section-coherent pre-existing corrections when that is simpler than extensive drift prose or tracker debt, but defer/split only substantial capability slices whose persistence, migration, concurrency, design, or proof cost would displace review. Track deferred gaps separately from admitted fixes.
- De-prioritize exhaustive point-in-time implementation-status synchronization in Luna discovery. Fix verified status drift when it surfaces, but treat it as merge-taper maintenance rather than stable-contract work unless it creates an unsafe instruction, false implementation claim, impossible proof gate, or authority contradiction. Track hosted stable-contract findings separately from status-maintenance findings; repeated status-only hosted rounds do not hold an otherwise tapered PR indefinitely.

## Program State

The capability taxonomy, allocation, decision inventory, implementation/proof reconciliation, human-led adversarial review, and selective ADR-family imports are merged to `develop`. The imported set is 182 historical decision keys plus three direct licensing/hosted-service decisions through ADRs 0179–0181. The remaining design-alignment phase is the planned whole-corpus authority review, tracker reconciliation where status or proof changes, and complete-corpus validation. That phase is currently paused before its first unit by Ben's explicit instruction.

The detailed current phase and provisional review partition are in `tmp/main-slice-worker-program.md`. The executable 23-unit/7-parcel review tracker is `tmp/whole-corpus-authority-review-plan.md`. Canonical counts and status live in `design/project-management/design-alignment/README.md`.

## Workspace Snapshot And Cleanup Safety

At the 2026-08-29 snapshot:

- Main worktree: `/home/ben/src/FireMUD-wsl-copy` on clean `develop` at `7c5098c97`; `.codex/config.toml` is now tracked through merged PR #2658.
- Parked source worktree: `/home/ben/src/FireMUD-ci-workflow-reliability` on `codex/ci-workflow-reliability`; preserve only until the transplanted PR #2659 branch is verified and its lineage cleanup is safe.
- Active CI worktree: `/home/ben/src/FireMUD-ci-reliability-current` on `codex/ci-reliability-current`, current PR #2659. The exact parked six-file commit was cherry-picked onto current `develop` without stale-history rebase.
- The licensing source branch `codex/license-policy-alignment` was deleted after proving its tip is contained in the active PR branch; the root worktree was returned to `develop` without disturbing `.codex/config.toml`.
- The sole remaining historical remote topic ref, `design/adversarial-decision-review`, is retained only for three Ben-authored post-import semantics from `1c028e5f`. Independent audits classify all three as REWORK rather than wholesale import: region-scoped Weather and deterministic persisted plan ordinals are directionally sound but need precise current-owner decisions; smoke/reset isolation has a real safety gap but needs proportional disposable-versus-persistent environment wording. Seven other branch-era semantics are already present on `develop` in refined/renumbered form. Delete the remote after the three unique items are integrated or explicitly rejected.
- Two unique ignored environment files from the retired PR-2644 worktree were moved without displaying their contents to `/home/ben/src/FireMUD-local-env-backups/pr2644-preflight-foundation`, with directories mode `0700` and files mode `0600`.
- The duplicate `Github` remote was removed after `main` was repointed to identical `origin/main`; stale Renovate tracking refs were pruned, while the open TypeScript Renovate branch was preserved.

Before later removal, refresh with `dev-tools/validation/report-worktree-pr-topology.sh`, inspect exact status including ignored files, map the PR/dependency state, and prove containment or deliberate backup intent.

## Temporary Files

- `tmp/main-slice-worker-handover.md`: current operational continuity; refresh rather than accumulating old PR logs.
- `tmp/main-slice-worker-program.md`: current phase and review-unit plan.
- `tmp/whole-corpus-authority-review-plan.md`: executable unit/pass/parcel/CodeRabbit tracker for Ben review.
- `tmp/ai-assisted-manual-testing-tracker.md`: preserve as separate hosted manual-gameplay proof planning.
- `tmp/shipped-game-profiles-idea.md`: preserve as separate product/content ideation.

These repository-root `tmp/` files are ignored continuity material, not canonical design and not automatically commit candidates.

## Immediate Next Actions

1. Finish, validate, and review PR #2659; retire the parked CI worktree/branch only after containment is proved.
2. Complete adjudication of the three retained design semantics, integrate their reworked current-owner form or explicitly reject them, then remove the historical remote branch.
3. Keep corpus execution paused until Ben starts it. When started, begin with Shared Runtime unit 5A under `tmp/whole-corpus-authority-review-plan.md`.
4. Maintain section-local Luna terminals and PR-wide CLI/hosted terminals separately; do not let a hosted wait idle the local review pipeline. Keep the fix/verification and next-slice lanes populated when safe work exists, publish after primary validation, and record any intentional idle lane with its concrete reason.

## Human-Controlled Boundaries

- Ben performs every merge and auto-merge action.
- Stop for genuine new or competing consequential design states, not routine local implementation or documentation choices.
- The main worker scopes, adjudicates, inspects delegated diffs, verifies, and coordinates; it does not directly implement repository fixes. When known fixes are queued, normally reserve one subagent lane for delegated fixes and use remaining lanes for unrestricted whole-section discovery. Do not let corpus work absorb a substantial capability slice; record deferred implementation/proof gaps separately from admitted fixes.
- Preserve concurrent and dirty work. Never restore, reset, clean, or stash without explicit human authorization.
