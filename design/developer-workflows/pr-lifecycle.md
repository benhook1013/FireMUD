# Pull Request Lifecycle

Use this guide for pull-request status, review handling, CI, Renovate, merging, or branch/worktree cleanup.

## Review And Status

- CodeRabbit automatically performs the initial review when a qualifying PR opens; automatic incremental reviews are disabled. Request `@coderabbitai full review` for every subsequent review checkpoint only after all current and outdated findings are resolved and no review is active or rate-limited. If a full review is rejected solely because the PR exceeds the active plan's total-file ceiling and a trustworthy substantive checkpoint already exists, use one incremental `@coderabbitai review` and verify the selected commit and file range in its summary.
- CodeRabbit CLI reviews use a separate local-review quota. Before launching one, fetch the PR's remote base and pass its remote-tracking ref to `--base`; never use an unchecked local base branch, and verify the resulting file count against the live PR. When quota is available, prefer independent full PR-diff passes with `coderabbit review --agent --committed --base <remote>/<base-ref>`; if the CLI enforces a size limit, partition the complete PR diff exhaustively rather than reviewing only convenient files. CLI findings are additional review evidence and do not replace GitHub review-thread, summary, or merge-gate checks. Do not install or invoke autonomous CodeRabbit skills that can consume review quota outside this deliberate workflow.
- Do not start a review against a base that is about to change. Once a hosted or CLI review has started, let it finish and consume any still-valid findings; cancelling it does not recover quota or evidence.
- Use hosted and CLI review quota deliberately: run an initial substantive review and checkpoint reviews after meaningful changes, then stop repeating passes when new findings are no longer materially useful. Do not cycle reviews indefinitely merely because quota is currently available.
- For "check PR", "check review", or merge-readiness, inspect unresolved non-outdated review threads and the latest completed CodeRabbit review summary before CI and mergeability. Verify summary-only duplicate and outside-diff findings even when they have no unresolved thread. Report current and outdated unresolved counts separately; current threads remain authoritative for inline conversation state, not the complete finding inventory.
- Before calling a PR review-complete or merge-ready, run `python3 dev-tools/validation/check-coderabbit-review.py --repo <owner/repo> --pr <number>` and use its live thread counts and explicit-review verdict.
- Resolve a CodeRabbit thread only after verifying its exact finding is fixed in `HEAD`. Do not resolve threads to hide open work, request a review while one is active or rate-limited, or request a review solely to self-resolve already-fixed feedback.

## Change And Merge Policy

- Keep PRs coherent, normally one medium-sized slice of roughly 800-2,000 changed lines including adjacent convergence. Use smaller PRs for isolated fixes; split only independent or genuinely hard-to-review work.
- Before requesting CodeRabbit, keep the reviewer-selected file count comfortably below the active plan ceiling rather than targeting the exact limit. Review findings define correctness work, not optional scope: implement every valid finding before deciding how to manage the reviewer ceiling. The ceiling may change branch topology or review boundaries, but never which live issues are fixed.
- Do not open a tiny extraction PR merely to cross a reviewer ceiling. For broad alignment or other file-heavy work, prefer natural 50-70-file review boundaries and treat 80-90 files as the maximum after splitting under a 100-file ceiling, leaving substantial capacity for review fixes. When two coherent domains can be separated, a balanced split is preferable to preserving one majority PR; if no natural split exists, keep the work together and use the available CLI or scoped-review path.
- A substantive CodeRabbit review must cover feature-bearing commits. Direct fixes to its findings may merge without rereview only when they add no functionality or independently reviewable behavior. Request fresh review after new functionality or materially broader risk.
- Merge only when explicitly authorized, the CodeRabbit check reports zero unresolved current and outdated threads, and required CI is green. Merge completed PRs rather than parking them after that threshold.
- This repository currently enables merge commits only. Use `gh pr merge --auto --merge` when authorized; do not probe disabled squash or rebase methods unless repository policy changes.
- Treat failing Renovate PRs as maintenance work: inspect CI and push the smallest compatible fix to its branch when possible; otherwise use a replacement branch with required compatibility changes.

## Branch And PR Hygiene

- Open or update a PR autonomously when an implementation branch reaches a coherent review checkpoint. Stacked PRs are acceptable when their base dependencies are explicit and they let independent work continue while CI or review runs; do not create tiny placeholder PRs without review-worthy content.
- When retargeting a stacked PR after its parent merges, change the base before rebasing or pushing the child. Required workflows listen for GitHub's `edited` event but run replacement gates only when `changes.base.ref` is present; title/body edits use a separate metadata-only concurrency group and neither cancel an active required gate run nor start replacement jobs.
- For recurring branch, worktree, and PR inventory, use `dev-tools/validation/report-worktree-pr-topology.sh` rather than ad hoc status commands.
- After a merge, remove its defunct local worktree and merged local/remote branch only after confirming no open PR or active stacked branch depends on it. Preserve unmerged branches and worktrees.
- Pass PR-body Markdown through a file or stdin with real newlines rather than literal `\\n` strings.
