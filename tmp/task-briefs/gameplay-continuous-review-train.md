# Gameplay task brief: continuous merge train

Owner: Gameplay. This is an ongoing assignment, including overnight. At each resumption, re-read the active FireMUD repository's canonical [PR lifecycle](https://github.com/benhook1013/FireMUD/blob/develop/design/developer-workflows/pr-lifecycle.md) and [AI delegation and review](https://github.com/benhook1013/FireMUD/blob/develop/design/developer-workflows/ai-delegation-and-review.md), then inspect the live controller queue. Until #2887 merges its workflow/controller changes, use the version-matched active FireMUD worktree's PR lifecycle and controller operations guide; afterward use the linked `develop` copies. The [dated archive](./gameplay-continuous-review-train-history-2026-09-27.md) preserves old handoffs, not current instructions.

## Standing assignment

**Keep the configured PR train moving.** Run Hosted and CLI independently, fix findings, reconcile and verify PRs, and hand ready ones to the human for merging. If one PR or channel is blocked, work on the next safe review or fix up the chain. Stop only when no safe useful work remains or the human explicitly pauses the lane; report the concrete blocker and what can continue elsewhere.

Keep at most one active request per channel. Prepare fixes locally while Hosted runs and publish only after it finishes. Follow the controller for request safety, but do not let head, parent, or patch changes reset PR-level taper or a human stop: one zero-useful Hosted result or three consecutive zero-useful CLI results complete that PR's channel; an accepted in-PR finding breaks its streak, while a routed finding does not. Human min/max extra-review decisions are per PR and channel. If the selector contradicts this policy, correct the tool and continue safe work elsewhere.

Gameplay has the project owner's standing authorization to make coherent merge-train splits under [PR lifecycle](https://github.com/benhook1013/FireMUD/blob/develop/design/developer-workflows/pr-lifecycle.md#change-and-merge-policy), without asking again for a routine split. Preserve review evidence, update and read back the configured queue, and continue safe work while preparing the split. Gameplay reports merge readiness; the human controls merging.

## Current bounded work

Finish the review-controller work already underway: keep `found / accepted / routed` and source-neutral routing queryable, import completed provider and manual/subagent findings into the canonical SQLite record store, and close any remaining head-dependent taper or stop behavior with focused proof. Use the active FireMUD repository's [controller operations guide](https://github.com/benhook1013/FireMUD/blob/develop/dev-tools/pr_review/README.md) for the detailed schema and commands. The live JSON-to-SQLite cutover waits for merged, version-matched tooling; then prove migration, old-writer refusal, private Hetzner snapshot backup, remote restore, and readback. Keep normal review commands free of remote backup I/O. Do not call the migration complete from local code or CI alone. Overseer owns the separate status-site presentation.

Once that bounded controller work is proved and handed off, remove this section; the standing assignment continues through the configured queue.
