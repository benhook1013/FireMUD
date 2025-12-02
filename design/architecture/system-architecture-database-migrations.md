# FireMUD System Architecture: Database Migrations

This document explains how FireMUD manages PostgreSQL schema changes across its microservices. Each service owns its tables and applies migrations independently.

---

## Migration Tool

- **Flyway** is used for all schema migrations.
- The root `build.gradle.kts` applies the `org.flywaydb.flyway` plugin and adds
  `flyway-core`, `flyway-database-postgresql`, and the PostgreSQL driver for
  every service module under `services/` (excluding the `common-library`
  module, which does not run Flyway itself).
- Versioned SQL files live under each service in `src/main/resources/db/migration/`.
- Migrations follow the `V<version>__<description>.sql` naming convention.
- Every module begins with a `V1__init.sql` baseline and numbers sequentially from there.
- `spring.flyway.enabled=true` in `application.yml` triggers migration execution on startup.
- Flyway reads connection settings from the `FIREMUD_POSTGRES_*` environment variables described in
  [Environment & Secrets](./infrastructure/environment-and-secrets.md).
- Java-based callbacks are avoided; migrations remain SQL-only for portability.

## Per-Service Organization

- Every microservice maintains its own migration folder and changelog.
- Schemas are isolated: services never modify each other's tables.
- Tables reside in dedicated schemas for each service to ensure isolation.
  Schema names match the owning service (for example `account_service`). See
  [Multi-Tenancy](./system-architecture-multi-tenancy.md) for details.
- Common tables shared by multiple services reside in the `common-library` module with its own migrations.
- It contains saga table migrations described in [System Architecture – Transactions](./system-architecture-transactions.md).
- Services that need these shared tables include the module as a dependency during their build.
- The library packages its migrations inside the JAR so Flyway automatically
  picks them up from the classpath when each service starts.
  The `common-library` itself does not run Flyway; consuming services execute
  these migrations on startup.
- New migrations are committed alongside service code so history stays with the owning service.

## CI/CD Execution

- Flyway runs automatically when a service container starts.
  If any migration fails, the application startup aborts so issues are caught early.
- In development you can run `./gradlew flywayMigrate` for a single service.
- Execute this task from the service directory or prefix the project name (e.g.,
  `./gradlew :account-service:flywayMigrate`).
- You can also run `./gradlew :service:flywayInfo`, `flywayClean`, or `flywayRepair` to troubleshoot local databases. **Use `flywayClean` with caution** because it drops tables.
- Run `./gradlew :service:flywayValidate` to verify migrations before committing.
- The CI pipeline runs `flywayValidate` for all services to catch migration issues early.
- See [DEVELOPER_SETUP.md](../../DEVELOPER_SETUP.md) for the environment variables needed to connect to your local PostgreSQL instance. Copy the `FIREMUD_POSTGRES_*` values from `.env.sample` into `.env` so Flyway can connect locally.
- During deployment GitHub Actions builds the Docker image, pushes it, and Kubernetes restarts the service. This step is fully automated.
- On startup the container executes Flyway against its database schema before the Spring application fully starts.
- The [`dev-tools/docs/generate-erd.sh`](../../dev-tools/docs/generate-erd.sh) script uses Flyway to clean and migrate temporary databases when generating ERD diagrams.
- Diagrams are written to `design/erd/` and the CI workflow collects this
   directory as an artifact.

Migrations are therefore applied consistently in every environment without manual steps.

---

## Related Documentation

- [Backup & Disaster Recovery](./system-architecture-backup-recovery.md)
- [CI/CD Pipeline](./system-architecture-cicd.md)
