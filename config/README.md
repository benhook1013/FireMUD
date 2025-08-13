# Configuration Files

Centralizes build and tooling configuration. Each subfolder (listed
alphabetically) contains its own settings and README describing how to use it.

- `checkstyle/` – Java style rules enforced during `./gradlew check`.
- `eslint/` – Shared ESLint configuration.
- `git-hooks/` – Custom Git hooks; install with
  `git config core.hooksPath config/git-hooks`.
- `hadolint/` – Dockerfile lint rules.
- `lychee/` – Ignore list for the `lychee` link checker.
- `markdownlint/` – Markdown linting configuration.
- `protobuf/` – Buf workspace and code generation configs.
- `redis/` – Sample `redis.conf` used by Docker Compose.
- `release/` – Release Please manifest and configuration.
- `security/` – Settings for container and dependency scanning tools.
- `spotbugs/` – Static analysis rules for SpotBugs.
- `ts/` – Base TypeScript configuration.

Use this directory for any tooling or service configuration so the repository
root stays clean and discoverable.
