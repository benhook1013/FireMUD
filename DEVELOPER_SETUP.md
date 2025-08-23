# 🛠 Developer Setup

This guide explains how to configure a local development environment for the FireMUD Game Platform.

## Prerequisites

Install the following tools before building the services:

- **Java 21+** – required for all Spring Boot microservices.
- **Gradle** – used to build and test each service.
- **Node.js** (latest LTS) – needed if you plan to build the React frontend.
- **Docker** and **Docker Compose** – run the full stack locally.
- **Git** – version control for cloning and contributing.

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

This project uses `markdownlint-cli2` to lint Markdown files. To speed up local builds, `./gradlew check` skips Markdown lint along with SpotBugs, Checkstyle, JaCoCo coverage, and other heavy analysis unless the `fullCheck` property is supplied:

```bash
./gradlew check -PfullCheck
```

To always run the full suite of checks locally, add the property to your Gradle user settings:

```properties
# ~/.gradle/gradle.properties
org.gradle.project.fullCheck=true
```

Restart the daemon once with `./gradlew --stop` so the new setting is picked up. Every subsequent build (`./gradlew build`, `./gradlew check`, etc.) then executes SpotBugs, Checkstyle, Jacoco coverage, and other tasks gated by `fullCheck`.

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

The React client in `web-client` provides npm scripts for linting, formatting,
and running an accessibility audit. After installing dependencies with
`npm --prefix config/openapi ci`. you can run these checks:

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
FIREMUD_REDIS_HOST=redis
FIREMUD_REDIS_PORT=6379
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

Redis normally starts empty between service restarts. If you want to keep the
Append-Only File (AOF) across container launches, the compose stack mounts a
`redis-data` volume. You can manually restore an AOF backup with:

```bash
./dev-tools/restores/restore-redis-aof.sh backups/appendonly.aof
```

This helper is intended **only** for local development. Production Redis nodes
rely on replication and automatically repopulate state from PostgreSQL.

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

Use the Redis CLI (`redis-cli -h localhost -p 6379`) to inspect transient game state. Useful commands include:

```bash
SCAN 0 MATCH session:*
GET tick:lock:entity-xyz
SCAN 0 MATCH timer:player-123:*
```

For a graphical view, a RedisInsight container runs in development via
`docker/docker-compose.override.yml`. Bring up the stack with `docker compose -f
docker/docker-compose.yml -f docker/docker-compose.override.yml up --build -d`. RedisInsight is then
available at <http://localhost:8001> and connects to the default Redis instance
on `localhost:6379`. Typical key patterns are `session:*`, `tick:*`, and
`timer:*`.

## Configuration Files

Environment‑specific settings live in each service's `src/main/resources` directory.

- `application.yml` – base configuration containing both `dev` and `prod` profile sections.
- `SPRING_PROFILES_ACTIVE` – environment variable used to select the active profile at runtime.

More details on deployment environments and gateway routing can be found in the following design documents:

- [Deployment Environments](design/architecture/infrastructure/deployment-environments.md)
- [Gateway Architecture](design/architecture/system-architecture-gateway.md)
- [Infrastructure Overview](design/architecture/infrastructure/README.md) – explains TLS/mTLS certificates, multi-tenancy, and network boundaries.

These documents explain how the compose setup differs from production and provide examples of the configuration files.

---

You are now ready to explore the codebase and contribute!

## 📚 Related Documentation

- [Infrastructure Overview](design/architecture/infrastructure/README.md)
- [Deployment Environments](design/architecture/infrastructure/deployment-environments.md)
- [Gateway Architecture](design/architecture/system-architecture-gateway.md)
- [System Architecture Overview](design/architecture/system-architecture-overview.md)
