# FireMUD User Journeys: Operators

This guide summarizes typical workflows for administrators, moderators, and platform operators. It focuses on monitoring, moderation, operational recovery, deployment, CI/CD, and platform-level updates. Use it alongside the [Architecture Overview](../../architecture/README.md), the [System Architecture Overview](../../architecture/system-architecture-overview.md), the [System Architecture Diagram](../../architecture/system-architecture-diagram.md), and the [System Context Diagram](../../architecture/system-context-diagram.md).

For other personas, see:

- [Player Journeys](./players.md)
- [Creator Journeys](./creators.md)
- [User Journeys Hub](./overview.md)

These journeys define observable product behavior and user-facing outcomes; technical contracts remain in the linked architecture documents.

## Implementation Status

The journeys below keep target operator behavior primary. Current and target boundaries are:

- **Moderation ingress:** Current report creation is internal service-to-service `CreateReport` only until public/player ingress exists; that ingress is currently unavailable. The target report and operator-write contracts are defined in the [Logging & Admin API status](../../architecture/microservices/logging-admin-service/api-contracts.md#implementation-status) and the [Platform Operations tracker](../../project-management/implementation-tracking/platform-operations-and-delivery.md#capability-status).
- **Redis/PostgreSQL recovery:** Current recovery uses the fail-closed operator fallback with affected workloads fenced; the durable controller-backed Redis reset and PostgreSQL restore/reopen sequence is target-only. See [Redis Reset & Recovery](../../architecture/system-architecture-redis-reset-and-recovery.md#current-operator-fallback), [Backup & Disaster Recovery](../../architecture/system-architecture-backup-recovery.md#recovery-controller-continuation), and the [Platform Operations tracker](../../project-management/implementation-tracking/platform-operations-and-delivery.md#capability-status).
- **Grant revocation:** Grant records and partial revocation exist today. Target revocation removes only the affected realm access, terminates its connected sessions, and blocks discovery, reconnect, and `PLAY` without removing unrelated account access; expiry and lifecycle automation remain target gaps. See the [Player Access and Session tracker](../../project-management/implementation-tracking/player-access-and-session.md#capability-status) and [Session Behavior](../../architecture/system-architecture-session-behavior.md#multi-client-behavior-and-session-takeover).
- **Session termination:** Gameplay takeover, `LOGOUT`, and transport idle-close handling are current; Account control-plane revocation/logout, full expiry convergence, and post-logout replay cleanup remain partial. Target termination distinguishes logout from suspension and closes revoked active sockets so the affected session cannot resume; see the [Player Access and Session tracker](../../project-management/implementation-tracking/player-access-and-session.md#capability-status) and [Session Behavior](../../architecture/system-architecture-session-behavior.md#active-socket-auth-revocation).

## Table of Contents

- [Implementation Status](#implementation-status)
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

Content and world-design changes are described in the [Creator Journeys](./creators.md). Player-facing account and gameplay flows are described in the [Player Journeys](./players.md).

---

## 1. Monitoring and Moderation

Operators monitor the game and enforce rules using the [Logging & Admin Service](../../architecture/microservices/logging-admin-service/README.md). Logs, metrics, and traces flow into **Elasticsearch**, **Prometheus**, and **Jaeger** as described in [Logging & Monitoring](../../architecture/system-architecture-logging-monitoring.md) and [Tracing](../../architecture/system-architecture-tracing.md). For usage examples see the [Analytics Dashboards](../../architecture/microservices/logging-admin-service/analytics-dashboards.md).

The service provides moderation and investigation surfaces such as reports, bans, and runtime feature toggles. Administrators review logs and configure these options through the [Admin UI](../../architecture/microservices/logging-admin-service/admin-ui.md). Policies are summarized in the [Moderation Policies](../../architecture/microservices/logging-admin-service/moderation-policies.md) document. Review these guides alongside the [Security Architecture](../../architecture/system-architecture-security.md) to ensure moderation actions follow platform rules.

**Target moderation journey (public player reporting is target behavior):**

1. **Player Report Arrives (target behavior)** – Once public player ingress exists, a player submits an in-game report, which the Logging & Admin Service records with tenant, realm, subject, and supporting evidence.
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

Chat and social flows that feed into moderation are described in [Social Interaction & Safety](./players.md#5-social-interaction--safety).

Admin and operator access to tenant-scoped tools is governed by the JWT-based role and tenant model. See the [Tenant Authorization Contract](../../architecture/system-architecture-authentication.md#tenant-authorization-contract) and [Multi-Tenancy](../../architecture/system-architecture-multi-tenancy.md#identity--tenant-model) for how `globalRoles` and `scopedRoles` determine which tenants an operator can act on.

---

## 2. Operator Recovery Journeys

When issues occur, operators follow the [operations documentation](../../operations/README.md) to restore services. See [Backup & Disaster Recovery](../../architecture/system-architecture-backup-recovery.md) for database snapshots and Redis persistence. From a player perspective, Redis incidents typically surface as **short-term effects**: recent commands for a region may be lost or delayed after a crash/failover, and some sessions may be dropped and require re-login, but long-term character and world state remain intact because they live in PostgreSQL. A PostgreSQL rewind is a longer environment-wide outage: traffic remains quarantined, sessions are invalidated, and controlled recovery checks plus explicit reopen must complete before players return to the restored timeline.

```plaintext
Admin → Runbooks → Kubernetes / Docker → Services Restored
```

---

## 3. Testing & Continuous Delivery

1. **Pre‑Commit Hooks** – Developers run `pre-commit run --all-files` or `./gradlew check` to format code and execute tests before pushing.
2. **Run Tests** – Each microservice executes unit and integration tests. See [Testing Strategy](../../architecture/system-architecture-testing.md).
3. **CI/CD Pipeline** – Changes are built and deployed via GitHub Actions as described in [CI/CD Pipeline](../../architecture/system-architecture-cicd.md). Workflows run unit tests, CodeQL security scans, open source license checks, and generate ERD diagrams before publishing Docker images and documentation.
4. **Database Migrations** – Schemas are migrated with Flyway on startup; see [Database Migrations](../../architecture/system-architecture-database-migrations.md).

```plaintext
GitHub → CI Workflow → Container Registry → Kubernetes
```

These steps apply both to game-specific services and to the FireMUD platform itself. Content-focused update flows are described in [Patch and Update a Live Game](./creators.md#5-patch-and-update-a-live-game).

For tenant-scoped runtime lifecycle, operators are not the routine owners of creator realm launches. `tenantAdmin` handles normal realm launch, playtest-fork creation, cutover, script-patch pinning, and rollback for that tenant. Operators retain break-glass and platform-wide override authority when incidents, abuse, or entitlement failures require intervention.

For script-transition incidents, **target-state** operator tooling shows the authoritative Game Session pin/epoch and rollout history alongside Automation readiness, projection freshness, plugin state, timer reconciliation, and stage-aware dead-letter outcomes. Target-state Automation blocks new scripted admission when its observed Game Session tuple is missing, stale, or mismatched; ordinary non-script gameplay admission and ticks continue while their own dependencies and fences are healthy. Current implementation note: the live handoff lacks `scriptPinEpoch` and cannot yet reject same-version work from an older epoch at that boundary, and stale executor `ONLOAD_RUNNING` work is not yet reclaimed or terminalized, so these target-state displays and enforcement rules must not be treated as live proof. Target stale execution records the Automation-owned terminal reason `stale_execution_fenced` without DSL re-entry or a new `scriptEventId` or step identity. Dashboards show this state but do not authorize a stale-pin override. Routine rollback is therefore visible as scoped Automation convergence and asynchronous cleanup rather than an assumed whole-game pause.

At minimum, operator tooling should expose enough fork metadata to reason about support and incident response safely: realm type, source snapshot identity, target build (`versionId` / `scriptPatchVersion`), reset/expiry status, and whether the fork is currently player-admissible. This metadata should come from the same tenant runtime/control-plane surfaces that creators use for fork lifecycle and realm routing, rather than from an operator-only shadow model.
Operator tooling should also support revoking player visibility/admission for a fork without deleting the fork outright, so support, moderation, or incident-response workflows can hide a fork temporarily while preserving its fork-local state and audit history. **Target behavior:** revoking or expiring a realm grant removes only that account's authority for the affected fork, terminates its connected fork sessions, and blocks subsequent discovery, reconnect, and `PLAY` for that realm. Account-wide bootstrap and access to unrelated tenants, worlds, and realms remain available. When a creator or operator wants a friendly scheduled playtest ending, they close and drain the realm first and revoke grants afterward; access revocation itself is not overloaded as a drain request.

---

## 4. Deployment & Environment Configuration

FireMUD can be deployed locally using **Docker Compose** or to production via **Kubernetes**:

1. **Local Development** – Run `./gradlew devUp` to start all services with Docker Compose. Configuration values are loaded from an `.env` file. See [Deployment Environments](../../architecture/infrastructure/deployment-environments.md).
2. **Production** – Kubernetes manifests load configuration through `ConfigMap` and `Secret` objects. Refer to [Environment & Secrets Management](../../architecture/infrastructure/environment-and-secrets.md) for details.
3. **Infrastructure Overview** – Shared networking and deployment patterns are summarized in [Infrastructure Overview](../../architecture/infrastructure/README.md).

```plaintext
Developer / Operator → Docker Compose / Kubernetes → Running Services
```

---

## 5. Observability & Debugging

Operators troubleshoot issues and tune performance using the centralized [Logging & Admin Service](../../architecture/microservices/logging-admin-service/README.md) and observability stack:

1. **Log Aggregation** – Fluent Bit forwards service logs to **Elasticsearch**, and operators explore them in **Kibana**. See [Logging & Monitoring](../../architecture/system-architecture-logging-monitoring.md).
2. **Metrics & Alerts** – **Prometheus** scrapes metrics, sends alerts through **Alertmanager**, and **Grafana** visualizes dashboards such as the [Service Overview](../../observability/grafana/service-overview.json).
3. **Tracing** – Distributed traces are sent to **Jaeger** via the OpenTelemetry Collector as described in [Tracing](../../architecture/system-architecture-tracing.md).
4. **Kibana Dashboards** – Pre-built views like the [Log Volume dashboard](../../observability/kibana/log-volume.json) help monitor logging rates.
5. **Operator Dashboards** – Additional Grafana and Kibana views are described in the [Analytics Dashboards](../../architecture/microservices/logging-admin-service/analytics-dashboards.md) document.

```plaintext
Service Logs → Elasticsearch → Kibana
Metrics → Prometheus → Grafana / Alertmanager
Traces → Jaeger
```

Common troubleshooting steps are documented in the [operations documentation](../../operations/README.md).

---

## 6. Platform Service Updates

Updating FireMUD itself follows the standard CI/CD workflow:

1. **Build New Images** – GitHub Actions compiles each microservice and pushes updated container images. See the [CI/CD Pipeline](../../architecture/system-architecture-cicd.md).
2. **Restart Services** – Kubernetes rolls the new images into the cluster, restarting pods one by one.
3. **Apply Schema Migrations** – Each service runs Flyway on startup to migrate its database before the Spring application launches.
4. **Verify Health** – Operators monitor metrics and logs to ensure the deployment succeeded.

```plaintext
GitHub → Container Registry → Kubernetes → Service Startup (Flyway)
```

These flows complement the architecture diagrams in [System Architecture Overview](../../architecture/system-architecture-overview.md). For game-specific content updates and hotfixes, see [Patch and Update a Live Game](./creators.md#5-patch-and-update-a-live-game).

---

## Related Documentation

- [CI/CD Pipeline](../../architecture/system-architecture-cicd.md)
- [Database Migrations](../../architecture/system-architecture-database-migrations.md)
- [Deployment Environments](../../architecture/infrastructure/deployment-environments.md)
- [Environment & Secrets Management](../../architecture/infrastructure/environment-and-secrets.md)
- [Infrastructure Overview](../../architecture/infrastructure/README.md)
- [Logging & Monitoring](../../architecture/system-architecture-logging-monitoring.md)
- [Moderation Policies](../../architecture/microservices/logging-admin-service/moderation-policies.md)
- [Operations documentation](../../operations/README.md)
- [Security Architecture](../../architecture/system-architecture-security.md)
- [System Architecture Overview](../../architecture/system-architecture-overview.md)
- [Testing Strategy](../../architecture/system-architecture-testing.md)
- [Tracing](../../architecture/system-architecture-tracing.md)
