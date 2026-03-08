# 🛠 Developer Setup

This guide explains how to configure a local development environment for the FireMUD Game Platform.

## Prerequisites

Install the following tools before building the services:

- **Java 21+** – required for all Spring Boot microservices.
- **Gradle** – used to build and test each service.
- **Node.js** (latest LTS) – needed if you plan to build the React frontend.
- **Docker** and **Docker Compose** – run the full stack locally.
- **Git** – version control for cloning and contributing.

## Optional Developer Tooling

The following tools are not strictly required to build or run the stack, but they make development and collaboration smoother. Install whichever are relevant to your workflow:

- **GitHub CLI (`gh`)** – recommended for managing pull requests from the command line and enabling AI tooling (such as Codex) to inspect or update PR metadata. See [GitHub CLI Integration for PRs](#github-cli-integration-for-prs) for setup details.
- **Python 3**, **`pip`**, and **`pre-commit`** – used to run the repository’s pre-commit hooks (`pip install pre-commit && pre-commit install`) as described in `CONTRIBUTING.md`.
- **Insomnia** – a desktop client for REST and WebSocket testing. An Insomnia project is provided in `dev-tools/insomnia/` to exercise login, registration, and gateway admin routes.
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

Each microservice includes a `Dockerfile` and a Gradle build script. After cloning the repository, generate the Gradle wrapper scripts if they are not already present:

```bash
gradle wrapper --gradle-version 8.14.3 --distribution-type bin
```

Run this command any time the wrapper JAR is missing. It downloads the
required `gradle-wrapper.jar` into `gradle/wrapper/`.

This creates `gradlew`, `gradlew.bat`, and the wrapper JAR under `gradle/wrapper/`. You only need to run it once after cloning.

If you're on Windows, a PowerShell script is available to generate wrappers for every service:

```powershell
./dev-tools/init-gradle-wrappers.ps1
```

Run this script after cloning if the wrapper files are missing from the individual service folders.

Build all modules with:

```bash
# Build all images
./gradlew build
```

Gradle compiles the services and prepares Docker images using the included Dockerfiles.

### Running Tests for a Single Service

Gradle project paths map directly to the names defined in `settings.gradle.kts`, so they do **not** include the `services:` prefix even though the source lives under `services/`. To run just one service's tests, use the project name from `settings.gradle.kts`:

```bash
# Example: tcp-proxy-service
./gradlew :tcp-proxy-service:test
```

Using a `services:` prefix (for example `:services:tcp-proxy-service:test`) will fail because no such project path exists in the Gradle settings.

### Spring Profiles for Testing

Local development and CI default to relaxed Spring profiles so you can run `./gradlew bootRun` or `./gradlew test` without provisioning PostgreSQL. The build script sets `spring.profiles.active` to `dev` for `bootRun` and `test` for `Test` tasks when no profile is provided, and those profiles disable Flyway while pointing to an in-memory H2 datasource. Set `SPRING_PROFILES_ACTIVE=prod` (or `--args=--spring.profiles.active=prod` for `bootRun`) when you specifically want to use PostgreSQL, such as when running the Docker Compose stack or validating migration scripts.

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

### Optional Local PR Summary File

You can maintain a local, untracked summary of your changes and sync it to a pull request body with `gh`:

1. Create or update a `pr-summary.md` file in the repository root with the description you want in the PR body.
2. Apply it to an open PR with:

   ```bash
   gh pr edit <pr-number> --body-file pr-summary.md
   ```

The `pr-summary.md` file is listed in `.gitignore` so it is not committed. AI tooling (such as Codex) may update this file and run the `gh pr edit` command on your behalf when you explicitly ask it to refresh the PR description.

## 🐳 Building Docker Images

Use the aggregated task to build container images for all services:

```bash
./gradlew buildDockerImages
```

Each invocation runs Spring Boot's `bootBuildImage` for every module and tags the images with `latest`.
The microservice Dockerfiles extend the shared base image
`ghcr.io/benhook1013/firemud-base:latest`. If the base image is missing or out of
date, build it locally with `./gradlew buildBaseImage` or pull the published
version from GitHub Container Registry.

## ✅ Markdown Linting via Gradle

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

### Frontend Lint & Accessibility

The React client in `web-client` provides npm scripts for linting, formatting, and running an accessibility audit. Install dependencies with Linux-native npm from the `web-client` directory:

```bash
cd web-client
npm ci
```

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

The microservices use **Lombok** to cut down on boilerplate and **MapStruct** for DTO mapping. Each service's `build.gradle.kts` already declares these dependencies:

```kotlin
dependencies {
    implementation("org.mapstruct:mapstruct:1.5.5.Final")
    annotationProcessor("org.mapstruct:mapstruct-processor:1.5.5.Final")

    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")
}
```

Make sure annotation processing is enabled in your IDE (e.g., IntelliJ IDEA) so Lombok and MapStruct can generate code during compilation.

## Spring Profiles and Databases

Local dev and CI tasks default to the `dev` or `test` Spring profiles. These profiles disable Flyway and point the services at an in-memory H2 datasource so `bootRun` and unit tests start without a running PostgreSQL instance. Use the `prod` profile (for example, `SPRING_PROFILES_ACTIVE=prod ./gradlew :service:bootRun` or via Docker Compose) when you need Flyway migrations against PostgreSQL.

## Running with Docker Compose

The `docker/docker-compose.yml` file orchestrates all services, including PostgreSQL and Redis, for local development. Launch the stack with:

> **Note**: The `deploy.resources` block in the compose file only applies to
> Docker Swarm. Docker Compose ignores these limits, so CPU and memory
> restrictions are not enforced locally.

```bash
./gradlew devUp
```

This task builds all service images and starts the Docker Compose stack with PostgreSQL and Redis. Connection settings are read from an optional `.env` file. A sample file named `.env.sample` is provided with default credentials:

```env
FIREMUD_POSTGRES_USER=firemud
FIREMUD_POSTGRES_PASSWORD=firemud
FIREMUD_POSTGRES_DB=firemud
FIREMUD_POSTGRES_HOST=postgres
FIREMUD_POSTGRES_PORT=5432
FIREMUD_REDIS_COORD_HOST=redis-coord
FIREMUD_REDIS_COORD_PORT=6379
FIREMUD_REDIS_CACHE_HOST=redis-cache
FIREMUD_REDIS_CACHE_PORT=6379
```

Copy this to `.env` and adjust values as needed before running the stack.

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
./dev-tools/backups/backup-db.sh             # writes to ./backups
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
`./backups` directory and follow the same 15min/daily/weekly/monthly rotation policy as
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

### Insomnia for REST and WebSocket

An Insomnia project is included under `dev-tools/insomnia/`. From the
**Import/Export** menu choose **Import From File** and select
`firemud-insomnia.json` to quickly test login, registration, and gateway admin routes. The project defines a **Base Environment** with `base_url` and an optional `jwt` variable for admin endpoints. If you populate the variable, Insomnia injects `Authorization: Bearer {{ jwt }}` on calls that need authorization.

WebSocket testing is also configured. Use the `WebSocket Login` request to send
raw commands like:

```text
LOGIN user pass
MOVE north
CAST "fireball"
```

Add or modify requests directly in Insomnia and re-export the workspace if you
need to share updates.

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

- `application.yml` – base configuration containing both `dev` and `prod` profile sections.
- `SPRING_PROFILES_ACTIVE` – environment variable used to select the active profile at runtime.

More details on deployment environments and gateway routing can be found in the following design documents:

- [Deployment Environments](design/architecture/infrastructure/deployment-environments.md)
- [Gateway Architecture](design/architecture/system-architecture-gateway.md)
- [Infrastructure Overview](design/architecture/infrastructure/README.md) – explains TLS/mTLS certificates, multi-tenancy, and network boundaries.

These documents explain how the compose setup differs from production and provide examples of the configuration files.

### Dev-isolated stubbed services

- Several services expose a **dev-isolated mode** that keeps core flows testable without standing up the full dependency graph. These modes are wired through Gradle `bootRunDevIsolated` tasks and corresponding environment variables, and are intended strictly for local smoke tests and debugging—not for production.
- When `game-session.dev-isolated` (or `GAME_SESSION_DEV_ISOLATED`) is `true`, the Game Session Service (and dependent tests) uses in-memory replacements (`DevIsolatedSessionContextService`, `DevIsolatedGameInstanceService`, and `DevIsolatedGameInstanceRegistry`) instead of hitting Redis/JPA. This keeps LOGIN + LOOK flows runnable on a developer laptop that lacks PostgreSQL/Redis/Account Service dependencies. You can start this mode with `./gradlew :game-session-service:bootRunDevIsolated` (see `services/game-session-service/README.md` and `design/architecture/microservices/game-session-service/README.md#dev-isolated-mode` for details).
- The TCP Proxy Service exposes a similar dev-isolated mode controlled by `TCP_PROXY_DEV_ISOLATED`. The `./gradlew :tcp-proxy-service:bootRunDevIsolated` task and the `docker/docker-compose.tcp-proxy-devisolated.yml` profile both set this flag so you can smoke-test Telnet input against the proxy’s in-process echo handler without starting Spring Cloud Gateway, Game Session, or the rest of the stack (see `design/architecture/microservices/tcp-proxy-service/README.md#local-development-and-echo-loop`).
- Spring Cloud Gateway also provides a `./gradlew :spring-cloud-gateway:bootRunDevIsolated` task that runs with the `dev` profile and enables `TCP_PROXY_DEV_ISOLATED` for local WebSocket debugging that mirrors the proxy’s dev-isolated behavior, while still relying on in-process stubs instead of full upstream services.
- The dev-isolated smoke/integration tests (`DevIsolatedGameSessionSmokeTest`, `GameSessionLoginIntegrationTest`, `GameSessionWebSocketHandlerIntegrationTest`, and `SessionResumptionFlowTest`) are currently annotated with `@Disabled` and reference the TODO in `design/project-management/vertical-slices/02-task-list-login-and-session-vertical-slice.md#7-dev-mode-stubs-and-real-service-rollout`. They should be revisited and re-enabled once the real Account/Redis/GameInstance wiring is available so Gradle runs against production services instead of the stubbed dev-isolated path.

### Running Gradle from WSL

- Running Gradle inside WSL avoids the Windows file-locking issues that can block `build/test-results/**`. Open a WSL shell, `cd` into this repository via the `/mnt/c/.../FireMUD` path, and run the usual `./gradlew …` commands there.
- You can keep your editor on Windows while letting long-running builds/tests execute on the Linux filesystem by pointing it at the same working tree.
- Use the same rule for frontend and Kubernetes tooling: run `npm` and `kubectl` from the WSL shell with Linux-native installs rather than Windows executables on the mounted drive.

---

You are now ready to explore the codebase and contribute!

## 📚 Related Documentation

- [Infrastructure Overview](design/architecture/infrastructure/README.md)
- [Deployment Environments](design/architecture/infrastructure/deployment-environments.md)
- [Gateway Architecture](design/architecture/system-architecture-gateway.md)
- [System Architecture Overview](design/architecture/system-architecture-overview.md)
