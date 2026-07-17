# Developer Setup

This guide explains how to configure a local development environment for the FireMUD Game Platform.

## Prerequisites

Install the following tools before building the services:

- **Java 21+** – required for all Spring Boot microservices.
- **Node.js** (latest LTS) – needed if you plan to build the React frontend.
- **Docker** and **Docker Compose** – run the full stack locally.
- **Git** – version control for cloning and contributing.
- **Gradle** – optional; only needed if you must regenerate the wrapper.

## Optional Developer Tooling

The following tools are not strictly required to build or run the stack, but they make development and collaboration smoother. Install whichever are relevant to your workflow:

- **GitHub CLI (`gh`)** – recommended for managing pull requests from the command line and enabling AI tooling (such as Codex) to inspect or update PR metadata. See [GitHub CLI Integration for PRs](#github-cli-integration-for-prs) for setup details.
- **Python 3**, **`pip`**, and **`pre-commit`** – used to run the repository’s pre-commit hooks (`pip install pre-commit && pre-commit install`) as described in `CONTRIBUTING.md`.
- **Kreya** – a gRPC client configured via `dev-tools/kreya/.kreya-project.yaml` for calling services like `AccountService`, `EntityService`, and `PlayerService` on `localhost:6565`.
- **Redis CLI (`redis-cli`)** and **RedisInsight** – useful for inspecting transient gameplay/session state in the local Redis instance and browsing keys like `session:*`, `tick:*`, and `timer:*`.
- **Kubernetes CLI (`kubectl`)** and optionally **Helm** – install `kubectl` if you work on `k8s/`, Kustomize overlays, or overlay validation CI. Local validation uses `kubectl kustomize`, so this is effectively required for Kubernetes-related changes.

## Windows + WSL Tooling

If you develop from Windows, prefer running build and package-management tools inside WSL with Linux-native toolchains.

- Install **Node.js/npm inside WSL**, not only on Windows. If `which node` or `which npm` resolves to a `/mnt/c/...` path, you are using the Windows toolchain from a Linux shell, which can break `npm ci`, lockfile generation, and frontend linting.
- Keep your editor on Windows if you want, but run `./gradlew`, `npm`, and `kubectl` from the WSL shell.
- Avoid mixing Windows `node.exe` / `npm.cmd` with a WSL shell for repository work. That mixed setup is fragile and was the source of repeated frontend CI/debugging issues in this repo.
- Install `kubectl` inside WSL as well if you want local overlay validation. You do not need Docker Desktop or a running local cluster just to use `kubectl kustomize`.

Quick verification checklist:

```bash
which node
which npm
node -v
npm -v
kubectl version --client
```

Expected result:

- `node` and `npm` should resolve to Linux paths such as `$HOME/.nvm/...`, not `/mnt/c/...`.
- `kubectl version --client` should succeed from the WSL shell.

## Building Services

The repository ships with a root Gradle wrapper. In normal use, run tasks with `./gradlew`.

### SQL Persistence Direction

For SQL-backed services, the repo’s canonical persistence stack is `jOOQ + Flyway`. Flyway is the schema authority, and SQL-backed services now use generated/executed explicit SQL rather than a mixed ORM runtime. New persistence work should therefore:

- assume `jOOQ + Flyway` is the house style for SQL-backed services;
- keep schema authority in Flyway rather than introducing service-local schema side channels;
- reuse the shared `jOOQ` code generation and runtime helpers instead of inventing one-off service-local repository patterns.

The shared `jOOQ` foundation now exposes a canonical generation task:

```bash
./gradlew :automation-scripting-service:generateJooq
```

Later migrated services should follow that same `:service:generateJooq` pattern through the shared `net.firedevops.firemud.jooq-conventions` plugin rather than inventing service-local codegen tasks.

### Durable Workflow Direction

For long-running control-plane workflows, the repo’s target durable workflow substrate is Temporal. The shared foundation now lives in `services/common-temporal`, and adopter services should opt in through the shared Gradle plugin:

```kotlin
plugins {
    id("net.firedevops.firemud.temporal-conventions")
}
```

The shared foundation exposes these core properties:

- `firemud.temporal.enabled`
- `firemud.temporal.namespace`
- `firemud.temporal.target`
- `firemud.temporal.workers-enabled`
- `firemud.temporal.task-queue-prefix`

Workflow-hosting services should use `TemporalTaskQueueResolver`, `FiremudWorkflowIds`, and `TemporalWorkerRegistrar` from the shared package rather than inventing service-local worker startup or workflow-id formatting.

Until the first real Temporal adopters land, most contributors do **not** need a local Temporal cluster just to work in the repo. The shared foundation is intentionally minimal and the first adopter slices will carry the heavier local runtime/bootstrap guidance when those workflows become executable end to end.

If the wrapper JAR is missing and you need to regenerate it, run:

```bash
gradle wrapper --gradle-version 8.14.3 --distribution-type bin
```

Run this command any time the wrapper JAR is missing. It downloads the
required `gradle-wrapper.jar` into `gradle/wrapper/`.

This recreates `gradlew`, `gradlew.bat`, and the wrapper JAR under `gradle/wrapper/`.

Build all modules with:

```bash
./gradlew build
```

This compiles the repository modules and runs the build lifecycle. It does not build the service container images.

### Running Tests for a Single Service

Gradle project paths map directly to the names defined in `settings.gradle.kts`, so they do **not** include the `services:` prefix even though the source lives under `services/`. To run just one service's tests, use the project name from `settings.gradle.kts`:

```bash
# Example: tcp-proxy-service
./gradlew :tcp-proxy-service:test
```

Using a `services:` prefix (for example `:services:tcp-proxy-service:test`) will fail because no such project path exists in the Gradle settings.

### Spring Profiles for Testing

The only maintained alternate Spring profile is `test`, and Gradle test tasks default to it when no profile is provided. `bootRun` no longer forces a local runtime profile automatically; if you want an in-memory test-style run, set `SPRING_PROFILES_ACTIVE=test` explicitly. If you want the real runtime topology, use the canonical Docker Compose stack or provide the real Postgres/Redis/downstream endpoints directly.

### Telnet Proxy Limits in Local Dev

The TCP Proxy Service enforces connection and envelope limits even in development. When running it locally (for example `./gradlew :tcp-proxy-service:bootRun`), you can override the defaults via environment variables:

- `TCP_PROXY_MAX_CONNECTIONS` – global cap on concurrent Telnet connections (`0` = no explicit ceiling).
- `TCP_PROXY_MAX_CONNECTIONS_PER_IP` – cap on concurrent Telnet connections from a single client IP (`0` = no explicit ceiling).
- `TCP_PROXY_MAX_LINE_BYTES` – maximum Telnet line length before the frame is truncated/closed.
- `TCP_PROXY_MAX_MALFORMED_ENVELOPES` – number of malformed `SESSION` envelopes allowed per connection before it is closed.

For local iteration, it is safe to keep these values low and increase them temporarily in your shell, for example:

```bash
TCP_PROXY_MAX_CONNECTIONS=50 \
TCP_PROXY_MAX_CONNECTIONS_PER_IP=10 \
TCP_PROXY_MAX_MALFORMED_ENVELOPES=10 \
./gradlew :tcp-proxy-service:bootRun
```

Do not commit environment-specific defaults for these variables to version control; treat them as deployment-time configuration managed by your shell, Docker Compose, or Kubernetes manifests.

## GitHub CLI Integration for PRs

Install and authenticate the GitHub CLI so you can manage pull requests locally and allow AI tooling to operate on PR metadata when requested:

```bash
gh auth login
```

Use `GitHub.com` as the host and choose HTTPS with browser login or a personal access token. Once authenticated, commands like `gh pr list`, `gh pr view`, and `gh pr edit --body-file <file>` work from this repository and can be safely invoked by AI assistants as part of an explicit task.

## Building Docker Images

Use the aggregated task to build container images for all services:

```bash
./gradlew buildDockerImages
```

Each invocation runs Spring Boot's `bootBuildImage` for every module and tags the images with `latest`.
The microservice Dockerfiles extend the shared base image
`ghcr.io/benhook1013/firemud-base:latest`. If the base image is missing or out of
date, build it locally with `./gradlew buildBaseImage` or pull the published
version from GitHub Container Registry.

## Markdown Linting via Gradle

This project uses `markdownlint-cli2` to lint Markdown files. To speed up local builds, the `fullCheck` property controls heavy analysis tasks such as SpotBugs, Checkstyle, Spotless checks, and JaCoCo coverage; these are skipped from `./gradlew check` unless `fullCheck` is supplied. Markdown lint and link checks are relatively fast and always run as part of `check`.

```bash
./gradlew check -PfullCheck
```

To always run the full suite of checks locally, add the property to your Gradle user settings:

```properties
# ~/.gradle/gradle.properties
org.gradle.project.fullCheck=true
```

Restart the daemon once with `./gradlew --stop` so the new setting is picked up. Every subsequent build (`./gradlew build`, `./gradlew check`, etc.) then executes SpotBugs, Checkstyle, Spotless checks, JaCoCo coverage, and other tasks gated by `fullCheck`.

Verify the property is active with:

```bash
./gradlew -q properties | grep fullCheck
```

which prints `org.gradle.project.fullCheck: true` when enabled.

For faster iteration, comment out or remove the line from `~/.gradle/gradle.properties` and stop the daemon again. You can also run a single build with `./gradlew check -PfullCheck=false`, but the build script treats the presence of `fullCheck` as truthy, so removing it entirely is the safest way to skip heavy checks.

You can also run the lint task directly:

```bash
./gradlew lintMarkdown
```

To manually fix correctable issues, run:

```bash
./gradlew lintMarkdownFix
```

Linting rules are defined in `config/markdownlint/.markdownlint-cli2.jsonc`. Auto-fixing is not part of the `check` phase so that CI runs remain non-destructive.

## Pre-commit Hooks

If you want lightweight commit-time hygiene, install the pre-commit hooks:

```bash
pip install pre-commit
pre-commit install
```

The configured hooks intentionally stay lightweight:

- `spotlessApply` always runs.
- `lintMarkdownFix` runs when staged Markdown files are present.
- `shellcheck` runs on staged shell scripts in repo-owned script paths.
- `hadolint` runs on staged Dockerfiles.

Heavier checks such as full Gradle `check`, SpotBugs, Checkstyle, broad link validation, and smoke proofs remain explicit local validation steps rather than commit hooks.

### Frontend Lint & Accessibility

The React client in `web-client` provides npm scripts for linting, formatting, and running an accessibility audit. Install dependencies with Linux-native npm from the `web-client` directory:

```bash
cd web-client
npm ci
```

The canonical frontend baseline is `React + Vite + MUI + TanStack Query`. Introduce Redux only if a later slice proves a real shared client-state problem that local feature state plus `TanStack Query` no longer solves cleanly.

Then run these checks:

```bash
cd web-client
npm run lint
npm run format -- -c
```

The accessibility audit relies on the axe-core CLI and requires Google Chrome
to be installed. On Debian-based systems you can install it with:

```bash
sudo apt install -y wget
wget https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb
sudo apt install -y --allow-downgrades ./google-chrome-stable_current_amd64.deb
```

Then execute:

```bash
npm run accessibility
```

### OpenAPI Spec Linting

OpenAPI descriptions for each service live under
`services/**/src/main/resources/openapi.yaml`. Lint these files with

```bash
npm --prefix config/openapi run openapi:lint
```

Run this command from the project root after installing dependencies with
`npm --prefix config/openapi ci`.

## Lombok and MapStruct

The microservices use **Lombok** to reduce boilerplate and **MapStruct** for DTO mapping. Versions are managed centrally through the Gradle version catalog and each service's `build.gradle.kts`.

Make sure annotation processing is enabled in your IDE (for example, IntelliJ IDEA) so Lombok and MapStruct can generate code during compilation.

## Running with Docker Compose

The `docker/docker-compose.yml` file orchestrates all services, including PostgreSQL and Redis, for local development. Launch the stack with:

> **Note**: The `deploy.resources` block in the compose file only applies to
> Docker Swarm. Docker Compose ignores these limits, so CPU and memory
> restrictions are not enforced locally.

```bash
./gradlew devUp
```

This task builds all service images and starts the Docker Compose stack with PostgreSQL and Redis. Connection settings are read from the repository-root `.env` file. A sample file named `.env.sample` is the canonical local-default contract:

```env
FIREMUD_POSTGRES_USER=firemud
FIREMUD_POSTGRES_PASSWORD=firemud
FIREMUD_POSTGRES_DB=firemud
FIREMUD_POSTGRES_HOST=postgres
FIREMUD_POSTGRES_PORT=5432
FIREMUD_REDIS_HOST=redis-cache
FIREMUD_REDIS_PORT=6379
FIREMUD_REDIS_COORD_HOST=redis-coord
FIREMUD_REDIS_COORD_PORT=6379
FIREMUD_REDIS_CACHE_HOST=redis-cache
FIREMUD_REDIS_CACHE_PORT=6379
```

Copy this to `.env` and adjust values as needed before running the stack. The local Compose files intentionally read from `.env` instead of repeating those default literals inline.

To stop the stack:

```bash
./gradlew devDown
```

### Optional CLI Helper

Run `dev-tools/firemud-cli.sh` for shortcuts:

```bash
./dev-tools/firemud-cli.sh up   # start services
./dev-tools/firemud-cli.sh down # stop services
./dev-tools/firemud-cli.sh ping # test a running service
```

### Backing Up the Local Database

Use `dev-tools/backups/backup-db.sh` to create a snapshot and
`dev-tools/restores/restore-db.sh` to restore one:

```bash
./dev-tools/backups/backup-db.sh             # writes to docker/backups
./dev-tools/restores/restore-db.sh backups/<file>
```

### Automatic Kubernetes Backups

Production clusters run a `firemud-pg-dump` CronJob that writes compressed dumps
to a `firemud-pg-dumps` volume. A helper script rotates these files and can
upload them to an object bucket when `PG_DUMP_BUCKET` is configured. Retrieve a
dump with `kubectl cp` or `aws s3 cp` and restore it with `psql` as shown in the
runbooks. Velero schedules back up only Kubernetes manifests.

### Local Database Cron Backups

The Docker Compose stack includes a `pg-dump-cron` service that runs
`dev-tools/backups/pg-dump-rotate.sh` every 15 minutes. Dumps are written to the
`docker/backups/` directory and follow the same 15min/daily/weekly/monthly rotation policy as
production. Set `PG_DUMP_BUCKET` and `PG_DUMP_ENDPOINT` to automatically upload
the files to your object store.

### Optional Redis Persistence

Cache/Rate-Limit Redis is best-effort and starts empty between restarts.
Coordination Redis persists its AOF across container launches via the
`redis-coord-data` volume. You can manually restore an AOF backup into the
local Coordination Redis with:

```bash
./dev-tools/restores/restore-redis-aof.sh backups/appendonly.aof
```

This helper is intended **only** for local development. Production Redis nodes
rely on their own durability/failover behavior and scoped coordination resets
as described in the Redis architecture and runbooks.

## Manual Testing Tools

### Kreya for gRPC APIs

The `dev-tools/kreya/.kreya-project.yaml` file configures Kreya to load all
protos from `./protos/` and targets `localhost:6565` by default. Services such
as `AccountService`, `EntityService`, and `PlayerService` are preconfigured.
JWT metadata is enabled only for admin endpoints; gameplay services do not
require it. Open Kreya, choose **Open Project** and select the project file to
invoke gRPC methods. When proto definitions change, update the project by
pointing Kreya to the modified `.proto` files.

Smoke test scripts also target `localhost:6565`. Docker Compose does not
publish this port to the host, so run them inside the Compose network with
`docker compose exec <service> ./smoke-test.sh` or set `GRPC_ADDR` to the
service hostname (e.g., `account-service:6565`).

### Redis Debugging

Use the Redis CLI to inspect transient state:

- Coordination Redis: `redis-cli -h localhost -p 6379`
- Cache/Rate-Limit Redis: `redis-cli -h localhost -p 6380`

Useful commands include:

```bash
SCAN 0 MATCH 'session:game:<tenantId>:*'
GET 'tick-executor-lease:{<tenantRegionTag>}'
ZRANGE 'timer:{<tenantRegionTag>}' 0 10 WITHSCORES
```

For a graphical view, a RedisInsight container runs in development via
`docker/docker-compose.override.yml`. Bring up the stack with `docker compose -f
docker/docker-compose.yml -f docker/docker-compose.override.yml up --build -d`. RedisInsight is then
available at <http://localhost:8001>. Add both Redis endpoints:

- Coordination: `localhost:6379`
- Cache/Rate-Limit: `localhost:6380`

Typical key patterns include `session:*`, `tick:*`, `timer:*`, and `ratelimit:*`.

## Configuration Files

Environment‑specific settings live in each service's `src/main/resources` directory.

- `application.yml` – base configuration for the canonical runtime contract.
- `SPRING_PROFILES_ACTIVE` – environment variable used to select the active profile at runtime.

More details on deployment environments and gateway routing can be found in the following design documents:

- [Deployment Environments](design/architecture/infrastructure/deployment-environments.md)
- [Gateway Architecture](design/architecture/system-architecture-gateway.md)
- [Infrastructure Overview](design/architecture/infrastructure/README.md) – explains TLS/mTLS certificates, multi-tenancy, and network boundaries.

These documents explain how the compose setup differs from production and provide examples of the configuration files.

### Local development model

- The canonical local path now uses the normal runtime configuration with the real Postgres, Redis, Gateway, Account, and gameplay-service topology.
- For end-to-end validation, prefer the repo-owned smoke scripts under `dev-tools/` instead of ad hoc single-service shortcuts:
  - `dev-tools/verify-fresh-bootstrap.sh`
  - `dev-tools/verify-restart-state.sh`
- Treat local source-built smoke as the primary proof path for gameplay changes. Docker Compose and smoke workflows should exercise the same runtime topology that production-like environments use, not alternate dependency-light modes.

### Running Gradle from WSL

- Running Gradle inside WSL avoids the Windows file-locking issues that can block `build/test-results/**`. Open a WSL shell, `cd` into this repository via the `/mnt/c/.../FireMUD` path, and run the usual `./gradlew …` commands there.
- You can keep your editor on Windows while letting long-running builds/tests execute on the Linux filesystem by pointing it at the same working tree.
- Use the same rule for frontend and Kubernetes tooling: run `npm` and `kubectl` from the WSL shell with Linux-native installs rather than Windows executables on the mounted drive.

---

You are now ready to explore the codebase and contribute!

## Related Documentation

- [Infrastructure Overview](design/architecture/infrastructure/README.md)
- [Deployment Environments](design/architecture/infrastructure/deployment-environments.md)
- [Gateway Architecture](design/architecture/system-architecture-gateway.md)
- [System Architecture Overview](design/architecture/system-architecture-overview.md)
