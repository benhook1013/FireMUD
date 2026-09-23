# Gameplay task brief: complete PR #2838

Status: active, awaiting current Hosted result and later parent-merge reconciliation

Owner: Gameplay

## Current state

- PR: #2838, the review-duration and Codecov tooling change.
- Published head: `87194112fa18c731ed40ba6f38e43fcb0578b48d`.
- Current base: #2835 branch `codex/priority-stacked-previews`.
- Unique scope: 14 files at the last verified checkpoint.
- CLI checkpoints: `0/0 → 1/1 → 1/1 → 0/0 → 0/0 → 0/0`. Both accepted fixes are integrated and the final three rounds establish CLI taper on the published head.
- Hosted coverage was previously missing because trigger `5780604810` ended rate-limited without a reset time. The human has now manually triggered a new Hosted review on #2838. Treat that existing request as authoritative and do not create a duplicate.
- #2835 is currently being merged by Overseer. Its reviewed head is unchanged, but the final `develop` merge commit is not yet available.

## Immediate assignment

Work only on #2838.

1. Locate and consume the already-triggered current Hosted review. Report its raw found/accepted counts separately from unresolved-thread counts.
2. Adjudicate every attributable finding. While Hosted remains active, keep any fixes local and do not publish review-invalidating commits.
3. After Hosted terminates, integrate accepted fixes through bounded implementation delegation, inspect the returned diff, run focused validation, publish one coherent batch, and resolve owned threads.
4. If a useful Hosted finding changes the owned patch, continue corrected-state Hosted review until one completed zero-useful round or an explicit report boundary. A rate limit never counts and never advances the target.
5. Do not restart CLI merely to repeat its existing taper. If Hosted-driven fixes materially change behavior that CLI previously assessed, run only the additional CLI work justified by that changed boundary and record the reason.
6. Keep the PR body, LOC metadata, and review checkpoints current.

## Parent merge and reconciliation

Do not retarget, rebase, or merge `develop` into #2838 until Overseer appends a dated reconciliation instruction to this brief with #2835's exact merge commit.

When that update arrives, follow it rather than remembered chat instructions. The expected normal operation is to reconcile current `develop` into #2838 without rewriting reviewed history, retarget #2838 to `develop`, prove its unique owned patch and patch identity, rerun affected validation and required CI, and make an explicit evidence-retention judgment for any parent-only movement. The appended instruction will confirm the exact operation and commit.

## Boundaries

- Do not merge or enable auto-merge.
- Do not touch #2818, #2826, #2827, #2828, #2829, #2839, or #2840.
- Do not start the review-control refactor; General owns it as the next child after #2838.
- Do not replace a missing Hosted result with CLI evidence.
- Do not stop merely because the provider rate-limits; preserve #2838 as the Hosted target and report the exact provider state.

## Stopping point

Stop only when both conditions hold:

1. The current Hosted work and every accepted fix are complete at a coherent published #2838 head, with the required corrected-state review evidence.
2. The later Overseer reconciliation update has been applied, #2838 targets `develop`, its unique patch is proven, required CI is terminal, metadata is current, and a concrete merge-readiness handoff is available.

If condition 1 completes before #2835 merges, remain on #2838 and wait for the appended reconciliation update; do not advance to another PR.

## Required handoff

Report the final head and base, unique file count, Hosted and any additional CLI sequence, finding significance, open/outdated thread counts, exact required-CI state, patch-identity/reconciliation proof, body/LOC freshness, mergeability, and any real remaining blocker.

## 2026-09-23: #2835 merged; reconcile after active Hosted terminates

#2835 merged into `develop` as `c45a1e3e98253af53740943940eb799fd5877f9b`. This section activates the parent-reconciliation work that the original brief deferred.

First consume the already-active manually triggered Hosted review on #2838. Do not publish, retarget, or otherwise invalidate that review while it is active. Adjudicate and preserve any accepted fixes locally until its terminal result is recorded.

After the active Hosted result is terminal:

1. Verify the live `develop` tip and record it. It must contain merge `c45a1e3e98253af53740943940eb799fd5877f9b`.
2. Merge current `develop` normally into `codex/review-checkpoint-duration`; do not rebase or rewrite reviewed history.
3. Preserve #2838's owned behavior through any conflict and prove the unique child patch against `develop`, including its file count and stable patch identity before and after reconciliation.
4. Retarget #2838 from `codex/priority-stacked-previews` to `develop`.
5. Apply and publish any accepted Hosted fixes in the same coherent corrected-state preparation, then run the focused validation and required exact-head CI.
6. Make the review-evidence judgment explicitly: parent-only movement with an unchanged owned patch may retain existing CLI taper; any material owned-patch change reopens only the review channel whose assessed boundary changed. A useful Hosted fix requires corrected-state Hosted evidence under the main assignment above.

This section supersedes only the original instruction to wait for a later reconciliation update. All other scope, review, boundary, stopping, and handoff instructions remain active.
