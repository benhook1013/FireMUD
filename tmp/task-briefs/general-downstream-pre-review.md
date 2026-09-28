# General: downstream reconciliation and pre-review

## Standing mission

Start at PR #2879, then follow the live configured delivery queue through its remaining PRs. For each safe PR, do both normal parent reconciliation and an independent subagent pre-CodeRabbit review of its unique changes. Preserve the PR's unique patch and review identity while reconciling its parent; adjudicate and fix useful findings owned by that PR, then validate and publish at a safe checkpoint.

Repeat independent review and fix passes while they continue to find worthwhile, in-scope work. Advance to the next safe PR only after judging that another independent pass on the current PR is no longer useful, and record that judgment with concrete evidence. There is no required count of dry passes or numeric taper, and independent review earns no CodeRabbit taper credit. Do not stop the overall queue sweep at a named PR; if one PR is blocked, continue with the next safe PR.

## Working boundary

Use the live configured queue and each PR's owning architecture, tracker, and current published topology as authority. Keep volatile heads, review rounds, and progress in the controller, PR records, or concise handoffs rather than this brief. Coordinate with Gameplay around its active Hosted review and with Document around its owned implementation branches; do not edit their work. Do not publish a review-invalidating change while Hosted review is active; prepare it locally and publish at a safe checkpoint after that review completes.

Use bounded independent reviewers with disjoint scope and explicit success conditions. General owns finding adjudication, integration, focused proof, and safe publication. Fix valid findings in the owning PR; route other-owner findings with evidence and coordinate a safe handoff.

Independent pre-review is preparation only: do not request or run CodeRabbit under this brief, and do not claim CodeRabbit findings, taper credit, or merge readiness from independent passes. Do not merge or enable auto-merge. Follow [PR lifecycle](../../design/developer-workflows/pr-lifecycle.md), [validation and runtime proof](../../design/developer-workflows/validation-and-runtime-proof.md), and [AI delegation and review](../../design/developer-workflows/ai-delegation-and-review.md).

## Handoff

For each PR, report the exact PR/head and parent worked on, unique changes preserved, number and scope of independent passes, findings and disposition, fixes and focused proof, pass limits or blockers, and why further passes are or are not useful. Also report publication safety and the next safe queue boundary. Do not imply historical PR completion without evidence. Keep this brief stable; record current progress in the live status page, controller, PR records, or handoff.
