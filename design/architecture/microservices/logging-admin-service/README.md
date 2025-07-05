# Logging & Admin Service

## Overview

Centralized logging and administration tools for the platform. Collects log data from all services and provides moderation capabilities for game operators.

## Architecture / Design Notes

Uses the common stack outlined in [Logging & Monitoring](../../system-architecture-logging-monitoring.md) and exposes admin endpoints for reviewing logs and applying moderation actions.
All admin APIs are secured via role-based access control integrated with the Account Service.

## Key Features

- Central log collection and search.
- Basic analytics dashboards for operators.
- Tools for banning or restricting accounts.
- Moderation policy definitions including profanity filters.
- UI and APIs for toggling runtime feature flags. See [Versioning & Runtime Configuration](../system-architecture-versioning-runtime.md).
- Audit trail for account actions and world changes.
- Transaction logs for purchases and subscription events.

### Data Model

- Log events are stored in Elasticsearch indexes for search.
- `moderation_action` table records bans and warnings with timestamps.
- `feature_flag` table mirrors active runtime settings for auditing.

### Moderation Workflow

- Operators review flagged logs through the web UI.
- Actions such as bans or warnings are issued via secured API calls.
- Events are forwarded to the Account Service for enforcement and stored for
  compliance purposes.

### gRPC/REST APIs

- `QueryLogs` – streams filtered log entries for analysis.
- `ApplyModerationAction` – bans or restricts an account based on policy.
- `ToggleFeatureFlag` – updates runtime configuration values.

## Dependencies

- **Internal:** Account Service forwards account events and payment notifications.
- **External:** Elasticsearch, Prometheus, Grafana, and Alertmanager for storage, visualization, and alerting.

> See [**Gateway Architecture**](../../infrastructure/gateway-architecture.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../infrastructure/protocol-bridging.md) for
details on shared infrastructure components.

## Proto Files

API schemas are kept in
[../../../../protos/logging-admin/v1](../../../../protos/logging-admin/v1). When
these change, run `./gradlew generateProto` to refresh generated sources.

## 📚 Related Documentation

See [Logging & Monitoring](../../system-architecture-logging-monitoring.md) for details on the shared observability stack.
- [Security Architecture](../system-architecture-security.md)
- [Multi-Tenancy](../system-architecture-multi-tenancy.md)
- [Service Responsibility Matrix](../service-responsibility-matrix.md)

## Future Enhancements

- Role-based admin UI.
- Automated alerting for suspicious activity via Prometheus Alertmanager.
- Real-time analytics on game performance.
