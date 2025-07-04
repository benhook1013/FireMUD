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

Build all modules with:

```bash
# Build all images
./gradlew build
```

Gradle compiles the services and prepares Docker images using the included Dockerfiles.

## Running with Docker Compose

The `docker-compose.yml` file (to be added in a future update) orchestrates all services for local development. Launch the stack with:

```bash
docker compose up --build
```

This command builds any missing images and starts the gateway, microservices, and supporting containers like PostgreSQL and Redis. Environment variables are defined in `.env` files referenced by the compose configuration.

To stop the stack:

```bash
docker compose down
```

## Configuration Files

Environment‑specific settings live in Spring Boot profile files:

- `application-dev.yml` – used when running with Docker Compose.
- `application-prod.yml` – used in Kubernetes deployments.

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
