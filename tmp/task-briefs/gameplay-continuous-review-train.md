# Gameplay task brief: continuous merge-train review

Status: active and fully realigned on 2026-09-23

Owner: Gameplay

This rewritten brief supersedes the earlier #2838-only contents and every earlier Gameplay chat instruction about stopping after #2838 or advancing directly to #2818. It preserves the current #2838 obligation, adds the intervening review-control prerequisite, and authorizes continuous review through #2827. Do not reconstruct policy from chat history.

## Authorized queue

Work through this single queue in order:

1. **#2838 — review-duration and Codecov tooling.** Current published head `0ee196c2aab41449460a47dc14aa2b371e38a138`, targeting `develop`, 14 owned files at the preceding checkpoint. Hosted completed `0/0` on reviewed head `87194112fa18c731ed40ba6f38e43fcb0578b48d`; CLI completed `0/0 → 1/1 → 1/1 → 0/0 → 0/0 → 0/0`. The current head adds only the normal `develop` reconciliation through #2835 and two Renovate merges; prove the owned patch unchanged, finish exact-head CI and metadata, and make the merge-readiness handoff.
2. **Review-control refactor — PR number pending.** General owns its implementation as a child of #2838. Do not begin review until Overseer appends the exact PR/head and confirms completion of the required independent hands-on acceptance. Do not skip this slot.
3. **#2818 — publication workload identity and production activation.** Current published head `24c258ddb1db5a671d008552e8c5bee4bbfdc4ac`, currently based on #2838. Before counted review it must be reconciled normally onto the accepted review-control prerequisite and retain its owned patch.
4. **#2826 — 5B Schema.** Current published head `b0632a4c8f82c54931f3e2229cba5779258b797d`, currently based on #2818. Preserve the reviewed Schema boundary and reconcile only after its exact parent is stable.
5. **#2827 — V3 plugin lifecycle/fences.** Current published head `443759f5304d651842ee9bfeb722dc9d4370c841`, currently based on #2826.

Stop before #2828. Do not touch #2828, #2829, #2839, #2840, or later Document work under this assignment.

If the review-control prerequisite is not ready when #2838 finishes, wait at that queue slot and report the dependency. Do not bypass it to spend review quota on #2818.

## Preparation before each PR

1. Verify the live PR head, base, parent head, mergeability, worktree, unique file count, body, LOC metadata, unresolved current threads, outdated unresolved threads, and latest review evidence.
2. Derive the effective parent as the nearest preceding unmerged queue PR, or current `develop` after all predecessors have merged.
3. Require the PR base to name that parent, the base tip to equal the parent's current published head, and that exact parent head to be an ancestor of the child.
4. Audit the complete adjacent ancestry through the active target. If any parent moved, stop counted review until normal reconciliation is published and its owned patch identity is checked.
5. Use a normal merge to reconcile parent movement; do not rebase or rewrite reviewed history. Resolve overlaps in favor of each owning PR's canonical behavior and prove the unique child patch afterward.
6. Warn at 90 unique files and stop before publishing above 100. Do not invent a split without Overseer direction.
7. Do not publish commits while a Hosted review is active. Prepare accepted fixes locally, then publish one coherent batch after the review is terminal.

## Hosted policy

- Hosted belongs to the earliest queue PR whose Hosted policy is incomplete. Only one Hosted review may be active.
- Request a complete full-PR review through the canonical tooling. A rate limit, stale request, provider error, partial output, or missing terminal evidence does not count and never advances the target.
- Record raw found/accepted counts separately from current and outdated unresolved-thread counts. `N/0` is zero-useful even when raw findings are nonzero.
- Adjudicate every attributable finding. Delegate bounded implementation and focused proof, inspect the diff, validate, publish, and resolve threads before the next full review.
- Any accepted useful finding resets Hosted taper. Matching the canonical repository workflow, normal Hosted taper requires **two consecutive completed full corrected-state zero-useful rounds after the latest accepted useful fix**. `N/0` counts as zero-useful; rate limits, provider failures, partial output, and interrupted reviews do not count.
- Overseer may explicitly close a PR after one dry Hosted round only through a recorded head- and patch-bound judgment for a small, narrowly proven boundary or direct fix. Gameplay must not infer that exception. Critical/security findings, broad corrections, or material boundary changes remain on the normal two-dry rule unless Overseer gives a more conservative instruction.
- A direct formatting, metadata, or narrowly proven review fix may retain preceding evidence only through an explicit recorded judgment. Never describe zero unresolved threads as raw `0/0`.
- Once Hosted tapers on the active PR, move Hosted to the next eligible queue PR without waiting for CLI, CI, or merge, provided the next PR's entire ancestry is current and no unpublished parent batch exists.

## CLI policy

- Run complete full-candidate CLI reviews serially. Do not run concurrent CLI reviews against the same candidate.
- CLI tapers after **three consecutive zero-useful completed rounds** on the same corrected owned patch. Any accepted useful finding resets the streak to zero. Rejected or duplicate findings count as `N/0` and do not reset it.
- A quota failure, setup failure, interrupted run, discarded partial output, or provisional unreconciled pass does not count.
- After CLI tapers on one PR, it may advance independently through every consecutive stable, review-eligible queue PR. There is no one-PR lead limit.
- Before advancing CLI, verify the complete current ancestry and ensure no ancestor has unpublished accepted fixes or active work likely to move its head. If an ancestor later changes, mark affected downstream evidence stale or judgment-required and reconcile before further counted rounds.
- Do not restart already tapered CLI solely because a parent-only reconciliation preserved the owned patch. Record a patch-identity judgment. Reopen CLI when Hosted or reconciliation materially changes the boundary CLI assessed.

## Cross-channel and correction rules

- Hosted and CLI are independent cursors over the same queue. Neither substitutes for the other.
- A fix discovered by either channel must be considered against evidence from the other. Material behavior or proof changes reopen the affected channel; direct narrow corrections may retain evidence only with a written head/patch-bound rationale.
- Never move a cursor because a worker has been idle, a numerical allowance elapsed, or a provider cooldown is inconvenient.
- Numerical allowances are report boundaries, not automatic completion. Continue productive review after reporting unless Overseer explicitly stops it.
- Use wake-capable sentinels for provider cooldowns and CI. The parent task waits on them rather than polling or narrating unchanged state.

## Per-PR readiness and continuation

A PR may be handed to Overseer as merge-ready only when:

- all accepted findings are fixed and published;
- Hosted and CLI satisfy the policies above or an explicit recorded Overseer judgment says otherwise;
- zero current and zero outdated unresolved threads remain;
- required CI is green on the exact owned patch, with any merge-only reconciliation evidence explained;
- unique patch, base/head, body, LOC metadata, and validation limits are current;
- GitHub reports a coherent mergeable state, or any mechanical policy status is precisely explained;
- no real design, runtime-proof, security, topology, or file-ceiling blocker remains hidden.

Gameplay never merges or enables auto-merge. Give a concise readiness handoff for each completed PR, then continue preparing the next eligible queue item without waiting for the human merge click. When the human merges a predecessor, reconcile the current child at the next safe review boundary, retarget it to the new effective parent, prove the owned patch, and continue. Do not invalidate an active Hosted review to perform that reconciliation.

Report a genuine blocker immediately instead of silently advancing around it. The user may poll periodically; answer with the current PR, exact head, Hosted sequence, CLI sequence, useful-finding significance, taper state, CI, ancestry, and next action.

## Delegation and evidence

Use bounded Luna lanes for separable implementation, mechanical proof, and CI/provider waits. Gameplay retains finding adjudication, diff inspection, integration, publication, review/merge-readiness judgment, and the final handoff. Do not consume overlapping agents for the same task.

Keep PR bodies and private ledgers current with detailed findings and proof. The task brief owns queue and process; do not duplicate every finding here.

## Final stopping point

Stop after #2827 has received its merge-readiness handoff, or earlier for a genuine design decision, file ceiling, unrecoverable provider/tool failure, or an unavailable required queue prerequisite. Do not begin #2828.
