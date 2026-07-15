# FireMUD AI Contributor Notes

Use this file as the entrypoint for AI work in this repository. Treat repo docs and scripts as the system of record; use this file to find the right source and the non-negotiable workflow rules.

## Start Here

- Read [design/architecture/repository-structure.md](design/architecture/repository-structure.md) when you need the repo map or are not sure where a concern lives.
- Read [design/architecture/system-architecture-overview.md](design/architecture/system-architecture-overview.md) for cross-service contracts, shared runtime behavior, or architecture changes.
- Read [design/architecture/infrastructure/README.md](design/architecture/infrastructure/README.md) for deployment, gateway, protocol, environment, or preview/hosted concerns.
- For service-scoped work, prefer the matching docs under `design/architecture/microservices/<service>/` before inferring from code in other areas.

## Core Workflow

- Use Gradle task paths without a `services:` prefix. Example: `./gradlew :tcp-proxy-service:test`, not `./gradlew :services:tcp-proxy-service:test`.
- For heavier local Gradle tasks, run from WSL in this repo path to avoid Windows file-locking issues.
- Do not manually hard-wrap lines in docs; let lines flow naturally.
- Do not create, merge, or close PRs unless explicitly asked.
- Repository-local CodeRabbit reviews automatically, then pauses after one reviewed commit. Request another review explicitly with `@coderabbitai review` only after all current and outdated findings are resolved and the PR is at a meaningful checkpoint.
- For PR status checks, treat unresolved non-outdated review threads as the primary truth for review completeness. Do not treat a green top-level CodeRabbit status, passing GitHub checks, or mergeability alone as meaning the PR is review-complete.
- When asked to "check PR", "check review", or determine whether a PR is done, inspect unresolved review threads first, then check GitHub Actions status and mergeability second.
- For recurring local branch/worktree/PR inventory, use `dev-tools/validation/report-worktree-pr-topology.sh` instead of composing ad hoc status commands.
- When reporting PR or review status, always report both unresolved non-outdated threads and unresolved outdated threads. Treat non-outdated threads as the actionable truth, but call out outdated unresolved threads separately because they can still appear in GitHub or CodeRabbit UI and confuse merge status.
- Before calling a PR review-complete or merge-ready, run `python3 dev-tools/validation/check-coderabbit-review.py --repo <owner/repo> --pr <number>` and use its live unresolved-thread counts plus explicit-review verdict as the source of truth.
- When explicitly authorized to merge, merge a PR only after the CodeRabbit validation script confirms zero unresolved current or outdated threads and required CI is green. A substantive CodeRabbit review must cover the feature-bearing commits; direct fixes to its existing findings may merge without rereview when they add no functionality or independently reviewable behavior. Request a fresh review when a follow-up introduces new functionality or materially broadens behavioral risk.
- Resolve a CodeRabbit thread manually only after verifying its exact finding is fixed in `HEAD`. Never resolve a thread to hide an unresolved issue, and do not spend a fresh review solely to make CodeRabbit self-resolve already-addressed feedback. Do not request a review while one is active or CodeRabbit is rate limited.
- Keep open PRs meaningful: after that authorized merge threshold, merge rather than leaving completed PRs parked.
- Prefer one coherent medium-sized slice per PR, normally about 800-2,000 changed lines including required adjacent convergence; use smaller PRs only for isolated fixes and split only independent or genuinely hard-to-review work.
- Treat a failing Renovate PR as actionable maintenance work. Inspect its CI failure and push the smallest compatible fix to the Renovate branch when possible so Renovate can rebase and complete its own update; otherwise perform the dependency upgrade directly on a replacement branch with the required compatibility changes.
- After a PR merges, proactively remove its now-defunct local worktree and merged local/remote branch once no open PR or active stacked branch depends on it. Preserve unmerged branches and their worktrees, and verify dependency/merge state before cleanup rather than deleting by name alone.
- If `pr-summary.md` exists and the user asks to refresh the PR description, prefer `gh pr edit --body-file pr-summary.md`.
- When writing PR bodies, pass Markdown through a file or stdin with real newlines, not literal `\\n` strings.

## Required Validation

- If you change code, run `./gradlew spotlessApply` before hand-off.
- If you change code, run `./gradlew check` before hand-off.
- Before marking a slice or seam complete, verify the exact claimed boundary across its public contract, implementation, and focused proof instead of assuming adjacent green work is enough.
- If formatting-sensitive files changed after `spotlessApply`, run the relevant `spotlessCheck` or `spotlessJavaCheck` task for the touched services.
- If you change service code, prefer the same validation path CI uses for that service: `./gradlew :<service>:check -PfullCheck`.
- For heavy local service verification, prefer `dev-tools/validation/run-locked-gradle.sh` over raw `./gradlew` so overlapping local runs do not corrupt shared `build/test-results` state.
- If you edit Markdown or design docs, run `./gradlew linkCheck lintMarkdown` before hand-off.
- Treat failing Markdown hygiene checks as work to fix before hand-off even when the failures were pre-existing.
- If CI has already exposed multiple failures in the same area, stop relying on incremental remote feedback and rerun the fuller local proof for that area before pushing.
- If a heavy Gradle verification run goes quiet after test execution, use `dev-tools/validation/inspect-test-results.sh <service>` to inspect fresh parsed XML before assuming a new product regression; treat it as diagnostics only, not proof of clean task completion.
- If multiple branch lanes or broad migration/build changes have been in flight, do not trust service-local green proof alone; rerun repo-wide validation before hand-off.

## Smoke And Runtime Proof

- Prefer the canonical smoke scripts under `dev-tools/` over ad hoc `docker compose` loops.
- For smoke-specific fixes, run the canonical proof for the changed path:
  - Source-built stack: `dev-tools/verify-fresh-bootstrap.sh` or `dev-tools/verify-restart-state.sh`
  - Image-tag smoke: `SMOKE_IMAGE_TAG=<tag> dev-tools/verify-smoke-images.sh`
- If a change affects runtime behavior, startup, auth, wiring, migrations, or packaged artifacts, make sure the proof rebuilds and boots fresh images rather than reusing stale containers or images.
- If multiple CI failures suggest the branch is no longer locally mirrored, run `./gradlew check` plus the appropriate canonical Docker smoke proof before pushing.

## Subagent Models

The main `gpt-5.6-sol` thread is the orchestrator. It owns planning, design discussion with the human, task decomposition, integration, validation, and final repository decisions. Use subagents heavily for bounded bulk work so Sol context and usage stay focused on orchestration.

- `gpt-5.6-luna`: Default for bulk repository reading, exhaustive inventories, mechanical or well-specified edits, focused investigation, and targeted test work. Sol must define the boundary and review the result. Do not use Luna as independent final-review evidence.
- `gpt-5.6-terra`: Use selectively for difficult bounded work or independent verification where Luna is not reliable enough. Do not delegate product or architecture design decisions.
- Do not delegate design decisions. Main thread owns design reasoning with the human, slice boundaries, integration, validation, and PR decisions.
- Write every human-dispatched Codex Spark handover prompt or review brief to `C:\\temp\\firemud-spark-reviews` (`/mnt/c/temp/firemud-spark-reviews` from WSL) so it is ready to pick up without chat copy/paste. Spark responses are normally appended to the same prompt file, so inspect that file for the complete handover record.

## Execution Style

- Prefer a single main-thread workflow on the active branch and in the current working tree unless a human explicitly asks otherwise.
- Keep orchestration, integration, end-to-end reasoning, and final verification on the main thread.
- Delegate bounded bulk work proactively when it preserves Sol context or materially reduces orchestration cost. Prefer `gpt-5.6-luna`; reserve `gpt-5.6-terra` for tasks that need stronger independent reasoning.
- Give each subagent a disjoint write scope, explicit success conditions, and enough canonical context to execute without making design decisions.
- Optimize for direct convergence and avoid unnecessary splitting across delegated workers.
- For slice or branch reconciliation, read the owning vertical-slice doc first and use its declared scope, proof, and status before interpreting branch names, commit messages, or raw diffs.
- Be proactive within scope. If the task exposes nearby drift or related breakage in the same area, fix it in the same pass when practical.
- When rolling out or repairing a shared pattern, update the remaining in-scope adopters in the same pass when practical.

## Working Tree Safety

- Expect dirty worktrees with unrelated edits from parallel AI or human sessions.
- Continue working in scope; do not stop only because unrelated files are modified.
- Do not run workspace cleanup commands that modify the Git working tree or index, such as `git restore`, `git checkout`, `git reset`, `git clean`, or `git stash`, unless a human explicitly asks for that exact action.
- Do not delete untracked files just because they look temporary or fail a linter. If an untracked file is not unquestionably disposable and in scope, leave it alone.
- Never revert, clean up, or reformat unrelated files.
- If a target file is already modified, edit in place and preserve in-progress changes.
- If overlapping edits make intent unclear or risky, ask a human before proceeding.

## Development Mode

FireMUD is in initial development. Optimize for direct convergence to a clean canonical state, not backward compatibility.

- Treat old schemas, contracts, and legacy routes as replaceable.
- Prefer direct replacement over phased migration.
- Breaking changes are allowed across DB schema, Redis keys, protocol fields, route shapes, and internal APIs.
- Do not add migration scaffolding unless explicitly requested.
- When a contract changes, update all call sites, tests, and docs in the same change and remove obsolete paths.
- Because the product is still pre-v1, AI contributors have standing permission to widen a slice, create a new concrete slice, or complete a broader convergence pass when that is the cheaper or more correct path to the designed target state.
- Do not artificially keep work narrow when the live invariant clearly spans multiple services or seams and a wider in-scope pass will reduce churn, repeated review cost, or follow-up rework.
- Do not stop to ask for special approval merely because a change becomes cross-service, breaking, or larger than an initially narrow seam; only stop when design intent is genuinely unclear or competing target states exist.
- Default to the cheapest credible path to canonical convergence, even when that means folding adjacent slice work together, replacing a larger surface directly, or retiring obsolete code in the same pass.

## Documentation Rules

- In design and architecture docs, describe target-state behavior directly in the main flow.
- If implementation is partial, keep that status in a dedicated section near the top, such as `Implementation Notes`, and keep it current.
- Document one canonical current behavior and remove obsolete transitional guidance unless a human explicitly asks for rollout history.
- When a slice narrows or replaces a shared abstraction, update the surviving high-level docs and nearby helper/module comments in the same pass so the repo keeps teaching one boundary.
- Do not use emojis in Markdown headings.
- When linking to repo files in Markdown, prefer plain file links without `:line` suffixes so links stay GitHub-compatible.

## Runtime And Architecture Invariants

- Assume one prod-like topology across dev, hobby self-hosted, and production.
- Optimize for a single-admin operator model.
- Prefer simple automation and one-shot tools over multi-step manual runbooks.
- gRPC application-level failures must be returned as `ErrorDetail` in normal responses, not thrown as transport errors.
- For gRPC application errors, log warnings, increment `grpc.app_error` tagged with error code, and tag spans.
- Use `onError()` only for transport or infrastructure failures.

## Tools And Environment

- `gh` CLI and `python3` are available and may be used when requested.
- Prefer standard CLI tools directly for routine archive, JSON, YAML, SQL, and text inspection.
- If a common utility is missing and installing it is straightforward, prefer installing it once rather than repeatedly working around it.
- If a missing tool would require a nontrivial or risky system change, ask before installing it.
- When running Docker from WSL, use a native Linux Docker CLI against `unix:///var/run/docker.sock`; Windows `docker.exe` wrappers can appear connected while breaking bind mounts that the canonical source-built smoke proofs rely on.
- When invoking Gradle repeatedly from AI workflows, prefer the default daemon behavior; only use `--no-daemon` for daemon debugging.
- Preview host: `77.42.29.156`; SSH user: `firemud`; current self-hosted runner label: `preview`. Treat it as preview infrastructure first, and check live host or runner state before heavier use.

## AI Observation Log

- Record reusable process, tooling, environment, or design lessons in [design/project-management/ai-observations.md](design/project-management/ai-observations.md).
- Do not log ordinary one-off bugs fixed as part of the current task unless the underlying lesson still matters after the fix.
- Treat that file as append-only during normal work: add dated entries and do not rewrite older ones unless a human explicitly asks for cleanup.
- Do not proactively review or curate that file as routine maintenance; only do an observation cleanup/review pass when a human explicitly asks. If it grows noisy enough to merit a pass, note that and ask whether to schedule one.
