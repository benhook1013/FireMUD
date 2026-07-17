# Pull Request Lifecycle

Use this guide for pull-request status, review handling, CI, Renovate, merging, or branch/worktree cleanup.

## Review And Status

- CodeRabbit automatically reviews repository PRs and pauses after one reviewed commit. Request `@coderabbitai full review` only after all current and outdated findings are resolved and the PR reaches a meaningful checkpoint.
- For "check PR", "check review", or merge-readiness, inspect unresolved non-outdated review threads first, then CI and mergeability. Report current and outdated unresolved counts separately; current threads are the actionable truth.
- Before calling a PR review-complete or merge-ready, run `python3 dev-tools/validation/check-coderabbit-review.py --repo <owner/repo> --pr <number>` and use its live thread counts and explicit-review verdict.
- Resolve a CodeRabbit thread only after verifying its exact finding is fixed in `HEAD`. Do not resolve threads to hide open work, request a review while one is active or rate-limited, or request a review solely to self-resolve already-fixed feedback.

## Change And Merge Policy

- Keep PRs coherent, normally one medium-sized slice of roughly 800-2,000 changed lines including adjacent convergence. Use smaller PRs for isolated fixes; split only independent or genuinely hard-to-review work.
- A substantive CodeRabbit review must cover feature-bearing commits. Direct fixes to its findings may merge without rereview only when they add no functionality or independently reviewable behavior. Request fresh review after new functionality or materially broader risk.
- Merge only when explicitly authorized, the CodeRabbit check reports zero unresolved current and outdated threads, and required CI is green. Merge completed PRs rather than parking them after that threshold.
- Treat failing Renovate PRs as maintenance work: inspect CI and push the smallest compatible fix to its branch when possible; otherwise use a replacement branch with required compatibility changes.

## Branch And PR Hygiene

- For recurring branch, worktree, and PR inventory, use `dev-tools/validation/report-worktree-pr-topology.sh` rather than ad hoc status commands.
- After a merge, remove its defunct local worktree and merged local/remote branch only after confirming no open PR or active stacked branch depends on it. Preserve unmerged branches and worktrees.
- Pass PR-body Markdown through a file or stdin with real newlines rather than literal `\\n` strings.
