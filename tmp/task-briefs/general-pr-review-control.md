# General task brief: unified PR review control

Status: active

Owner: General

## Starting point

Create one new draft PR stacked directly on #2838 and inserted before #2818. The preparation parent is #2838 at `87194112fa18c731ed40ba6f38e43fcb0578b48d`; verify the live head before beginning and reconcile only through explicit parent handling. Do not edit #2818 or later feature PRs.

## Objective

Replace the fragmented Hosted/CLI review operator surface with one reliable command and one private repository stack configuration. The controller must preserve independent Hosted and CLI progress while preventing workers from advancing either channel on missing evidence, rate limits, stale parents, or remembered chat instructions.

## Public interface

Provide one human-facing command, `dev-tools/pr-review`, with a small coherent surface equivalent to:

- `pr-review stack set/show`
- `pr-review status [--pr]`
- `pr-review run hosted|cli [--expect-pr]`
- `pr-review evidence`
- `pr-review decide`

There is one repository stack, not named Gameplay, General, or Document trains. Store its versioned private state under the shared Git common directory, for example `<git-common-dir>/firemud/pr-review-stack.json`. The implementation must provide an isolated state-path or test-adapter mechanism for hands-on acceptance without touching canonical state or consuming review quota.

## Review-channel policy

- `run hosted` and `run cli` derive the permitted target. An optional expected PR asserts the result; it never selects a different target.
- Hosted remains on the earliest PR whose Hosted policy is incomplete.
- A Hosted rate limit, including a terminal provider response without a reset time, never counts as coverage and never advances Hosted. This must reproduce #2838's `5780604810` incident: #2838 remains the Hosted target while CLI may progress independently.
- CLI may advance serially through every consecutive stable and review-eligible PR after the current PR reaches three consecutive zero-useful rounds.
- An accepted finding resets that channel's streak.
- Either channel stops at a held, unstable, unreconciled, over-ceiling, or judgment-blocked PR.
- A cross-channel head change after taper produces `JUDGMENT_REQUIRED` until an explicit head/checkpoint-bound reopen-or-retain decision is recorded.
- Matching the canonical repository workflow, default Hosted taper is two consecutive completed full corrected-state zero-useful reviews after the latest accepted useful fix. Support an explicit head- and patch-bound human override for a deliberate one-dry close-out of a narrowly bounded case; never infer it automatically. Critical/security findings, broad corrections, and material boundary changes remain on the normal two-dry rule unless the recorded decision is more conservative.
- Remove ordinary workflow guidance that presents incremental `@coderabbitai review` as an alternative. A file-ceiling refusal stops for a topology decision.

## Stack reconciliation

For every PR after the first, derive the effective parent as the nearest preceding unmerged configured PR, or the default base after all predecessors merge. Before review:

- verify the live GitHub base branch matches the effective parent;
- verify the effective parent's live tip matches the expected parent head;
- verify that exact parent tip is an ancestor of the candidate head;
- audit the complete adjacent effective-parent chain.

Anchor evidence to the child head, effective parent identity and head, merge base, and unique patch identity. Parent movement marks downstream evidence `PARENT_MOVED` and blocks normal quota until reconciliation. An unchanged patch identity may retain earlier taper only through an explicit recorded judgment; overlapping or material movement reopens review. Equivalent history after squash or rebase also requires an explicit head-bound judgment.

Provide no generic force option. A narrow CLI-only `--allow-unreconciled --reason ...` may run one exact-head and parent-head-pinned provisional discovery pass. Provisional evidence cannot satisfy taper, advance either channel, or support merge readiness. Hosted remains refused.

## Refactor boundary

Move GitHub attribution, locking, durable Hosted-trigger state, isolated CLI worktrees, evidence parsing, status composition, policy, and stack behavior into clearly named internal modules. In the same PR delete these public tools and all references to them, with no compatibility shims:

- `dev-tools/request-coderabbit-review.sh`
- `dev-tools/run-coderabbit-review.sh`
- `dev-tools/validation/check-coderabbit-review.py`
- `dev-tools/validation/report-pr-review-checkpoints.py`
- `dev-tools/validation/report-pr-status.py`

Update `AGENTS.md`, PR lifecycle guidance, AI delegation guidance when applicable, `dev-tools/README.md`, CI, tests, and every call site. Prove repository-wide that no legacy command reference remains. Preserve historical checkpoint comments and private trigger/capture evidence formats.

## Validation and review

Use no CodeRabbit on this implementation. Run up to six fresh serial Luna xhigh whole-boundary cycles. Integrate, validate, publish, and record every useful cycle before the next. Stop at the first zero-useful cycle or after six productive cycles.

## 2026-09-23: metadata-check rollup repair

Include the reproduced #2838 metadata-edit defect in this same PR. Three rapid body/metadata edits on unchanged head `304ea81ce` launched three workflow waves under `cancel-in-progress`; superseded runs left cancelled Validation, Security, and Smoke summary jobs attached to the same commit. The first-page check view hid some of them, while the full 230-check inventory made the aggregate rollup `FAILURE` despite all five required gates passing. Skipped summary jobs also exposed their unevaluated `${{ ... }}` job-name expression as the visible check name.

Correct the workflow boundary so rapid metadata-only edits cannot leave cancelled optional summaries that poison the commit rollup. Preserve fail-closed required-gate behavior and substantive-run cancellation semantics. Use stable, readable check names rather than conditional expressions that render literally when skipped. Add focused contract coverage for repeated metadata edits/concurrency behavior and verify the final aggregate contains no cancelled/failed residue while required Validation, Security, License, Smoke, and CodeQL contexts remain authoritative. Overseer reran the cancelled metadata attempts and restored aggregate `SUCCESS`, but GitHub still reports #2838 `BLOCKED`; do not claim this workflow repair explains or clears that separate merge-state label.

Implementation boundary confirmed from current workflows: `ci.yml`, `security.yml`, and `smoke.yml` each share one `metadata` concurrency group per PR with `cancel-in-progress: true`. Give each lightweight metadata-only run a unique group (for example, include `github.run_id` in that branch of the expression), while preserving the stable per-PR `required` group and its cancellation behavior. Do not merely set `cancel-in-progress: false` on a shared metadata group: GitHub Actions can still replace an older pending run. Prove two rapid metadata edits leave neither cancelled summary checks nor duplicate substantive work. A close/reopen is not a status-refresh procedure; it starts new substantive CI and makes required gates pending again.

Additional observed edge: an ordinary single #2838 body edit during substantive CI launched `PR Metadata Edit (Smoke Gate)`, whose preservation helper reported `Relevant prior Smoke Gate is still in_progress; retrying attempt 61/92`. The helper has a 23-minute deadline while substantive Smoke Gate allows 25 minutes. A metadata-only job can therefore fail before a valid required gate completes. Overseer is correcting this Smoke-specific pending behavior directly on #2838, so #2844 must reconcile and verify the final parent implementation rather than duplicate it. Preserve the distinct non-required metadata context and substantive required gate. The other metadata-concurrency repairs above remain #2844-owned.

Parent update: #2838 published `ddb4e8ebd` with the Smoke-specific `allow-pending` input and exact typed current-base refresh fallback after GitHub supplied a lagging event `base.sha`. All five required gates passed and the user's pre-enabled auto-merge merged #2838 as `9f8ceba9bf8ff44866db11543d7bd27e95d50fbd` on 2026-09-23. #2844 should retarget to `develop` before reconciling the merged parent at its review boundary, preserve its own metadata-concurrency correction, and validate both contracts. Do not recreate this parent fix in the child.

The final handoff must include the exact head, parent, unique file count, operator commands, migrated/deleted surfaces, focused and full validation, review-cycle results, known limits, clean worktree state, and PR metadata state.

## 2026-09-23: merge-state diagnosis

Make `pr-review status` explain merge readiness from live, paginated evidence rather than treating GitHub's coarse `mergeStateStatus` as a diagnosis. Show the current head/base, draft/conflict state, every required context with its expected app and latest exact-head result, pending and failed checks from the complete check inventory, review decision and unresolved threads, and the aggregate rollup separately. Distinguish optional failed/cancelled checks from required gates. When those known conditions pass but GitHub still returns `BLOCKED`, report `BLOCKED — cause not exposed by available API` instead of guessing that CI, CodeRabbit, or the metadata-summary defect caused it. Include a focused fixture for #2838's prior state: all five required gates and aggregate rollup successful, zero unresolved threads, yet `BLOCKED`; and a fixture for its close/reopen state with newly pending required runs. Keep this diagnostic read-only and do not trigger workflow reruns or metadata edits to make the label change.

## Overseer acceptance gate

Do not hand the PR to Gameplay until the Overseer completes at least two fresh root-owned hands-on acceptance runs against isolated state:

1. Happy path: configure/show the stack, derive both targets, inspect status/evidence, advance CLI across multiple stable PRs, record a head-bound judgment, and assess output usability.
2. Adversarial path: wrong target, Hosted rate limit with and without reset time, accepted-finding reset, parent movement, unreconciled descendants, merged-parent effective-base selection, provisional CLI override, stale overrides, locking, and atomic writes.

Those checks must execute `dev-tools/pr-review`, inspect persisted isolated state and exit codes, and report confusing or unsafe behavior. Accepted defects return to General before Gameplay receives review priority.

## Stopping point

Publish a clean draft at the exact reconciled parent, refresh its body and LOC metadata, consume only any already-running Luna review and its valid findings, complete affected validation, and stop for Overseer hands-on acceptance. Do not start a fresh Luna review round: further discovery would delay the CodeRabbit handoff. Do not use CodeRabbit, merge, enable auto-merge, start Gate 2, or touch #2818 and later feature PRs.

## 2026-09-24: Overseer hands-on acceptance findings

General handed off draft #2844 at `7b125df94d15024d83fec4a5003872bbd941d795`, directly against `develop`, with 48 unique files and a clean worktree. Two fresh root-owned subagents executed the actual `dev-tools/pr-review` command against separate mode-0700 isolated synthetic fixtures; neither contacted GitHub, consumed review quota, or edited the repository.

- Happy path passed: three configured PRs, independent Hosted/CLI targets, CLI advancing across two completed PRs, exact-head retain judgment, isolated state persistence, and no simulated quota consumption.
- Adversarial gates passed for wrong target, Hosted rate limits with and without reset time, accepted-finding reset, merged-parent selection, moved-parent and unreconciled refusal, stale exact-bound decisions, locking, and atomic state writes.
- **Accepted acceptance defect:** two immediate identical synthetic `run cli --allow-unreconciled` calls both succeeded with `provisional=true`, because the first simulated run left no isolated provisional evidence. Pre-seeding the evidence made the second call fail, but that does not prove the end-to-end one-shot boundary. Make acceptance mode record enough isolated synthetic evidence for the second identical call to fail, without contacting a provider, changing canonical state, counting provisional evidence toward taper, or weakening production behavior. Add focused regression proof and repeat this hands-on acceptance case.
- **Accepted safety/usability correction:** whole-stack `status --json` omits the `isolated=true`, `network=false`, `review_quota=false` marker shown by scoped status. Add the marker to the whole-stack acceptance output so operators cannot mistake fixture status for live status. Keep fixture/live output distinguishable in focused proof. Explicit target fields and the synthetic `evidence` layout may be improved if they are narrow; they are not handoff blockers.

Fix only those accepted points, inspect the resulting diff, run affected focused validation and required CI on the corrected head, update PR body/LOC and ledger, and return for a narrow repeat of the failed acceptance checks. Start no new Luna discovery or CodeRabbit review. Gameplay priority remains held until Overseer confirms acceptance and exact-head required CI.
