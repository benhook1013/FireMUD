# Logging & Admin Service Runtime and Data

This document defines the Logging & Admin Service runtime model, availability partitioning, control-plane responsibilities, and persistent data ownership.

## Architecture and Design Notes

Logging & Admin uses the common stack outlined in [Logging & Monitoring](../../system-architecture-logging-monitoring.md) and exposes admin endpoints for reviewing logs, managing coordination health, and applying moderation actions. It consumes Kibana and Grafana APIs to embed existing dashboards within the admin interface.

## Target-State Runtime Contract

Logging & Admin is the operator-facing policy, audit, and observability coordination plane. It owns moderation policy definitions, operator intent, audit history, quota/limit override UX, control-plane inspection, and the availability boundary between core operator actions and observability-backed experiences. It does not own gameplay coordination state, runtime feature-flag truth, entity state, or another service's persistence, and it must invoke those owning services through their canonical contracts.

The target service preserves two independently degradable partitions:

- core control-plane actions remain usable when search, metrics, tracing, dashboards, or alerting backends are unavailable;
- observability-backed searches, dashboards, traces, and alert investigations degrade explicitly without taking the core control plane down.

Tick and coordination remediation remains a Game Session responsibility. Logging & Admin may display health, choose an allowed operation, request a bounded remediation, and record audit evidence, but Game Session remains the authority for region leases, pause/resume, reset, and coordination mutation. Any regional or aggregate operation must carry the owning service's explicit scope and fencing evidence rather than being inferred from an operator UI selection.

## Implementation Status

The current shipped scope is narrower than this target contract:

- The service exposes core admin/moderation, report, saga-inspection, and coordination-health surfaces, plus observability-backed integrations; it does not yet provide the complete target control-plane workflow for every recovery, regional, or aggregate operation.
- Current tick pause/resume support is the shipped `<tenantId, gameInstanceId>` boundary. Regional pause/resume and the explicit aggregate scope/fencing contract remain target-state work tracked in [Game Session runtime and tick coordination](../../../project-management/implementation-tracking/game-session-runtime-and-tick-coordination.md#capability-status).
- Logging & Admin consumes Game Session health and requests Game Session-owned control APIs; it does not directly mutate Redis or runtime coordination state.
- Moderation policy, audit data, quota/limit override intent, and operator history remain owned here, while Account, Game Session, and Social & Groups own enforcement and runtime mutation.
- The core/observability availability split is a design contract; exact independent deployment and pool isolation remain implementation obligations rather than a claim that every backend integration is already isolated in production.

In addition to log and moderation tooling, the service acts as a control-plane coordinator for tick and coordination health:

- Consumes metrics and health information published by the Game Session Service (for example, per-region status such as `HEALTHY`, `DEGRADED`, or `COORDINATION_UNTRUSTWORTHY`).
- Exposes admin APIs and UI controls to:
  - pause or resume tick execution for the shipped `<tenantId, gameInstanceId>` boundary; regional pause/resume controls remain target-state behavior and are tracked against the current Game Session implementation status in [Game Session runtime and tick coordination](../../../project-management/implementation-tracking/game-session-runtime-and-tick-coordination.md#capability-status); and
  - request scoped coordination remediation through Game Session control APIs and operator runbooks in [Redis Operations & Migrations](../../system-architecture-redis-operations.md).
- Implements guarded automation that:
  - automatically pauses ticks and marks regions as unhealthy when dual-leader or split-brain signals are detected; and
  - may request safe, narrow remediation through Game Session-owned control APIs without requiring an operator to be present, while still emitting audit events for every action. Unattended requests use exact Logging and Admin mTLS identity plus an Account-validated, versioned automation-policy authorization reference; they never synthesize a human `control-ui` token or operator identity.

Game Session remains the only service allowed to mutate gameplay coordination state or execute tick pause/resume behavior. Logging & Admin owns operator UX, automation policy, and audit only; it does not become the runtime state owner for remediation.

Operator and support diagnostics do not impersonate a player, attach to a live player session, or observe gameplay through a hidden actor. They use purpose-built, minimized support-safe reads, logs, dashboards, reports, moderation records, and explicit control-plane operations. Break-glass controls remain separately authorized and audited and must not create a player actor, gameplay session, or tenant-scoped gameplay capability.

- gRPC connections to this service require mTLS. JWT validation is required for admin or user-facing endpoints; internal gameplay and system calls are authenticated solely via mTLS.
- The security model uses JWT roles plus network-layer isolation: admin endpoints are reachable only through Gateway/internal management surfaces and namespace/network-policy controls, not direct public exposure.
- All admin APIs are secured via role-based access control integrated with the Account Service.
- Moderation data and log indices include a `tenantId` field so administrators only see information for the games they manage. Cross-tenant queries are rejected per the [Multi-Tenancy](../../system-architecture-multi-tenancy.md) strategy.
- The service utilizes the [Shared Libraries](../../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.

## Availability Partitioning

This service has two intentionally different availability classes:

- Core operator control plane: security-sensitive operator APIs whose availability must not depend on observability backends.
- Observability-backed experiences: embedded dashboards, log search, metric exploration, traces, and alert-centric investigations.

The core operator control plane must remain available when Elasticsearch, Prometheus, Jaeger, Grafana, Kibana, or Alertmanager are degraded. Implementations should preserve this with independent readiness/degradation behavior, resource isolation, and defensive timeouts/circuit breakers around observability backends.

The architecture treats these as two runtime partitions even when they are delivered from one deployable:

- Core control-plane endpoints include moderation actions, feature-flag controls, reports, saga inspection, and live tick-remediation pause/resume APIs. These paths must not block on Elasticsearch, Prometheus, Jaeger, Grafana, Kibana, or Alertmanager for request success. Quota override and broader remediation are not current endpoints and remain coverage drift.
- Observability-backed endpoints include log search, embedded dashboards, traces, metric exploration, and alert investigation views. These paths may degrade independently or return explicit backend-unavailable states.
- Readiness and degradation reporting must distinguish these partitions so an observability outage does not mark the entire operator service unavailable.
- Thread pools, connection pools, and timeout budgets for observability integrations must be isolated from the core control plane so expensive search/dashboard failures cannot starve moderation or remediation requests.
- If a future implementation cannot preserve those guarantees inside one service boundary, the architecture should split the deployable into separate operator-control and observability surfaces rather than weakening the availability rule.

## Script Patch and Plugin Control-Plane Coordination

Logging & Admin provides the operator-facing audit and coordination layer around script patch and plugin changes:

- operators review automation or plugin state here and then invoke the owning control-plane APIs exposed by Game Design, Automation & Scripting, and Game Session;
- audit trails and operator intent for those changes are recorded here; and
- runtime mutation authority remains with the owning domain services rather than with Logging & Admin itself.

Logging & Admin does not write to Redis directly and does not define a competing script/plugin state-mutation API. It coordinates operator UX and audit around the documented service-owned APIs so operators can explain why automation behavior changed.

## Data Model

- `log_events` stores log data and is mirrored into Elasticsearch indexes for search.
- `moderation_actions` records bans and warnings with timestamps and includes a `tenant_id` column.
- `player_reports` stores abuse and bug reports with a `tenant_id` column.
- Runtime feature-flag truth is owned by Game Session. Logging & Admin records operator intent and audit context for feature-flag requests, then forwards the mutation to Game Session rather than maintaining a competing `feature_flag` runtime table.

## Moderation Workflow

- Operators review flagged logs through the web UI.
- Actions such as bans or warnings are issued via secured API calls.
- Enforcement follows the ban taxonomy:
  - `account_security_ban` events are applied by Account Service.
  - `gameplay_ban` events are enforced by Game Session Service.
  - `chat_mute` and `chat_ban` events are enforced by Social & Groups Service.

All moderation actions are audit-recorded for compliance.

## Saga Dashboard

The service exposes `/sagas` and `/sagas/{id}/steps` endpoints for operators to inspect short synchronous orchestration coordinated via the shared Saga library. Durable long-running workflow inspection belongs on the corresponding Temporal adopter read surfaces, not on the shared saga dashboard. The dashboard reads from the `saga_instance` and `saga_step` tables and publishes a `sagas.active` Prometheus gauge.

See [Transaction Strategies](../../system-architecture-transactions.md) for an overview of Saga usage across FireMUD.
