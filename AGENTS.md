# FireMUD AI Contribution Guide

Please read the following documents before using AI tooling or submitting code changes:

- [Global AI Rules](design/project-management/ai-rules-global.md)
- [Local AI Rules](design/project-management/ai-rules-local.md)

Gradle project paths do **not** include the `services:` prefix even though the modules live under the `services/` directory. Use commands like `./gradlew :tcp-proxy-service:test` instead of `./gradlew :services:tcp-proxy-service:test` when running or referencing tasks.

When running heavier Gradle tasks/tests locally, prefer executing them inside WSL to avoid Windows file-locking issues. Run `./gradlew <task>` from a WSL shell in this repository directory (for example `/mnt/c/.../FireMUD`).

Before editing, run `dev-tools/print-lines-with-numbers.ps1 <file>` (optionally with `-StartLine`, `-Count`, and `-EndLine`) to view numbered ranges and pinpoint the slice you need without extra tooling.

Do not manually wrap lines. Let lines flow naturally so content displays cleanly on GitHub and in
editors.

## Hard Rule: No Workspace Cleanup

Do not run any command that modifies the Git working tree or index (for example `git restore`, `git checkout`, `git reset`, `git clean`, or `git stash`) unless a human explicitly asks for that exact action. If unrelated diffs appear, ask what to do and proceed without reverting anything.

These files contain the full coding conventions and workflow requirements. The
`.windsurfrules` file in this repository simply points to `ai-rules-local.md`
for compatibility with Windsurf.
