# 🛠️ FireMUD System Architecture: Database Migrations

This document explains how FireMUD manages PostgreSQL schema changes across its microservices. Each service owns its tables and applies migrations independently.

---

## 🚀 Migration Tool

- **Flyway** is used for all schema migrations.
- Versioned SQL files live under each service in `src/main/resources/db/migration/`.
- Migrations follow the `V<version>__<description>.sql` naming convention.
- Java-based callbacks are avoided; migrations remain SQL-only for portability.

## 📂 Per-Service Organization

- Every microservice maintains its own migration folder and changelog.
- Schemas are isolated: services never modify each other's tables.
- Common tables shared by multiple services reside in a small `shared` module with its own migrations.
- New migrations are committed alongside service code so history stays with the owning service.

## 🔄 CI/CD Execution

- Flyway runs automatically when a service container starts.
- In development you can run `./gradlew flywayMigrate` for a single service.
- During deployment GitHub Actions builds the Docker image, pushes it, and Kubernetes restarts the service.
- On startup the container executes Flyway against its database schema before the Spring application fully starts.

Migrations are therefore applied consistently in every environment without manual steps.

### ERD Diagrams

CI runs `dev-tools/generate-erd.sh` after tests complete to create database diagrams. The script concatenates each service's migrations, converts them to DBML via `@dbml/cli`, and uploads the output as build artifacts.

---

## 📚 Related Documentation

- [CI/CD Pipeline](./system-architecture-cicd.md)
- [Backup & Disaster Recovery](./system-architecture-backup-recovery.md)
