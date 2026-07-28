# Logging & Admin Service

## Overview

Centralized logging and administration tools for the platform. The service collects log data from all services, provides moderation capabilities for game operators, embeds shared observability tools, and acts as the operator-facing coordinator for coordination-health monitoring and live per-instance tick pause/resume forwarding.

## Implementation Status

Logging & Admin owns moderation policy persistence, evaluation, and audit. `GAMEPLAY_ADMISSION` is the gameplay enforcement decision boundary consumed by Game Session, and `CHAT_SEND` is the chat enforcement decision boundary consumed by Social & Groups; applicable high-risk decisions fail closed when a fresh policy result is unavailable. Versioned snapshot/event propagation and broader owner-side enforcement remain missing, so the current operator action route persists policy input and audit but is not itself an enforcement mutation. Automated remediation is live only as per-instance `<tenantId, gameInstanceId>` `PauseTicksForScope`/`ResumeTicksForScope` forwarding; regional reset and general remediation are not live capabilities.

## Responsibilities

- Aggregate logs from every microservice via Fluent Bit sidecars and expose search APIs.
- Offer dashboards and search for operators and moderators by embedding Kibana and Grafana views.
- Define moderation policy, record moderation actions, and keep auditable moderation records.
- Record audit trails for feature flag changes and account events.
- Monitor coordination and tick health across tenant and region scopes. Automated remediation is live only as per-instance `<tenantId, gameInstanceId>` `PauseTicksForScope`/`ResumeTicksForScope` forwarding; regional pause/resume, regional reset, and broader/general remediation remain target-only.

## Key Features

- Central log search for entries collected via Fluent Bit sidecars.
- [Analytics dashboards](./analytics-dashboards.md) for operators, embedding Kibana and Grafana panels, including Telnet ingress views based on the TCP Proxy metrics described in [Logging & Monitoring](../../system-architecture-logging-monitoring.md) and the example Grafana snippets under `design/observability/grafana/`.
- Tools for reviewing and recording account restrictions.
- [Role-based admin UI](./admin-ui.md) for moderators.
- [Moderation policies](./moderation-policies.md) including profanity filters.
- UI for requesting runtime feature-flag overrides through owning domain control-plane APIs.
- Audit trail for account actions, world changes, and moderation actions.
- Transaction logs for purchases and subscription events.
- Operator review of failed login attempts and suspicious activity reported by Game Session.
- Automated alerts for suspicious activity via Alertmanager.
- Real-time analytics on game performance.

## Document Map

- [API Contracts](./api-contracts.md)
  - admin/moderation/control-plane surfaces and canonical API ownership.
- [Runtime and Data](./runtime-and-data.md)
  - observability-state ownership, moderation/audit data boundaries, and durability assumptions.
- [Operations](./operations.md)
  - readiness/liveness, operator workflows, and operational guidance for dashboards and remediation flows.
- [Configuration](./configuration.md)
  - environment variables, embedding/trust configuration, and source-location notes.
- [Role-Based Admin UI](./admin-ui.md)
  - operator-facing UI ownership and interaction model.
- [Analytics Dashboards](./analytics-dashboards.md)
  - dashboard-specific ownership and visualization detail.
- [Moderation Policies](./moderation-policies.md)
  - moderation rules, escalation behavior, and policy-specific detail.

## Dependencies

- **Internal:**
  - Account Service forwards account events and payment notifications.
  - Game Session Service streams session lifecycle metrics and owns runtime coordination state.
  - Social & Groups Service delivers chat logs for moderation.
- **External:** Elasticsearch, Prometheus, Kibana, Grafana, and Alertmanager for storage, visualization, embedding, and alerting.

See [Gateway Architecture](../../system-architecture-gateway.md), [Deployment Environments](../../infrastructure/deployment-environments.md), and [Protocol Bridging](../../system-architecture-protocol-bridging.md) for details on shared infrastructure components.

## Related Documentation

- [Role-Based Admin UI](./admin-ui.md)
- [Analytics Dashboards](./analytics-dashboards.md)
- [Moderation Policies](./moderation-policies.md)
- [Logging & Monitoring](../../system-architecture-logging-monitoring.md)
- [Transaction Strategies](../../system-architecture-transactions.md)
- [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md)
- [Security Architecture](../../system-architecture-security.md)
- [Authentication & Authorization](../../system-architecture-authentication.md)
- [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- [Service Responsibility Matrix](../../service-responsibility-matrix.md)
- [User Journeys – Monitoring and Moderation](../../user-journeys-operators.md#1-monitoring-and-moderation)
- [User Journeys – Purchases and Subscriptions](../../user-journeys-players.md#5-purchases-and-subscriptions)
- [gRPC API Style & Versioning Guidelines](../../system-architecture-grpc.md)
- [Shared Libraries Overview](../../system-architecture-shared-libraries.md)
- [Backup & Disaster Recovery](../../system-architecture-backup-recovery.md)
- [Testing Strategy](../../system-architecture-testing.md)
- [CI/CD Pipeline](../../system-architecture-cicd.md)
- [System Architecture Diagram](../../system-architecture-diagram.md)
- [System Context Diagram](../../system-context-diagram.md)
