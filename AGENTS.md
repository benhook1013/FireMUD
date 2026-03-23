# FireMUD AI Contributor Notes

Use this file as the canonical AI instruction source for this repository.

## Core Workflow

- Use Gradle task paths without a `services:` prefix (for example `./gradlew :tcp-proxy-service:test`, not `./gradlew :services:tcp-proxy-service:test`).
- For heavier local Gradle tasks, run from WSL in this repo path (for example `/mnt/c/.../FireMUD`) to avoid Windows file-locking issues.
- Do not manually hard-wrap lines in docs; let lines flow naturally.
- For larger tasks that span multiple domain areas, prefer using subagents when the work can be split cleanly. This helps avoid dragging unnecessary context through the main thread, reduces token cost, and can safely parallelize independent work.
- For code changes, run `./gradlew spotlessApply` before commit so formatting is normalized locally rather than relying on `check` or CI to catch drift.
- For code changes, run `./gradlew check` before hand-off.
- After `spotlessApply`, run the relevant `spotlessCheck` or `spotlessJavaCheck` task for touched services when formatting-sensitive files changed.
- For markdown or design-document changes (especially under `design/`), run `./gradlew linkCheck lintMarkdown` before hand-off.
- Treat `./gradlew linkCheck lintMarkdown` as mandatory hygiene when editing files: if these checks fail, fix the reported issues before hand-off even when the failures were pre-existing and not introduced by your change.

## Working Tree Safety

- Expect dirty worktrees with unrelated edits from parallel AI or human sessions.
- Continue working in scope; do not stop only because unrelated files are modified.
- Do not run workspace cleanup commands that modify the Git working tree or index (for example `git restore`, `git checkout`, `git reset`, `git clean`, `git stash`) unless a human explicitly asks for that exact action.
- Never revert, clean up, or reformat unrelated files.
- If a target file is already modified, edit in place and preserve in-progress changes.
- If overlapping edits make intent unclear or risky, ask a human before proceeding.

## Initial Development Mode (Authoritative)

FireMUD is in initial development. Optimize for direct convergence to a clean canonical state, not backward compatibility.

- Treat old schemas, contracts, and legacy routes as replaceable.
- Prefer direct replacement over phased migration.
- Breaking changes are allowed across DB schema, Redis keys, protocol fields, route shapes, and internal APIs.
- Do not add migration scaffolding unless explicitly requested: no dual-read or dual-write paths, no deprecation windows, no compatibility matrices, and no temporary rollout-only flags.
- When a contract changes, update all call sites, tests, and docs in the same change and remove obsolete paths.

## Documentation Behavior

- In design and architecture docs, describe target-state behavior directly in the main flow.
- If implementation is partial, keep that status in a dedicated section near the top (for example `Implementation Notes`) and keep it current.
- Document one canonical current behavior and remove obsolete legacy or transitional guidance.
- Do not add phased rollout or compatibility narratives unless explicitly requested.

## Architecture and Operations Assumptions

- Assume one prod-like topology across dev, hobby self-hosted, and production.
- Optimize for a single-admin operator model.
- Prefer simple automation and one-shot tools over multi-step manual runbooks.
- Add new operator-facing controls only when necessary and not derivable from existing system state.

## Runtime and API Invariants

- gRPC application-level failures must be returned as `ErrorDetail` in normal responses, not thrown as transport errors.
- For gRPC application errors, log warnings, increment `grpc.app_error` tagged with error code, and tag spans.
- Use `onError()` only for transport or infrastructure failures.
- When invoking Gradle repeatedly from AI workflows, prefer the default daemon behavior; only use `--no-daemon` for daemon debugging.

## Tooling and PR Metadata

- `gh` CLI and `python3` are available and may be used when requested.
- Do not create, merge, or close PRs unless explicitly asked.
- If a feature branch has an open PR and the task updates behavior, keep the PR summary accurate.
- If `pr-summary.md` exists and the user asks to refresh the PR description, prefer `gh pr edit --body-file pr-summary.md`.
- When writing PR bodies, pass Markdown through a file or stdin with real newlines, not literal `\\n` strings.

## Hetzner Preview Host

- Preview host: `77.42.29.156`; normal SSH user: `firemud`; current self-hosted runner label: `preview`. Treat it as preview infrastructure first, and check live host/runner state before using it for anything heavier.
