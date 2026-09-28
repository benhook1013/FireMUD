# Gameplay task brief: continuous merge train

Owner: Gameplay. This is an ongoing assignment, including overnight. At each resumption, re-read the active FireMUD repository's canonical [PR lifecycle](https://github.com/benhook1013/FireMUD/blob/develop/design/developer-workflows/pr-lifecycle.md) and [AI delegation and review](https://github.com/benhook1013/FireMUD/blob/develop/design/developer-workflows/ai-delegation-and-review.md), then inspect the live controller queue. Use the version-matched controller entrypoint identified below until its correction merges. The [dated archive](./gameplay-continuous-review-train-history-2026-09-27.md) preserves old handoffs, not current instructions.

## Standing assignment

**Keep the configured PR train moving.** Run Hosted and CLI independently, fix findings, reconcile and verify PRs, and hand ready ones to the human for merging. If one PR or channel is blocked, work on the next safe review or fix up the chain. Stop only when no safe useful work remains or the human explicitly pauses the lane; report the concrete blocker and what can continue elsewhere.

Keep at most one active request per channel. Prepare fixes locally while Hosted runs and publish only after it finishes. Follow the controller for request safety, but do not let head, parent, or patch changes reset PR-level taper or a human stop: one zero-useful Hosted result or three consecutive zero-useful CLI results complete that PR's channel; an accepted in-PR finding breaks its streak, while a routed finding does not. Human min/max extra-review decisions are per PR and channel. If the selector contradicts this policy, correct the tool and continue safe work elsewhere.

Gameplay has the project owner's standing authorization to make coherent merge-train splits under [PR lifecycle](https://github.com/benhook1013/FireMUD/blob/develop/design/developer-workflows/pr-lifecycle.md#change-and-merge-policy), without asking again for a routine split. Preserve review evidence, update and read back the configured queue, and continue safe work while preparing the split. Gameplay reports merge readiness; the human controls merging.

## Temporary controller handoff

The shared JSON-to-SQLite cutover and private backup/restore proof are complete; do not rerun migration. Until the correction in #2890 merges, run all queue status and review commands through `/home/ben/src/FireMUD-review-active/dev-tools/pr-review`, pinned at `4bd735b3c`. That version preserves historical Hosted attribution after CodeRabbit edits a completion comment. The status site uses the same entrypoint. #2890 remains Overseer-owned for additional fixes and must stay open until explicitly handed into the review train. Keep independent Hosted and CLI reviews moving on eligible configured PRs.
