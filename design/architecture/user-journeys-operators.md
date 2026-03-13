# FireMUD User Journeys: Operators

This guide summarizes typical workflows for administrators, moderators, and platform operators. It focuses on monitoring, moderation, operational recovery, deployment, CI/CD, and platform-level updates. Use it alongside the [Architecture Overview](./README.md), the [System Architecture Overview](./system-architecture-overview.md), the [System Architecture Diagram](./system-architecture-diagram.md), and the [System Context Diagram](./system-context-diagram.md).

For other personas, see:

- [Player Journeys](./user-journeys-players.md)
- [Creator Journeys](./user-journeys-creators.md)
- [User Journeys Hub](./user-journeys.md)

## Table of Contents

- [Goals](#goals)
- [Quick Reference](#quick-reference)
- [1. Monitoring and Moderation](#1-monitoring-and-moderation)
- [2. Operator Recovery Journeys](#2-operator-recovery-journeys)
- [3. Testing & Continuous Delivery](#3-testing--continuous-delivery)
- [4. Deployment & Environment Configuration](#4-deployment--environment-configuration)
- [5. Observability & Debugging](#5-observability--debugging)
- [6. Platform Service Updates](#6-platform-service-updates)
- [Related Documentation](#related-documentation)

---

## Goals

- Describe operator-centric flows for keeping FireMUD healthy and compliant.
- Connect observability, moderation, and deployment workflows into clear journeys.
- Clarify how operator actions affect player and creator experiences.

---

## Quick Reference

- [Monitoring and Moderation](#1-monitoring-and-moderation) – Watch the system and enforce rules.
- [Operator Recovery Journeys](#2-operator-recovery-journeys) – Recover from incidents and restore services.
- [Testing & Continuous Delivery](#3-testing--continuous-delivery) – Validate changes and ship them safely.
- [Deployment & Environment Configuration](#4-deployment--environment-configuration) – Manage local and production environments.
- [Observability & Debugging](#5-observability--debugging) – Use logs, metrics, and traces for troubleshooting.
- [Platform Service Updates](#6-platform-service-updates) – Upgrade FireMUD services via CI/CD.

Content and world-design changes are described in the [Creator Journeys](./user-journeys-creators.md). Player-facing account and gameplay flows are described in the [Player Journeys](./user-journeys-players.md).

---

## 1. Monitoring and Moderation

Operators monitor the game and enforce rules using the [Logging & Admin Service](./microservices/logging-admin-service/README.md). Logs, metrics, and traces flow into **Elasticsearch**, **Prometheus**, and **Jaeger** as described in [Logging & Monitoring](./system-architecture-logging-monitoring.md) and [Tracing](./system-architecture-tracing.md). For usage examples see the [Analytics Dashboards](./microservices/logging-admin-service/analytics-dashboards.md).

The service also exposes moderation tools such as reports, bans, and runtime feature toggles. Administrators review logs and configure these options through the [Admin UI](./microservices/logging-admin-service/admin-ui.md). Policies are summarized in the [Moderation Policies](./microservices/logging-admin-service/moderation-policies.md) document. Complex moderation workflows are coordinated using saga patterns as described in [Transaction Strategies](./system-architecture-transactions.md). Review these guides alongside the [Security Architecture](./system-architecture-security.md) to ensure moderation actions follow platform rules.

Canonical moderation journey:

1. **Player Report Arrives** – A player submits an in-game report, which the Logging & Admin Service records with tenant, realm, subject, and supporting evidence.
2. **Operator Reviews Evidence** – Moderators inspect the report, associated chat logs, and related account/gameplay context.
3. **Operator Chooses Enforcement Type** – Enforcement uses the canonical taxonomy from the system overview:
   - `account_security_ban` for account-wide auth/security suspension,
   - `gameplay_ban` for tenant gameplay denial,
   - `chat_mute` / `chat_ban` for communication restrictions.
4. **Owning Service Enforces** – Account Service, Game Session Service, or Social & Groups Service applies the effect while Logging & Admin remains the policy/audit entry point.
5. **Player Sees Specific Outcome** – The affected user sees canonical account, gameplay, or chat errors rather than a generic moderation failure.

```plaintext
Operator → Logging & Admin Service → Observability Stack / Admin UI
```

Chat and social flows that feed into moderation are described in [Social Interaction](./user-journeys-players.md#4-social-interaction).

Admin and operator access to tenant-scoped tools is governed by the JWT-based role and tenant model. See the [Tenant Authorization Contract](./system-architecture-authentication.md#tenant-authorization-contract) and [Multi-Tenancy](./system-architecture-multi-tenancy.md#identity--tenant-model) for how `globalRoles` and `scopedRoles` determine which tenants an operator can act on.

---

## 2. Operator Recovery Journeys

When issues occur, operators follow the [Operational Runbooks](./system-architecture-runbooks.md) to restore services. See [Backup & Disaster Recovery](./system-architecture-backup-recovery.md) for database snapshots and Redis persistence. From a player perspective, Redis incidents typically surface as **short-term effects**: recent commands for a region may be lost or delayed after a crash/failover, and some sessions may be dropped and require re-login, but long-term character and world state remain intact because they live in PostgreSQL. PostgreSQL incidents instead manifest as longer outages or rollbacks to the last database snapshot; once recovered, Redis repopulates from the restored database and gameplay continues from that snapshot’s state.

```plaintext
Admin → Runbooks → Kubernetes / Docker → Services Restored
```

---

## 3. Testing & Continuous Delivery

1. **Pre‑Commit Hooks** – Developers run `pre-commit run --all-files` or `./gradlew check` to format code and execute tests before pushing.
2. **Run Tests** – Each microservice executes unit and integration tests. See [Testing Strategy](./system-architecture-testing.md).
3. **CI/CD Pipeline** – Changes are built and deployed via GitHub Actions as described in [CI/CD Pipeline](./system-architecture-cicd.md). Workflows run unit tests, CodeQL security scans, open source license checks, and generate ERD diagrams before publishing Docker images and documentation.
4. **Database Migrations** – Schemas are migrated with Flyway on startup; see [Database Migrations](./system-architecture-database-migrations.md).

```plaintext
GitHub → CI Workflow → Container Registry → Kubernetes
```

These steps apply both to game-specific services and to the FireMUD platform itself. Content-focused update flows are described in [Patch and Update a Live Game](./user-journeys-creators.md#5-patch-and-update-a-live-game).

For tenant-scoped runtime lifecycle, operators are not the routine owners of creator game launches. `tenantAdmin` handles normal realm launch, playtest-fork creation, cutover, script-patch pinning, and rollback for that tenant. Operators retain break-glass and platform-wide override authority when incidents, abuse, or entitlement failures require intervention.

---

## 4. Deployment & Environment Configuration

FireMUD can be deployed locally using **Docker Compose** or to production via **Kubernetes**:

1. **Local Development** – Run `./gradlew devUp` to start all services with Docker Compose. Configuration values are loaded from an `.env` file. See [Deployment Environments](./infrastructure/deployment-environments.md).
2. **Production** – Kubernetes manifests load configuration through `ConfigMap` and `Secret` objects. Refer to [Environment & Secrets Management](./infrastructure/environment-and-secrets.md) for details.
3. **Infrastructure Overview** – Shared networking and deployment patterns are summarized in [Infrastructure Overview](./infrastructure/README.md).

```plaintext
Developer / Operator → Docker Compose / Kubernetes → Running Services
```

---

## 5. Observability & Debugging

Operators troubleshoot issues and tune performance using the centralized [Logging & Admin Service](./microservices/logging-admin-service/README.md) and observability stack:

1. **Log Aggregation** – Fluent Bit forwards service logs to **Elasticsearch**, which are explored via **Kibana**. See [Logging & Monitoring](./system-architecture-logging-monitoring.md).
2. **Metrics & Alerts** – **Prometheus** scrapes metrics, sends alerts through **Alertmanager**, and **Grafana** visualizes dashboards such as the [Service Overview](../observability/grafana/service-overview.json).
3. **Tracing** – Distributed traces are sent to **Jaeger** via the OpenTelemetry Collector as described in [Tracing](./system-architecture-tracing.md).
4. **Kibana Dashboards** – Pre-built views like the [Log Volume dashboard](../observability/kibana/log-volume.json) help monitor logging rates.
5. **Operator Dashboards** – Additional Grafana and Kibana views are described in the [Analytics Dashboards](./microservices/logging-admin-service/analytics-dashboards.md) document.

```plaintext
Service Logs → Elasticsearch → Kibana
Metrics → Prometheus → Grafana / Alertmanager
Traces → Jaeger
```

Common troubleshooting steps are documented in the [Operational Runbooks](./system-architecture-runbooks.md).

---

## 6. Platform Service Updates

Updating FireMUD itself follows the standard CI/CD workflow:

1. **Build New Images** – GitHub Actions compiles each microservice and pushes updated container images. See the [CI/CD Pipeline](./system-architecture-cicd.md).
2. **Restart Services** – Kubernetes rolls the new images into the cluster, restarting pods one by one.
3. **Apply Schema Migrations** – Each service runs Flyway on startup to migrate its database before the Spring application launches.
4. **Verify Health** – Operators monitor metrics and logs to ensure the deployment succeeded.

```plaintext
GitHub → Container Registry → Kubernetes → Service Startup (Flyway)
```

These flows complement the architecture diagrams in [System Architecture Overview](./system-architecture-overview.md). For game-specific content updates and hotfixes, see [Patch and Update a Live Game](./user-journeys-creators.md#5-patch-and-update-a-live-game).

---

## Related Documentation

- [CI/CD Pipeline](./system-architecture-cicd.md)
- [Database Migrations](./system-architecture-database-migrations.md)
- [Deployment Environments](./infrastructure/deployment-environments.md)
- [Environment & Secrets Management](./infrastructure/environment-and-secrets.md)
- [Infrastructure Overview](./infrastructure/README.md)
- [Logging & Monitoring](./system-architecture-logging-monitoring.md)
- [Moderation Policies](./microservices/logging-admin-service/moderation-policies.md)
- [Operational Runbooks](./system-architecture-runbooks.md)
- [Security Architecture](./system-architecture-security.md)
- [System Architecture Overview](./system-architecture-overview.md)
- [Testing Strategy](./system-architecture-testing.md)
- [Tracing](./system-architecture-tracing.md)
