The architecture section describes the platform infrastructure and each microservice.

- [**infrastructure/**](./infrastructure/) – Deployment environments, gateway design, and protocol bridging.
- [**microservices/**](./microservices/) – Individual service responsibilities and APIs.
- [**service-responsibility-matrix.md**](./service-responsibility-matrix.md) – Summary of which service handles what.
- [**system-architecture-authentication.md**](./system-architecture-authentication.md) – Authentication mechanisms and session handling.
- [**system-architecture-backup-recovery.md**](./system-architecture-backup-recovery.md) – Backup strategy and disaster recovery procedures.
- [**system-architecture-cicd.md**](./system-architecture-cicd.md) – CI/CD pipeline design using GitHub Actions.
- [**system-architecture-database-migrations.md**](./system-architecture-database-migrations.md) – How Flyway manages schema changes per service.
- [**system-architecture-diagram.md**](./system-architecture-diagram.md) – Diagram of component relationships.
- [**system-architecture-frontend.md**](./system-architecture-frontend.md) – React UI structure, state management, and build tooling.
- [**system-architecture-grpc.md**](./system-architecture-grpc.md) – Conventions for proto layout, versioning, and tooling.
- [**system-architecture-logging-monitoring.md**](./system-architecture-logging-monitoring.md) – Logging and observability stack.
- [**system-architecture-multi-tenancy.md**](./system-architecture-multi-tenancy.md) – Hosting multiple games on shared infrastructure.
- [**system-architecture-overview.md**](./system-architecture-overview.md) – High-level diagrams and interactions.
- [**system-architecture-redis.md**](./system-architecture-redis.md) – Redis deployment topology and usage patterns.
- [**system-architecture-reconnection.md**](./system-architecture-reconnection.md) – Client reconnect flow across services.
- [**system-architecture-scripting.md**](./system-architecture-scripting.md) – Automation and scripting framework.
- [**system-architecture-security.md**](./system-architecture-security.md) – Cross-service security and secret management.
- [**system-architecture-shared-libraries.md**](./system-architecture-shared-libraries.md) – Common libraries for microservices.
- [**system-architecture-testing.md**](./system-architecture-testing.md) – Unit, integration, and load testing strategy.
- [**system-architecture-ticks.md**](./system-architecture-ticks.md) – Tick system and runtime design.
- [**system-architecture-versioning-runtime.md**](./system-architecture-versioning-runtime.md) – How versions are published and runtime flags are controlled.
- [**system-context-diagram.md**](./system-context-diagram.md) – Shows clients, DMZ components, services, and datastores.
- [**user-journeys.md**](./user-journeys.md) – Example creator and player workflows.

Refer to the README files within each subdirectory for more details.
