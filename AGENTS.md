# FireMUD AI Contributor Notes

- Use Gradle task paths without a `services:` prefix (for example `./gradlew :tcp-proxy-service:test`, not `./gradlew :services:tcp-proxy-service:test`).
- For heavier local Gradle tasks, run from WSL in this repo path (for example `/mnt/c/.../FireMUD`) to avoid Windows file-locking issues.
- Do not manually hard-wrap lines in docs; let lines flow naturally.
- Do not run workspace cleanup commands that modify the Git working tree or index (for example `git restore`, `git checkout`, `git reset`, `git clean`, `git stash`) unless a human explicitly asks for that exact action.
