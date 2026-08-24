# ADR 0158: Simplified Observability Degradation Without Fallback Alert Authority

## Status

Accepted

## Implementation Status

This decision is not implemented. Durable audit fallback, Alertmanager-only alert authority, independent deadman coverage, and safe degraded controls remain gaps.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `OBS-04`
- Decision date: 2026-07-20
- Decision key: `OBS-04`
- Primary capability: `PO-4.2` health, readiness, reliability policy, SLOs, and degraded operation
- Affected capabilities: `PO-1.1`, `PO-1.4`, `PO-4.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led review of degraded observability, alert authority, moderation safety and audit durability, external deadman coverage, and the cost of maintaining a second fallback alert model

## Context

FireMUD must preserve authoritative gameplay, moderation, and operator safety actions when Elasticsearch, Prometheus, Grafana, Kibana, Jaeger, or Alertmanager is unavailable. Observability systems help operators detect and investigate problems, but they do not own game state or the authority to perform safety actions.

The prior target state also asked Logging & Admin to reconstruct a bounded active-alert view from Prometheus recording rules when Alertmanager was unavailable, then merge, deduplicate, timestamp, and expire that view against Alertmanager state. That creates a second alert-state model whose rule families, labels, freshness behavior, and user interface must remain synchronized with Alertmanager. The fallback surface is not implemented, and its nominally small rule set has expanded across player SLO, backup, recovery, tick, maintenance, and cross-region conditions.

The word “best effort” is also unsafe if applied indiscriminately. Search indexes, dashboards, metrics, and traces may be unavailable without blocking a moderation action, but the required record that an operator or moderator performed that action is authoritative domain data. It must not disappear merely because the observability pipeline is degraded.

## Decision

### Observability Backends Are Soft Dependencies

Elasticsearch, Prometheus, Grafana, Kibana, Jaeger, OpenTelemetry collection, and Alertmanager are soft dependencies for gameplay and authoritative domain operations. Their failure may remove detection, investigation, visualization, history search, or notification features, but does not by itself stop safe gameplay actions, moderation enforcement, or operator control operations.

Each operator feature identifies which enrichment is unavailable and disables only the view or diagnostic function that directly requires it. It does not treat loss of an observability backend as loss of domain authority.

### Required Audit Is Durable Domain Data

A required moderation, security, or operator audit record is persisted through the authoritative owning service as part of the domain or control-plane operation. It is not a best-effort Elasticsearch document, log line, metric, trace, dashboard event, or Alertmanager notification.

Observability pipelines may receive projections of that durable record for search and correlation. Failure of those projections does not erase the authoritative audit, and inability to write a required durable audit result must follow the owning action's domain failure contract rather than being hidden as observability degradation.

### Alertmanager Alone Owns Routed Alert State

Alertmanager is the sole source of routed active-alert state while it is available. It owns grouping, inhibition, silencing, routing, and notification status. Logging & Admin may display that state, but does not become an independent alert authority and does not maintain a competing active-alert lifecycle.

Alertmanager is not a game-safety authority. Its alert state never grants, denies, pauses, resumes, mutes, bans, kicks, or otherwise decides an authoritative gameplay or moderation action.

### Alertmanager Failure Is Explicitly Unknown

When Alertmanager is unavailable, Logging & Admin reports that routed alert state is unavailable. It does not continue presenting last-known alerts as current and does not merge Prometheus-derived conditions into a second active-alert list.

Logging & Admin may optionally show a bounded Prometheus diagnostic snapshot while Prometheus remains reachable. Such a snapshot is clearly labeled as diagnostic telemetry, not routed alerts. It does not claim notification status, share Alertmanager silence or inhibition semantics, require alert-family equivalence, or participate in cross-source deduplication.

When the source can no longer refresh within its declared freshness budget, its state becomes `unknown`. A stale value is not treated as an active, resolved, or current condition.

### Safety Actions Use Authoritative State

Operators decide and execute safety actions through authoritative domain and control-plane state: service health and readiness, session and admission state, persisted moderation/security policy, tick or recovery controls, and the owning service's action result. Metrics, logs, traces, dashboards, and alerts inform the operator but are not prerequisites or authorization for those actions.

If observability is unavailable, operator interfaces expose the missing diagnostic sources and preserve the safe domain/control operations that remain reachable. A safety action does not wait for a reconstructed fallback alert.

### Profile-Dependent Independent Deadman Covers Total Failure

Profiles claiming externally verified availability or monitoring-resilient readiness require an external deadman and public-path monitoring boundary to detect total in-cluster monitoring failure and broad public-edge failure. That monitor remains outside the Prometheus and Alertmanager failure domain and pages independently. Hobby, single-node, and small profiles may omit the external monitor; they explicitly record a degraded/operator-dependent total-failure detection posture and must not claim independent outage detection, off-cluster paging, or monitoring-resilient readiness. An in-cluster Prometheus mirror may aid dashboards when healthy, but it is not the independent detection authority.

### Duplicate Fallback Alert Machinery Is Removed

FireMUD does not maintain a canonical Prometheus-to-Logging/Admin fallback active-alert registry, source-merging algorithm, alert-family equivalence table, or duplicate-suppression lifecycle.

Prometheus recording rules remain only when they are independently useful for calculation, dashboards, direct diagnostics, or ordinary Alertmanager evaluation. They are not required merely to reproduce Alertmanager state inside Logging & Admin. Documentation and operational assets must remove claims that fallback recording rules become the sole source of active issues while Alertmanager is unavailable.

## Consequences

- Gameplay and safe moderation/control actions do not fail merely because an observability backend is unavailable.
- Required action audits remain durable and queryable from their owning domain even if observability projections are delayed or lost.
- Operators see a clear routed-alert outage instead of an apparently equivalent but semantically weaker alert list.
- Logging & Admin may provide useful direct Prometheus diagnostics without implementing grouping, routing, silence, inhibition, merge, or deduplication semantics.
- For profiles claiming independent monitoring, the external deadman remains necessary because neither Alertmanager nor Prometheus can authoritatively report its own total failure; omitted profiles retain their explicit degraded/operator-dependent posture.
- Removing duplicate fallback authority reduces rule duplication, UI state machinery, equivalence-table upkeep, stale-state ambiguity, and repeated cross-source proof.
- During an Alertmanager-only outage, operators lose the convenience of a replacement routed-alert list and must use explicit diagnostics, authoritative control surfaces, and the incident runbook until routing returns.

## Alternatives Considered

### Maintain a Second Active-Alert Authority in Logging & Admin

This could preserve an alert-like list during an Alertmanager outage, but Prometheus conditions do not reproduce Alertmanager grouping, inhibition, silencing, routing, and notification state. Maintaining equivalence, deduplication, and freshness creates substantial recurring complexity and can present degraded approximations as authoritative. It is rejected.

### Make Observability Backends Hard Gameplay or Moderation Dependencies

This would ensure diagnostics are present before actions proceed, but it would turn a monitoring outage into a gameplay or safety-control outage. It is rejected.

### Treat All Audit as Best-Effort Logging

This is operationally simple but can lose the authoritative record of sensitive actions exactly when observability is impaired. Required audit remains durable domain data.

### Treat Last-Known Alert State as Current Until Recovery

This avoids an empty operator view but cannot distinguish a continuing incident from an already resolved condition. Stale state becomes unknown instead.

## Implementation and Proof Obligations

Current authoritative gameplay and control services, health surfaces, alert rules, and incident runbooks provide partial foundations. Logging & Admin does not currently implement the proposed merged Alertmanager/Prometheus fallback alert surface, so no compatibility layer for that unimplemented model is required.

Architecture documents, runbooks, dashboards, Prometheus rules, and Logging & Admin contracts must remove the fallback active-alert authority, family-equivalence, duplicate-suppression, and cross-source merge requirements. Recording rules retained for diagnostics or Alertmanager evaluation must be described according to that actual purpose.

Implementation must ensure required moderation, security, and operator audits are durable owner-side records rather than observability-only events. Logging & Admin must distinguish available Alertmanager state, unavailable routed alert state, fresh optional diagnostic snapshots, and `unknown` stale diagnostics without implying that telemetry authorizes safety actions.

Focused proof must cover each observability backend failing independently; continued safe gameplay and domain/control operations; durable audit creation while Elasticsearch and the log pipeline are unavailable; explicit routed-alert unavailability when Alertmanager fails; diagnostic snapshots that never appear as active routed alerts; transition to `unknown` after freshness expiry; and, for profiles claiming independent monitoring, independent deadman paging when both Prometheus and Alertmanager are unavailable. Omitted profiles must instead retain evidence of the explicit degraded/operator-dependent total-failure detection posture.

The repository does not currently ship an authoritative external deadman deployment, and live environment proof remains required. Static rule and documentation validation alone cannot satisfy that independent detection obligation.

## Reversibility and Revisit Triggers

Revisit the optional diagnostic snapshot only if operators demonstrate that direct Prometheus diagnostics are not useful enough during Alertmanager outages. Reintroducing any second routed-alert authority requires a separate consequential decision with a concrete need, complete lifecycle semantics, and measured maintenance cost. Do not make observability a domain-safety authority as part of such a revisit.

## Required Documentation Alignment

- [design/architecture/system-architecture-logging-monitoring.md](../system-architecture-logging-monitoring.md)
- [design/architecture/system-architecture-observability-incident-runbook.md](../system-architecture-observability-incident-runbook.md)
- [design/architecture/microservices/logging-admin-service/README.md](../microservices/logging-admin-service/README.md)
- [design/architecture/microservices/logging-admin-service/admin-ui.md](../microservices/logging-admin-service/admin-ui.md)
- [design/observability/README.md](../../observability/README.md)
- [design/observability/grafana/](../../observability/grafana/)
- [k8s/monitoring/prometheus-rules-firemud.yaml](../../../k8s/monitoring/prometheus-rules-firemud.yaml)
