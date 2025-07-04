# Architecture Overview

The architecture section describes the platform infrastructure and each microservice.

## High-Level Diagrams

- [**system-architecture-overview.md**](./system-architecture-overview.md) – High-level diagrams and interactions.
- [**system-context-diagram.md**](./system-context-diagram.md) – Shows clients, DMZ components, services, and datastores.
- [**system-architecture-diagram.md**](./system-architecture-diagram.md) – Component relationships.

## Directories & Responsibilities

- [**infrastructure/**](./infrastructure/) – Deployment environments, gateway design, and protocol bridging.
- [**microservices/**](./microservices/) – Individual service responsibilities and APIs.
- [**service-responsibility-matrix.md**](./service-responsibility-matrix.md) – Summary of which service handles what.
- [**repository-structure.md**](./repository-structure.md) – Layout of Gradle modules and repository files.

## Runtime Architecture

- [**system-architecture-authentication.md**](./system-architecture-authentication.md) – Authentication mechanisms and session handling.
- [**system-architecture-security.md**](./system-architecture-security.md) – Cross-service security and secret management.
- [**system-architecture-grpc.md**](./system-architecture-grpc.md) – Conventions for proto layout and versioning.
- [**system-architecture-redis.md**](./system-architecture-redis.md) – Redis deployment topology and usage patterns.
- [**system-architecture-reconnection.md**](./system-architecture-reconnection.md) – Client reconnect flow across services.
- [**system-architecture-ticks.md**](./system-architecture-ticks.md) – Tick system and runtime design.
- [**system-architecture-scripting.md**](./system-architecture-scripting.md) – Automation and scripting framework.
- [**system-architecture-versioning-runtime.md**](./system-architecture-versioning-runtime.md) – Publishing versions and runtime flags.
- [**system-architecture-multi-tenancy.md**](./system-architecture-multi-tenancy.md) – Hosting multiple games on shared infrastructure.
- [**system-architecture-frontend.md**](./system-architecture-frontend.md) – React UI structure and state management.
- [**system-architecture-shared-libraries.md**](./system-architecture-shared-libraries.md) – Common libraries for microservices.
- [**system-architecture-logging-monitoring.md**](./system-architecture-logging-monitoring.md) – Logging and observability stack.
- [**system-architecture-database-migrations.md**](./system-architecture-database-migrations.md) – Managing schema changes per service.
- [**system-architecture-testing.md**](./system-architecture-testing.md) – Unit, integration, and load testing strategy.

## Operations

- [**system-architecture-cicd.md**](./system-architecture-cicd.md) – CI/CD pipeline design using GitHub Actions.
- [**system-architecture-backup-recovery.md**](./system-architecture-backup-recovery.md) – Backup strategy and disaster recovery procedures.

## Additional Resources

- [**user-journeys.md**](./user-journeys.md) – Example creator and player workflows.

Refer to the README files within each subdirectory for more details.
