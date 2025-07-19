# 🛠️ FireMUD System Architecture: Database Migrations

This document explains how FireMUD manages PostgreSQL schema changes across its microservices. Each service owns its tables and applies migrations independently.

---

## 🚀 Migration Tool

- **Flyway** is used for all schema migrations.
- Each service's `build.gradle.kts` applies the `org.flywaydb.flyway` plugin.
  Most services explicitly depend on `flyway-core`, while others rely on the
  plugin's default dependency.
- Versioned SQL files live under each service in `src/main/resources/db/migration/`.
- Migrations follow the `V<version>__<description>.sql` naming convention.
- Every module begins with a `V1__init.sql` baseline and numbers sequentially from there.
- `spring.flyway.enabled=true` in `application.yml` triggers migration execution on startup.
- Java-based callbacks are avoided; migrations remain SQL-only for portability.

## 📂 Per-Service Organization

- Every microservice maintains its own migration folder and changelog.
- Schemas are isolated: services never modify each other's tables.
- Tables currently reside in the `public` schema but will move to dedicated
  schemas for each service for better isolation. See
  [Multi-Tenancy](./system-architecture-multi-tenancy.md). (TODO: Not yet implemented)
- Common tables shared by multiple services reside in the `common-library` module with its own migrations.
- It contains saga table migrations described in [System Architecture – Transactions](./system-architecture-transactions.md).
- Services that need these shared tables include the module as a dependency during their build.
- The library packages its migrations inside the JAR so Flyway automatically
  picks them up from the classpath when each service starts.
  The `common-library` itself does not run Flyway; consuming services execute
  these migrations on startup.
- New migrations are committed alongside service code so history stays with the owning service.

## 🔄 CI/CD Execution

- Flyway runs automatically when a service container starts.
- In development you can run `./gradlew flywayMigrate` for a single service.
- Execute this task from the service directory or prefix the project name (e.g.,
  `./gradlew :account-service:flywayMigrate`).
- You can also run `./gradlew :service:flywayInfo`, `flywayClean`, or `flywayRepair` to troubleshoot local databases. **Use `flywayClean` with caution** because it drops tables.
- Run `./gradlew :service:flywayValidate` to verify migrations before committing.
- The CI pipeline will eventually run `flywayValidate` for all services to catch
  migration issues early. (TODO: Not yet implemented)
- See [DEVELOPER_SETUP.md](../../DEVELOPER_SETUP.md) for the environment variables needed to connect to your local PostgreSQL instance. Copy the `FIREMUD_POSTGRES_*` values from `.env.sample` into `.env` so Flyway can connect locally.
- During deployment GitHub Actions builds the Docker image, pushes it, and Kubernetes restarts the service. Full automation of this step is still being developed (TODO: Not yet implemented).
- On startup the container executes Flyway against its database schema before the Spring application fully starts.
- The `dev-tools/generate-erd.sh` script uses Flyway to clean and migrate temporary databases when generating ERD diagrams.
- Diagrams are written to `design/erd/` and the CI workflow collects this
   directory as an artifact.

Migrations are therefore applied consistently in every environment without manual steps.

---

## 📚 Related Documentation

- [CI/CD Pipeline](./system-architecture-cicd.md)
- [Backup & Disaster Recovery](./system-architecture-backup-recovery.md)
