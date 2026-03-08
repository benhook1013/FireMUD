# Logging & Admin Service

## Overview

Centralized logging and administration tools for the platform. Collects log data from all services and provides moderation capabilities for game operators.

### Responsibilities

- Aggregate logs from every microservice via Fluent Bit sidecars and expose search APIs.
- Offer dashboards and search for operators and moderators by embedding Kibana and Grafana views.
- Define moderation policy, issue moderation actions, and keep auditable moderation records
- Record audit trails for feature flag changes and account events.
- Monitor **coordination and tick health** across tenants/regions and drive automated remediation where safe (for example, pausing ticks or triggering scoped coordination resets based on Redis/DB signals exposed by the Game Session Service).

## Architecture / Design Notes

Uses the common stack outlined in [Logging & Monitoring](../../system-architecture-logging-monitoring.md) and exposes admin endpoints for reviewing logs, managing coordination health, and applying moderation actions. It consumes Kibana and Grafana APIs to embed existing dashboards within the admin interface.

In addition to log and moderation tooling, the service acts as a **control-plane coordinator** for tick and coordination health:

- Consumes metrics and health information published by the Game Session Service (for example, per-region status such as `HEALTHY`, `DEGRADED`, or `COORDINATION_UNTRUSTWORTHY`).
- Exposes admin APIs and UI controls to:
  - Pause or resume tick execution for specific `<tenantId, regionId>` pairs.
  - Trigger **scoped coordination resets** using the runbooks in [Redis Operations & Migrations](../../system-architecture-redis-operations.md) (for example, per-region or per-deployment resets).
- Implements guarded automation that:
  - Automatically pauses ticks and marks regions as unhealthy when dual-leader or split-brain signals are detected.
  - Optionally performs safe, narrow coordination resets (such as single-region resets with clean tick ledgers) without requiring an operator to be present, while still emitting audit events for every action.
All admin APIs are secured via role-based access control integrated with the Account Service.

### Availability Partitioning

This service has two intentionally different availability classes:

- **Core operator control plane** – moderation actions, feature-flag requests, quota overrides, reports, saga inspection, and tick-remediation controls.
- **Observability-backed experiences** – embedded dashboards, log search, metric exploration, traces, and alert-centric investigations.

The core operator control plane must remain available when Elasticsearch, Prometheus, Jaeger, Grafana, Kibana, or Alertmanager are degraded. Implementations should preserve this with independent readiness/degradation behavior, resource isolation, and defensive timeouts/circuit breakers around observability backends.

The architecture treats these as two runtime partitions even when they are delivered from one deployable:

- **Core control-plane endpoints** include moderation actions, feature-flag and quota controls, reports, saga inspection, and tick-remediation APIs. These paths must not block on Elasticsearch, Prometheus, Jaeger, Grafana, Kibana, or Alertmanager for request success.
- **Observability-backed endpoints** include log search, embedded dashboards, traces, metric exploration, and alert investigation views. These paths may degrade independently or return explicit backend-unavailable states.
- Readiness and degradation reporting must distinguish these partitions so an observability outage does not mark the entire operator service unavailable.
- Thread pools, connection pools, and timeout budgets for observability integrations must be isolated from the core control plane so expensive search/dashboard failures cannot starve moderation or remediation requests.
- If a future implementation cannot preserve those guarantees inside one service boundary, the architecture should split the deployable into separate operator-control and observability surfaces rather than weakening the availability rule.

## Script Patch and Plugin Control Plane

Logging & Admin is the operator-facing control plane for:

- Enabling/disabling and draining automation scripts (through the Game Design and Automation & Scripting control-plane APIs).
- Enabling/disabling and rolling back plugins (as described in `design/architecture/microservices/game-design-service/modding-framework.md`).
- Repinning and rolling back `scriptPatchVersion` for running game instances by calling the Game Session control-plane APIs and following the rollback protocol specified in `design/architecture/system-architecture-scripting-control-plane-api.md`.

Logging & Admin does not write to Redis directly. It drives all runtime changes through documented service APIs and records audit trails so operators can explain why automation behavior changed.

- gRPC connections to this service require mTLS. JWT validation is required for admin or user-facing endpoints; internal gameplay and system calls are authenticated solely via mTLS.
- The security model uses JWT roles plus network-layer isolation: admin endpoints are reachable only through Gateway/internal management surfaces and namespace/network-policy controls, not direct public exposure.
- Moderation data and log indices include a `tenantId` field so administrators
  only see information for the games they manage. Cross-tenant queries are
  rejected per the [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
  strategy.
- Utilizes the [Shared Libraries](../../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.

## Key Features

- Central log search for entries collected via Fluent Bit sidecars.
- [Analytics dashboards](./analytics-dashboards.md) for operators, embedding Kibana and Grafana panels, including Telnet ingress views based on the TCP Proxy metrics described in [Logging & Monitoring](../../system-architecture-logging-monitoring.md) and the example Grafana snippets under `design/observability/grafana/`.
- Tools for banning or restricting accounts.
- [Role-based admin UI](./admin-ui.md) for moderators.
- Saga workflows coordinate moderation tasks across services. See [Transaction Strategies](../../system-architecture-transactions.md).
- [Moderation policies](./moderation-policies.md) including profanity filters.
- UI for requesting runtime feature flag overrides through owning domain control-plane APIs (Game Session enforces runtime behavior). See [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md).
- Audit trail for account actions and world changes.
- Transaction logs for purchases and subscription events.
- Captures failed login attempts and suspicious activity reported by the Game
  Session Service for operator review.
- Works with Saga workflows to record state changes across services. See
  [Transaction Strategies](../../system-architecture-transactions.md).
- Automated alerts for suspicious activity via Alertmanager.
- Real-time analytics on game performance.
- Optional TOTP-based two-factor authentication for administrator accounts.

When observability backends are unavailable, these features degrade differently:

- Core operator control-plane features remain writable and supported.
- Observability-backed features may become read-only, partially unavailable, or hidden behind degraded-state messaging.

### Data Model

- Log events are persisted in the `log_events` table and mirrored in Elasticsearch indexes for search.
- `moderation_actions` table records bans and warnings with timestamps and includes a `tenant_id` column.
- `player_reports` table stores abuse and bug reports with a `tenant_id` column.
- `feature_flag` table mirrors active runtime settings for auditing and stores the `tenant_id` of the owning game.

### Moderation Workflow

- Operators review flagged logs through the web UI.
- Actions such as bans or warnings are issued via secured API calls.
- Enforcement follows the ban taxonomy:
  - `account_security_ban` events are applied by Account Service.
  - `gameplay_ban` events are enforced by Game Session Service.
  - `chat_mute` and `chat_ban` events are enforced by Social & Groups Service.
  All moderation actions are audit-recorded for compliance.

### REST & gRPC Endpoints

#### REST

- `GET /ping` – basic health check returning `"pong"`.
- `POST /reports` – submit an abuse or bug report.
- `POST /feature-flags/toggle` – enable or disable runtime flags.
- `GET /logs` – search stored logs.
- `POST /moderation/actions` – apply a moderation action.
- `GET /sagas` – list saga instances.
- `GET /sagas/{id}/steps` – inspect steps for a saga instance.

```bash
curl http://localhost:8080/ping
```

#### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`logging_admin_service.proto`](../../../../protos/logging-admin/v1/logging_admin_service.proto).
- `QueryLogs(QueryLogsRequest) returns (QueryLogsResponse)` – searches collected logs.
- `ApplyModerationAction(ApplyModerationActionRequest) returns (ApplyModerationActionResponse)` – records a moderation event.
- `CreateReport(CreateReportRequest) returns (CreateReportResponse)` – ingest a player report.
- `ToggleFeatureFlag(ToggleFeatureFlagRequest) returns (ToggleFeatureFlagResponse)` – enable or disable a feature flag.

```bash
grpcurl -plaintext localhost:6565 logging_admin.v1.LoggingAdminService/Ping

grpcurl -plaintext -d '{"tenant_id":1,"reporter_account_id":1,"target_account_id":2,"type":"BUG","description":"example"}' \
  localhost:6565 logging_admin.v1.ReportService/CreateReport
```

### Endpoint Authentication Classes

| Surface | Examples | Required auth path | Notes |
| --- | --- | --- | --- |
| Public/infra health | `GET /ping`, `Ping` | Internal network + platform health policy | Not a user-authenticated business operation. |
| Admin/operator APIs (HTTP) | `/logs`, `/moderation/actions`, `/feature-flags/toggle`, `/reports`, `/sagas*` | JWT middleware (`AuthTokenInterceptor` + route classification) | External tools must enter via Gateway allowlisted routes. |
| Service-to-service control/ingest (gRPC internal) | Internal lifecycle/event ingestion and trusted backend calls | mTLS caller identity + explicit service authorization checks | Never exposed at public ingress; role claims are required only for user-scoped actions. |

### Availability Classes by Endpoint Family

| Endpoint family | Availability class | Required behavior during observability outage |
| --- | --- | --- |
| `/moderation/actions`, `/feature-flags/toggle`, `/reports`, `/sagas*`, tick-remediation APIs | Core operator control plane | Remain available; may use local/PostgreSQL-backed audit state and downstream domain-service APIs only |
| `/logs`, embedded Kibana/Grafana/Jaeger/Alertmanager views | Observability-backed | May degrade, return explicit unavailable/read-only states, or be hidden behind degraded-state messaging |

## Dependencies

- **Internal:**
  - Account Service forwards account events and payment notifications.
  - Game Session Service streams session lifecycle metrics.
  - Social & Groups Service delivers chat logs for moderation.
  - **External:** Elasticsearch, Prometheus, Kibana, Grafana, and Alertmanager for storage, visualization, embedding, and alerting.

> See [**Gateway Architecture**](../../system-architecture-gateway.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../system-architecture-protocol-bridging.md) for
details on shared infrastructure components.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.
- REST endpoints listen on port `8080` and gRPC on port `6565`.

## Environment Variables

The service uses the configuration approach from
[Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md).
It relies on the [PostgreSQL credentials](../../infrastructure/environment-and-secrets.md#postgresql-credentials).

### Redis Role and Prefixes

- The Logging & Admin Service does **not** connect to Redis at runtime. It consumes Redis-derived metrics and coordination health information via Game Session’s APIs and exporters, but it never issues commands against Coordination Redis or Cache/Rate-Limit Redis directly; all remediation actions are driven through the documented runbooks in [Redis Operations & Migrations](../../system-architecture-redis-operations.md) and Game Session control APIs.

TLS certificates are supplied via [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates). Peer services can be discovered using variables prefixed `FIREMUD_SERVICES_`.
The OpenTelemetry collector endpoint can be overridden via `OTEL_ENDPOINT` (see [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md)).

Additional variables specific to this service:

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `FIREMUD_AUTH_JWKS_URI` | JWKS endpoint used for JWT validation (canonical) | *(none)* |
| `FIREMUD_AUTH_JWT_SECRET` | Legacy HMAC JWT validation secret (transitional only; not for player-facing environments) | *(none)* |
| `FIREMUD_AUTH_JWT_SECRET_PATH` | Legacy file path for HMAC JWT validation secret (transitional only; not for player-facing environments) | *(none)* |
| `FIREMUD_AUTH_JWT_EXPIRATION_MS` | Lifetime of issued JWTs in milliseconds | `3600000` |
| `FIREMUD_SERVICES_ACCOUNT_SERVICE` | gRPC endpoint (host:port) for the Account Service | *(none)* |
| `FIREMUD_SERVICES_GAME_SESSION_SERVICE` | gRPC endpoint (host:port) for the Game Session Service | *(none)* |

## Proto Files

API schemas are kept in
[../../../../protos/logging-admin/v1](../../../../protos/logging-admin/v1). When
these change, run `./gradlew generateProto` to refresh generated sources.

## Related Documentation

See [Logging & Monitoring](../../system-architecture-logging-monitoring.md) for details on the shared observability stack.

- [Security Architecture](../../system-architecture-security.md)
- [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- [Service Responsibility Matrix](../../service-responsibility-matrix.md)
- [User Journeys – Monitoring and Moderation](../../user-journeys-operators.md#1-monitoring-and-moderation)
- [User Journeys – Purchases and Subscriptions](../../user-journeys-players.md#5-purchases-and-subscriptions)
- [gRPC API Style & Versioning Guidelines](../../system-architecture-grpc.md)
- [Shared Libraries Overview](../../system-architecture-shared-libraries.md)
- [Backup & Disaster Recovery](../../system-architecture-backup-recovery.md)
- [Authentication & Authorization](../../system-architecture-authentication.md)
- [Testing Strategy](../../system-architecture-testing.md)
- [CI/CD Pipeline](../../system-architecture-cicd.md)

- [System Architecture Diagram](../../system-architecture-diagram.md)
- [System Context Diagram](../../system-context-diagram.md)

## Additional Details

### Saga Dashboard

The service exposes `/sagas` and `/sagas/{id}/steps` endpoints for operators to inspect
long-running workflows coordinated via the shared Saga library. The dashboard reads from
the `saga_instance` and `saga_step` tables and publishes a `sagas.active` Prometheus gauge.

See [Transaction Strategies](../../system-architecture-transactions.md) for an overview of
Saga usage across FireMUD.
