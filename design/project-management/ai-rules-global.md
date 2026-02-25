# FireMUD AI Rules (Global)

These rules apply across the repository.

## Working Tree Safety

- Expect dirty worktrees with unrelated edits from parallel AI or human sessions.
- Continue working in scope; do not stop only because unrelated files are modified.
- Never revert, clean up, or reformat unrelated files.
- If a target file is already modified, edit in place and preserve in-progress changes.
- If overlapping edits make intent unclear or risky, ask a human before proceeding.

## Validation Before Hand-off

- For code changes, run `./gradlew check`.
- For markdown or design-document changes (especially under `design/`), run `./gradlew linkCheck lintMarkdown`.
- Treat these Gradle tasks as the source of truth for repository quality gates.

## Documentation Behavior

- In design and architecture docs, describe target-state behavior directly in the main flow.
- If implementation is partial, keep that status in a dedicated section near the top (for example `Implementation Notes`).
- Keep status notes current as behavior changes.

## Tooling and PR Metadata

- `gh` CLI and `python3` are available and may be used when requested.
- Do not create, merge, or close PRs unless explicitly asked.
- If a feature branch has an open PR and the task updates behavior, keep the PR summary accurate.
- If `pr-summary.md` exists and the user asks to refresh the PR description, prefer `gh pr edit --body-file pr-summary.md`.
- When writing PR bodies, pass Markdown through a file or stdin with real newlines, not literal `\\n` strings.
