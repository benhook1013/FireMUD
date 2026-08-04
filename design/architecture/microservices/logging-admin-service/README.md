# Logging & Admin Service

## Overview

Centralized logging and administration tools for the platform. The service collects log data from all services, provides moderation capabilities for game operators, embeds shared observability tools, and acts as the operator-facing coordinator for coordination-health monitoring. Per-instance tick pause/resume forwarding is implemented but unavailable as a supported operator mutation until the owner-side variant of the [Operator Mutation Support Gate](./admin-ui.md#implementation-status) is complete.

## Implementation Status

Logging & Admin owns target-state moderation policy persistence, evaluation, and audit. `GAMEPLAY_ADMISSION` is the gameplay enforcement decision boundary consumed by Game Session, and `CHAT_SEND` is the chat enforcement decision boundary consumed by Social & Groups; applicable high-risk decisions fail closed when a fresh policy result is unavailable. Versioned snapshot/event propagation and broader owner-side enforcement remain missing. `/moderation/actions` and `ApplyModerationAction` are gated/unavailable and currently persist neither the `moderation_actions` record nor audit evidence, and do not perform owner-side enforcement. They remain unavailable pending the receiving-service variant of the [Operator Mutation Support Gate](./admin-ui.md#implementation-status). This is the separate gated human mutation classification, not an owner-forwarding mutation. The separate `EvaluateModerationPolicy` read remains live at the Game Session and Social & Groups owner boundaries. Per-instance `<tenantId, gameInstanceId>` `PauseTicksForScope`/`ResumeTicksForScope` forwarding is implemented, but it is unavailable under the owner-side variant of the Operator Mutation Support Gate; when enabled, the receiving owner redeems its forwarded reference with Account. `ToggleFeatureFlag` is unavailable under that gate as well. Regional reset and general remediation are not live capabilities.

## Responsibilities

- Aggregate logs from every microservice via Fluent Bit sidecars and expose search APIs.
- Offer dashboards and search for operators and moderators by embedding Kibana and Grafana views.
- Define moderation policy and, in the target state, persist moderation actions and keep auditable moderation records.
- Record live audit trails for account events and target/operator-intent audit for feature-flag override requests; intent does not prove a runtime feature-flag mutation.
- Monitor coordination and tick health across tenant and region scopes. Automated per-instance `<tenantId, gameInstanceId>` `PauseTicksForScope`/`ResumeTicksForScope` forwarding exists but remains unavailable pending the owner-side variant of the Operator Mutation Support Gate; Logging & Admin forwards the opaque reference and does not redeem it. Regional pause/resume, regional reset, and broader/general remediation remain target-only.

## Key Features

- Central log search for entries collected via Fluent Bit sidecars.
- [Analytics dashboards](./analytics-dashboards.md) for operators, embedding Kibana and Grafana panels, including Telnet ingress views based on the TCP Proxy metrics described in [Logging & Monitoring](../../system-architecture-logging-monitoring.md) and the example Grafana snippets under `design/observability/grafana/`.
- Tools for reviewing account-related evidence and target-state restriction workflows; Logging & Admin does not currently record or enforce account restrictions.
- [Role-based admin UI](./admin-ui.md) for moderators.
- [Moderation policies](./moderation-policies.md) including profanity filters.
- Target-only UI for requesting runtime feature-flag overrides through owning domain control-plane APIs; `ToggleFeatureFlag` is not externally supported until the owner-side variant of the Operator Mutation Support Gate is complete.
- Audit trail for account actions and world changes; target-state audit trail for moderation actions.
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
- [User Journeys – Monitoring and Moderation](../../../product/user-journeys/operators.md#1-monitoring-and-moderation)
- [User Journeys – Purchases and Subscriptions](../../../product/user-journeys/players.md#5-purchases-and-subscriptions)
- [gRPC API Style & Versioning Guidelines](../../system-architecture-grpc.md)
- [Shared Libraries Overview](../../system-architecture-shared-libraries.md)
- [Backup & Disaster Recovery](../../system-architecture-backup-recovery.md)
- [Testing Strategy](../../system-architecture-testing.md)
- [CI/CD Pipeline](../../system-architecture-cicd.md)
- [System Architecture Diagram](../../system-architecture-diagram.md)
- [System Context Diagram](../../system-context-diagram.md)
