# 🛠 Developer Setup

This guide explains how to configure a local development environment for the FireMUD Game Platform.

## Prerequisites

Install the following tools before building the services:

- **Java 17+** – required for all Spring Boot microservices.
- **Gradle** – used to build and test each service.
- **Node.js** (latest LTS) – needed if you plan to build the React frontend.
- **Docker** and **Docker Compose** – run the full stack locally.
- **Git** – version control for cloning and contributing.

## Building Services

Each microservice includes a `Dockerfile` and a Gradle build script. After cloning the repository, generate the Gradle wrapper scripts if they are not already present:

```bash
gradle wrapper --gradle-version 8.5 --distribution-type bin
```

This creates `gradlew`, `gradlew.bat`, and the wrapper JAR under `gradle/wrapper/`. You only need to run it once after cloning.

If you're on Windows, a PowerShell script is available in the `services` directory to generate wrappers for every service:

```powershell
cd services
./init-gradle-wrappers.ps1
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

## ✅ Markdown Linting via Gradle

This project uses `markdownlint-cli2` to lint Markdown files. The `check` task automatically runs linting in check-only mode:

```bash
./gradlew check
```

To manually fix correctable issues, run:

```bash
./gradlew lintMarkdownFix
```

Linting rules are defined in `.markdownlint.json` at the project root. Auto-fixing is not part of the `check` phase so that CI runs remain non-destructive.

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

The `docker-compose.yml` file orchestrates all services, including PostgreSQL and Redis, for local development. Launch the stack with:

```bash
./gradlew devUp
```

This task builds all service images and starts the Docker Compose stack with PostgreSQL and Redis. Connection settings are read from an optional `.env` file. A sample file named `.env.sample` is provided with default credentials:

```env
POSTGRES_USER=firemud
POSTGRES_PASSWORD=firemud
POSTGRES_DB=firemud
POSTGRES_HOST=postgres
POSTGRES_PORT=5432
REDIS_HOST=redis
REDIS_PORT=6379
```

Copy this to `.env` and adjust values as needed before running the stack.

To stop the stack:

```bash
./gradlew devDown
```

## Configuration Files

Environment‑specific settings live in Spring Boot profile files contained within each service's `src/main/resources` directory.

- `application.yml` – base configuration with `dev` and `prod` profiles.
- `application-dev.yml` – legacy name; now included as a profile section in `application.yml`.
- `application-prod.yml` – legacy name; also included as a profile section.

More details on deployment environments and gateway routing can be found in the following design documents:

- [Deployment Environments](design/architecture/infrastructure/deployment-environments.md)
- [Gateway Architecture](design/architecture/infrastructure/gateway-architecture.md)
- [Infrastructure Overview](design/architecture/infrastructure/README.md) – explains TLS/mTLS certificates, multi-tenancy, and network boundaries.

These documents explain how the compose setup differs from production and provide examples of the configuration files.

---

You are now ready to explore the codebase and contribute!

## 📚 Related Documentation

- [Infrastructure Overview](design/architecture/infrastructure/README.md)
- [Deployment Environments](design/architecture/infrastructure/deployment-environments.md)
- [Gateway Architecture](design/architecture/infrastructure/gateway-architecture.md)
- [System Architecture Overview](design/architecture/system-architecture-overview.md)
