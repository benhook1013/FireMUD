# Document task brief: Access corpus overnight runway

Status: active

Owner: Document

This assignment supersedes the Cycle 12 stop/report boundary and every earlier resume instruction in this file. Progress reports are checkpoints, not pauses. The authorized sequence ends after Unit 1D; do not start Parcel 2 without a later assignment.

## Settled parent

- Combined 5C/5D draft #2839 is final at `a38861d006521b1e604b525a01d21ba0ce2de8f3`, 94 files unique to #2829.
- Whole-unit Cycles 14 and 15 were consecutive corrected-state dry cycles, so corpus taper is established.
- The final exact-head Validation run executed database tests but remained red on four inherited Automation fixtures/assertions and one inherited Game Session constraint-name assertion. #2839 changes none of those failing paths. Preserve this proof limit; do not reopen #2839 discovery or claim it is CI-green.
- #2839 was propagated normally into #2840 with #2840's unique patch preserved.

## Active target

- #2840 is the Unit 1A child of #2839.
- Published head: `b9c155de9685f47b2efd16490abac090c4f7de20`.
- Unique scope: 51 files.
- Cycles 1–8 were productive; Cycle 9 was dry, and Cycles 10–12 were productive. Cycle 11 and Cycle 12 each produced two distinct useful findings. The PR body and private ledger hold the detailed counts and dispositions.
- Current dry streak: zero. Unit 1A is not corpus-tapered or merge-ready.
- Cycle 12's accepted corrections are published and locally validated. Account ran 245 unit tests; 17 PostgreSQL integration tests were skipped without Docker. The cumulative stack retains inherited CI failures outside #2840's changed paths.

## Assignment

Resume only #2840 with fresh corrected-state whole-Unit-1A reviews starting at Cycle 13.

- Review the complete corrected semantic unit, not merely the latest diff.
- Adjudicate every candidate. Integrate, validate, publish, and ledger every useful accepted correction before starting the next cycle.
- Require two consecutive corrected-state zero-useful cycles for taper. Any useful accepted finding resets the dry streak. Two complementary reviewers may run in parallel on the same stable head and count as one cycle; do not start the next cycle until accepted fixes from the previous one are integrated, validated, published, and recorded.
- Report progress after each four further cycles, but continue without waiting for another instruction. A numerical checkpoint is not a stopping point or merge-readiness claim.
- When Unit 1A tapers, finish its validation, PR body, and ledger. Then continue through the prepared Access sequence in order: **1B** admission/session continuity/reconnect (16 starting sources), **1C** commands/output/frontend presentation (14), then **1D** social/communication/moderation-facing experience (16). Refresh each manifest and material handoffs against the latest cumulative tree before reviewing. The live corpus plan and alignment index own the source lists and unit status.
- Give each unit its own whole-boundary review and corrected-state taper evidence. Publish coherent developable changes as stacked draft PRs on the exact settled parent; do not force one PR per unit if adjacent corrections form a small coherent patch, and do not combine distinct concerns merely to reduce PR count. Record unit evidence separately even when packaging combines it. Preserve ancestry and unique patch/no-loss proof as the stack grows.
- Move directly to the next unit when the current one tapers and its accepted fixes, focused validation, body, and ledger are complete. Do not wait for Gameplay's CodeRabbit or merge train before continuing independent corpus work. After Unit 1D reaches its reviewed stopping point, report and pause before Parcel 2.

## Boundaries

- Keep #2839 and all earlier train PRs untouched.
- Do not use CodeRabbit or Hosted capacity.
- Do not merge or enable auto-merge.
- Do not begin a successor unit while its predecessor remains unfinished. Warn at 90 unique files and stop before publishing a PR above 100; propose a coherent split if needed. File counts are packaging limits, not grounds to discard a valid finding.
- Keep Docker/Testcontainers and live-deployment proof limits explicit.
- Do not implement proposal-dependent ADR 0183 behavior.
- Do not silently decide the parked Social cross-tenant duplicate-friendship policy in Unit 1D. Route an unresolved consequential choice while continuing independent review and fixes; stop only if the choice is necessary to complete the active correction.
- Stop only for an actual consequential design ambiguity, unsafe file-ceiling/topology issue, exhausted review capacity, or an explicit human pause. Keep inherited CI failures visible without treating them as a reason to abandon independent corpus review.

## Handoff

At each progress checkpoint, report the exact head/base and unique file count, new cycle raw/useful results, accepted-finding significance, executed and skipped validation, dry streak, and next action. State plainly whether the active unit tapered; do not call a PR merge-ready solely from corpus taper.
