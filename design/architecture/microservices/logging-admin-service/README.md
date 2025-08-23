# Logging & Admin Service

## Overview

Centralized logging and administration tools for the platform. Collects log data from all services and provides moderation capabilities for game operators.

### Responsibilities

- Aggregate logs from every microservice via Fluent Bit sidecars and expose search APIs.
- Offer dashboards and search for operators and moderators by embedding Kibana and Grafana views.
- Enforce moderation actions such as bans via secured APIs
- Record audit trails for feature flag changes and account events.

## Architecture / Design Notes

Uses the common stack outlined in [Logging & Monitoring](../../system-architecture-logging-monitoring.md) and exposes admin endpoints for reviewing logs and applying moderation actions. It consumes Kibana and Grafana APIs to embed existing dashboards within the admin interface.
All admin APIs are secured via role-based access control integrated with the Account Service.

- gRPC connections to this service require mTLS. JWT validation is required for admin or user-facing endpoints; internal gameplay and system calls are authenticated solely via mTLS.
- The security model relies solely on JWT roles; there is no additional
  network-layer isolation for admin endpoints.
- Moderation data and log indices include a `tenantId` field so administrators
  only see information for the games they manage. Cross-tenant queries are
  rejected per the [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
  strategy.
- Utilizes the [Shared Libraries](../../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.

## Key Features

- Central log search for entries collected via Fluent Bit sidecars.
- [Analytics dashboards](./analytics-dashboards.md) for operators, embedding Kibana and Grafana panels.
- Tools for banning or restricting accounts.
- [Role-based admin UI](./admin-ui.md) for moderators.
- Saga workflows coordinate moderation tasks across services. See [Transaction Strategies](../../system-architecture-transactions.md).
- [Moderation policies](./moderation-policies.md) including profanity filters.
- UI for toggling runtime feature flags. Backend APIs are available. See [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md).
- Audit trail for account actions and world changes.
- Transaction logs for purchases and subscription events.
- Captures failed login attempts and suspicious activity reported by the Game
  Session Service for operator review.
- Works with Saga workflows to record state changes across services. See
  [Transaction Strategies](../../system-architecture-transactions.md).
- Automated alerts for suspicious activity via Alertmanager.
- Real-time analytics on game performance.
- Optional TOTP-based two-factor authentication for administrator accounts.

### Data Model

- Log events are persisted in the `log_events` table and mirrored in Elasticsearch indexes for search.
- `moderation_actions` table records bans and warnings with timestamps and includes a `tenant_id` column.
- `player_reports` table stores abuse and bug reports with a `tenant_id` column.
- `feature_flag` table mirrors active runtime settings for auditing and stores the `tenant_id` of the owning game.

### Moderation Workflow

- Operators review flagged logs through the web UI.
- Actions such as bans or warnings are issued via secured API calls.
- Events are forwarded to the Account Service for enforcement and stored for
  compliance purposes.

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
Redis variables are not required.
TLS certificates are supplied via [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates). Peer services can be discovered using variables prefixed `FIREMUD_SERVICES_`.
The OpenTelemetry collector endpoint can be overridden via `OTEL_ENDPOINT` (see [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md)).

Additional variables specific to this service:

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `FIREMUD_AUTH_JWT_SECRET` | HMAC signing key for JWT validation | *(none)* |
| `FIREMUD_AUTH_JWT_SECRET_PATH` | Path to a file containing the JWT secret | *(none)* |
| `FIREMUD_AUTH_JWT_EXPIRATION_MS` | Lifetime of issued JWTs in milliseconds | `3600000` |
| `FIREMUD_SERVICES_ACCOUNT_SERVICE` | gRPC endpoint (host:port) for the Account Service | *(none)* |
| `FIREMUD_SERVICES_GAME_SESSION_SERVICE` | gRPC endpoint (host:port) for the Game Session Service | *(none)* |

## Proto Files

API schemas are kept in
[../../../../protos/logging-admin/v1](../../../../protos/logging-admin/v1). When
these change, run `./gradlew generateProto` to refresh generated sources.

## 📚 Related Documentation

See [Logging & Monitoring](../../system-architecture-logging-monitoring.md) for details on the shared observability stack.

- [Security Architecture](../../system-architecture-security.md)
- [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- [Service Responsibility Matrix](../../service-responsibility-matrix.md)
- [User Journeys – Monitoring and Moderation](../../user-journeys.md#9-monitoring-and-moderation)
- [User Journeys – Purchases and Subscriptions](../../user-journeys.md#11-purchases-and-subscriptions)
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
