# Logging & Admin Service

## Overview

Target state: centralized logging and administration tools present the selected profile's cross-service log-query and observability capabilities, fixed-category moderation policy, case, bounded-appeal, and audit contracts, and operator-facing coordination-health views. The current service does not collect the deployed structured-log stream, embed observability tools, or provide a separate admin UI; its narrower live surfaces are recorded in [Implementation Status](#implementation-status). Runtime restriction state remains owned by Account, Game Session, or Social & Groups. Per-instance tick pause/resume ingress is declared but hard fail closed; no Logging & Admin forwarding method is callable until the owner-side variant of the [Operator Mutation Support Gate](./admin-ui.md#implementation-status) is complete.

## Script-transition audit and observability boundary (target state; complete composition unavailable)

Target state only: Logging & Admin presents script-transition evidence but is not a runtime authority. Its operator views compose Game Session owner pin/history reads with Automation readiness, convergence, plugin, dead-letter, and timer diagnostics; the shared tuple, history, plugin-fence, projection, and audit-evidence definitions remain in the [scripting contracts](../../system-architecture-scripting-contracts.md), [scripting control-plane API](../../system-architecture-scripting-control-plane-api.md), and [scripting observability contract](../../system-architecture-scripting-observability-contract.md). The complete composition is currently unavailable: the live implementation exposes bounded Game Session current-pin/convergence reads whose epoch fields are incomplete, plus Automation's non-authoritative observed-pin projection. Current implementation status is tracked in the [Automation runtime/operator projection](../../../project-management/implementation-tracking/automation-and-scheduler-runtime.md#runtime-and-operator-projection) and [Game Session operator readback](../../../project-management/implementation-tracking/game-session-runtime-and-tick-coordination.md#operator-readback-and-facade-boundaries) tracker sections. The target composition must show projection lag or missing authority explicitly rather than selecting Automation's projection as a replacement history. It does not write pins, epochs, rollout history, Redis coordination keys, or dead-letter rows directly.

## Implementation Status

Current log-query status is narrower than the target profile-aware surface: `LogQueryServiceImpl` reads the service-owned PostgreSQL `log_events` domain table. That path is distinct from deployed structured-log collection and does not prove emitter-to-query delivery, `firemud-logs-*` indexing, Elasticsearch/Kibana queryability, a compatible indexed backend, or console/journal retrieval.

Logging & Admin owns fixed safety policy intent, moderation cases, bounded appeal cases/evidence, and audit. `GAMEPLAY_ADMISSION` is the gameplay enforcement boundary owned by Game Session, and `CHAT_SEND` is the chat enforcement boundary owned by Social & Groups; Account owns account-wide safety restrictions. **Current:** Game Session and Social & Groups synchronously call Logging & Admin's `EvaluateModerationPolicy` compatibility read at those boundaries, and the callers fail closed when the policy read is unavailable or not fresh. `/moderation/actions` and `ApplyModerationAction` are present legacy transport stubs that hard fail closed after narrow tenant/admin checks: HTTP returns `503 Service Unavailable`, gRPC returns an application-level `UNAVAILABLE` error, and neither path dispatches or persists the generic legacy `moderation_actions` row. They are unsupported/nonconformant drift, must not be wired or called as available mutations, and are not Gateway-forwarded. Separate fixed-category audit evidence and owner-side enforcement are absent. Current internal gRPC mTLS does not prove exact workload/method allowlisting or `CreateReport` receiver authorization; bearer JWT acceptance/forwarding remains an implementation gap. These seams are internal, not public routes. **Target:** routine enforcement is owner-local under [Moderation Policies](./moderation-policies.md), with typed commands and durable owner state rather than a synchronous Logging & Admin dependency. Complete fixed categories, owner revisions, appeals, and digest-bound outcome commands remain unimplemented and unproved. Per-instance `<tenantId, gameInstanceId>` `PauseTicksForScope`/`ResumeTicksForScope` ingress is declared but hard fail closed; no Logging & Admin forwarding method remains callable until the owner-side variant of the Operator Mutation Support Gate is complete. `ToggleFeatureFlag` is unavailable under that gate as well. Regional reset and general remediation are not live capabilities.

## Responsibilities

- **Target-state responsibility:** Present the log collection and supported operator-query capabilities advertised by the selected deployment profile. The default indexed profile aggregates service logs through Fluent Bit; compatible indexed backends use their documented mapping, while reduced profiles expose only their declared console/journal posture or explicit indexed-search omission.
- **Target-state responsibility:** Offer the selected profile's dashboards and search to operators and moderators. Kibana and Grafana embedding is the default indexed-profile integration, not a universal service dependency.
- **Target-state responsibility:** Define the fixed safety policy vocabulary, retain moderation cases and bounded appeals/evidence, and keep append-only auditable policy/review records; do not own runtime restriction state.
- Record live audit trails for account events and target/operator-intent audit for feature-flag override requests; intent does not prove a runtime feature-flag mutation.
- Monitor coordination and tick health across tenant and region scopes. Automated per-instance `<tenantId, gameInstanceId>` `PauseTicksForScope`/`ResumeTicksForScope` ingress is declared but hard fail closed pending the owner-side variant of the Operator Mutation Support Gate; no Logging & Admin forwarding method is callable. Regional pause/resume, regional reset, and broader/general remediation remain target-only.

## Key Features

- **Target state:** Profile-advertised log retrieval: central indexed search for the default or a compatible indexed profile, console/journal retrieval for a declared reduced profile, or an explicit unavailable indexed-search state.
- **Target state:** [Analytics dashboards](./analytics-dashboards.md) for operators. The default indexed profile embeds Kibana and Grafana panels, including Telnet ingress views based on the TCP Proxy metrics described in [Logging & Monitoring](../../system-architecture-logging-monitoring.md) and the example Grafana snippets under `design/observability/grafana/`; another profile maps equivalent views or marks omitted panels `not_applicable`.
- Tools for reviewing account-related evidence and target-state restriction workflows; Account owns and enforces account restrictions, while Logging & Admin does not currently record or enforce them.
- **Target state:** [Role-based admin UI](./admin-ui.md) for moderators; no separate admin application or embedded-dashboard endpoint is currently implemented.
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
- **External, profile-dependent:** the default indexed profile uses Elasticsearch, Fluent Bit, Kibana, Prometheus, Grafana, and Alertmanager for collection, storage, visualization, embedding, and alerting. Compatible profiles declare equivalent dependencies and evidence mappings; reduced profiles omit unavailable indexed-search dependencies and retain their explicit operator limitations.

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
- [User Journeys – Purchases and Subscriptions](../../../product/user-journeys/players.md#6-purchases-and-subscriptions)
- [gRPC API Style & Versioning Guidelines](../../system-architecture-grpc.md)
- [Shared Libraries Overview](../../system-architecture-shared-libraries.md)
- [Backup & Disaster Recovery](../../system-architecture-backup-recovery.md)
- [Testing Strategy](../../system-architecture-testing.md)
- [CI/CD Pipeline](../../system-architecture-cicd.md)
- [System Architecture Diagram](../../system-architecture-diagram.md)
- [System Context Diagram](../../system-context-diagram.md)
