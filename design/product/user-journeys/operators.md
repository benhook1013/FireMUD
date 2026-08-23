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
- **Safety and appeals:** The target fixed categories, owner-local enforcement, and bounded appeal case are defined in [Moderation Policies](../../architecture/microservices/logging-admin-service/moderation-policies.md). Current category persistence, owner commands, player notices, and appeal workflow are not implemented or proved. Account enforces `platform_access_ban` at platform-access boundaries and separately owns `account_security_lock` security/recovery; Logging & Admin owns moderation appeals, while Game Session and Social & Groups remain enforcement owners for their categories.
- **Redis/PostgreSQL recovery:** Current recovery uses the fail-closed operator fallback with affected workloads fenced; the durable controller-backed Redis reset and PostgreSQL restore/reopen sequence is target-only. See [Redis Reset & Recovery](../../architecture/system-architecture-redis-reset-and-recovery.md#current-operator-fallback), [Backup & Disaster Recovery](../../architecture/system-architecture-backup-recovery.md#recovery-controller-continuation), and the [Platform Operations tracker](../../project-management/implementation-tracking/platform-operations-and-delivery.md#capability-status).
- **Grant revocation:** Grant records and partial revocation exist today. Account's target grant, expiry, revision/tombstone, tenant-admin, and break-glass contract is canonical in [Account API Contracts](../../architecture/microservices/account-service/api-contracts.md#account-owned-playtest-grant-contract). The local operator consequence is targeted termination of the affected fork bindings and denial of discovery, reconnect, and `PLAY`; unrelated account access remains available. Expiry and lifecycle automation remain gaps.
- **Session termination:** Gameplay takeover, `LOGOUT`, and transport idle-close handling are current; Account control-plane revocation/logout, full expiry convergence, and post-logout replay cleanup remain partial. Target termination distinguishes logout from suspension and closes revoked active sockets so the affected session cannot resume; see the [Player Access and Session tracker](../../project-management/implementation-tracking/player-access-and-session.md#capability-status) and [Session Behavior](../../architecture/system-architecture-session-behavior.md#active-socket-auth-revocation).
- **Realm lifecycle and replacement:** Target operator diagnostics distinguish preparation, pre-activation failure, termination, and cleanup completion while retaining the stable playable-state namespace alongside the active runtime. World Management lifecycle state and epoch remain admission authority; Temporal reports coordination progress. A failed preparation is not proof of cleanup, and termination is complete only after every owner in the cleanup attempt's frozen required-owner snapshot at its frozen ownership-registry revision acknowledges cleanup. Current operator readback remains partial; see [ADR 0122](../../architecture/decisions/adr-0122-stable-playable-state-namespaces-for-runtime-replacement.md), [ADR 0123](../../architecture/decisions/adr-0123-database-authoritative-temporal-coordinated-world-lifecycle.md), and the [Platform Operations tracker](../../project-management/implementation-tracking/platform-operations-and-delivery.md#capability-status).
- **Script-transition operations:** Game Session currently persists the pin and exposes convergence reads but does not yet provide the target append-only request-idempotent history or direct history read; the live handoff lacks `scriptPinEpoch` and cannot yet reject same-version work from an older epoch; and Automation does not yet persist and propagate the captured `(pluginActivationEpoch, lifecycleRevision)` pair through every plugin work/status/handoff/retry/replay/recovery surface. These target history/readback and exact-fence capabilities remain unimplemented and unproved. Use [Scripting & Automation: Control Plane Operations](../../architecture/system-architecture-scripting-control-plane-operations.md), [Scripting & Automation: Rollout and Rollback](../../architecture/system-architecture-scripting-rollout-and-rollback.md), and the [Scripting & Automation: Observability Contract](../../architecture/system-architecture-scripting-observability-contract.md) for the canonical checks, epoch distinction, notification recovery, and sequencing. Current operator-visible gaps also include stale executor `ONLOAD_RUNNING` work not yet being reclaimed or terminalized; for current stale-readiness incidents, use the admin-authorized read-only patch-status and drain diagnostics, keep the readiness barrier in force, and do not infer reclaim, terminalization, or replay from age. The target Automation readiness-owner fenced path remains unimplemented; see [Stale `onLoad` execution](../../architecture/system-architecture-scripting-operations-cookbook.md#stale-onload-execution) for the current-safe response.

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

The current investigation surface includes internal report persistence and read/investigation tooling; runtime feature-toggle paths exist but remain externally gated. Fixed-category owner-local restriction actions and bounded appeals are target/partial, not current operator controls. Administrators review logs and configure available options through the [Admin UI](../../architecture/microservices/logging-admin-service/admin-ui.md). Policies are summarized in the [Moderation Policies](../../architecture/microservices/logging-admin-service/moderation-policies.md) document. Review these guides alongside the [Security Architecture](../../architecture/system-architecture-security.md) to ensure moderation actions follow platform rules.

**Target moderation journey (public player reporting is target behavior):**

1. **Player Report Arrives (target behavior)** – Once public player ingress exists, a player submits an in-game report, which the Logging & Admin Service records with tenant, realm, subject, and supporting evidence.
2. **Operator Reviews Evidence** – Moderators inspect the report, associated chat logs, and related account/gameplay context.
3. **Operator Chooses Enforcement Type** – The moderator selects exactly one punitive fixed category and explicit scope: `platform_access_ban`, `gameplay_ban`, `chat_mute`, or `chat_ban`. A suspected compromise is not a moderator choice: the operator hands the security evidence to Account's security-policy/recovery workflow, which owns any `account_security_lock`. Unqualified legacy values such as `ban` are rejected.
4. **Owning Service Enforces** – Account (for `platform_access_ban`), Game Session, or Social & Groups commits its own monotonic revision and enforces locally; Logging & Admin records policy intent, cases, appeal evidence, and audit and does not mutate foreign state. Account separately owns `account_security_lock` policy/recovery.
5. **Player Sees Specific Outcome** – The affected user sees the category-specific safe notice. Eligible severe or long-lived restrictions expose an opaque browser handoff for appeal/status; filing does not suspend enforcement.
6. **Appeal Is Reviewed** – Logging & Admin moves an eligible case through `SUBMITTED -> UNDER_REVIEW -> DECIDED`. `UPHELD` changes nothing; `MODIFIED` or `OVERTURNED` sends a newer digest-bound command to the existing owner. Original records and later unrelated restrictions remain intact.

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

For tenant-scoped runtime lifecycle, operators are not the routine owners of creator realm launches. `tenantAdmin` handles normal realm launch, playtest initialization/reset/expiry, cutover, script-patch pinning, rollback, admission close, drain, and retirement for that tenant. The canonical authority and gate contract is [Tenant-Owned Runtime Lifecycle Authority](../../architecture/system-architecture-versioning-runtime.md#tenant-owned-runtime-lifecycle-authority). Operators retain only the distinct audited `platformAdmin` break-glass path for bounded safety, abuse, billing, or recovery actions; it cannot impersonate a tenant or bypass publication, entitlement, quota, compatibility, readiness, integrity, or fencing gates.

For script-transition incidents, operator tooling should expose the authoritative Game Session `scriptPatchVersion`/`scriptPinEpoch` tuple and append-only history alongside Automation readiness, projection freshness, plugin state, timer reconciliation, and stage-aware dead-letter outcomes. For plugin-backed incidents, correlate `pluginId`, `pluginVersionId`, `bindingId`, captured `(pluginActivationEpoch, lifecycleRevision)`, current scoped `(pluginActivationEpoch, lifecycleRevision)` comparison evidence, lifecycle state, and bounded fence reason in audit/readback diagnostics. `scriptPinEpoch` is the Game Session-owned script-selection epoch; `pluginActivationEpoch` is the Automation-owned per-instance plugin-lifecycle epoch, and they must remain distinct. **Target:** Game Session append-only rollout history and the direct history/readback surface, together with readback and runtime fences carrying the exact script-pin tuple and plugin lifecycle-fence pair, are authoritative target behavior. Current implementation gaps and the current-safe stale-readiness response are maintained in [Implementation Status](#implementation-status). In target state, Automation reclaims or terminalizes stale execution with one terminal audit record using `finalStage=DSL_EVAL`, `finalOutcome=canceled`, and `finalReason=stale_execution_fenced`, without DSL re-entry or a new `scriptEventId` or step identity. In the target state, dashboards show this state but do not authorize a stale-pin override.

In the target state, break-glass and platform-wide authority do not bypass `pin_state_unavailable` or exact `(scriptPatchVersion, scriptPinEpoch)` admission; recovery restores authoritative Game Session reads or projection delivery, or performs explicit Game Session repin/rollback sequencing as applicable.

At minimum, operator tooling should expose enough fork metadata to reason about support and incident response safely: realm type, source snapshot identity, target build (`versionId` / `scriptPatchVersion`), reset/expiry status, and whether the fork is currently player-admissible. This metadata should come from the same tenant runtime/control-plane surfaces that creators use for fork lifecycle and realm routing, rather than from an operator-only shadow model.
Operator tooling should expose the Account-owned grant state, revision, effective expiry, tombstone/revocation outcome, exact fork lifecycle, and active-binding termination status without maintaining a shadow grant store. It should support revoking player visibility/admission for a fork without deleting the fork, preserving fork-local state and audit history. Account's grant contract defines the exact mutation and replay semantics; the local operator outcome is targeted termination, while unrelated account access remains available. When a creator or operator wants a friendly scheduled playtest ending, they close and drain the realm first and revoke grants afterward; access revocation itself is not overloaded as a drain request. Gate failures block start/expansion but leave closure, cleanup, repair, and audit reachable under the lifecycle owner contract.

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
