# ADR 0165: Authoritative Control Actions During Observability Loss

## Status

Accepted

## Implementation Status

This decision is not implemented. Control-path availability classification, durable audit or intent persistence, bounded reconciliation, and failure-mode proof remain gaps.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `MS-OPS-AVAILABILITY-PARTITION`
- Decision date: 2026-07-20
- Decision key: `MS-OPS-AVAILABILITY-PARTITION`
- Primary capability: `PO-4.2` health, readiness, reliability policy, SLOs, and degraded operation
- Affected capabilities: `PO-1.1`, `PO-4.1`, `PO-4.4`, `SF-2.2`, `SF-1.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led review of incident control, moderation, durable audit, authorization, owner authority, observability failure, performance, and deployment isolation

## Context

Elasticsearch, Prometheus, Grafana, Kibana, Jaeger, and Alertmanager are observability systems, not authorities for gameplay, moderation, recovery, or operator control. Making them hard dependencies would turn a monitoring incident into loss of the controls needed to reduce harm or recover service.

The opposite rule—allow every administrative write while monitoring is dark—is also unsafe. An action still requires authentication, domain authority, current fenced state, durable audit or intent, idempotency, and an authoritative outcome.

## Decision

An observability-only outage does not block a core control action when all of the following remain available:

- authenticated and tenant/jurisdiction-scoped operator authority;
- required durable audit or intent persistence;
- the authoritative owning service and its current revision, fence, and preconditions;
- stable idempotency/request identity; and
- durable acknowledgement from the owner.

Risk-reducing controls such as closing admission, pausing ticks, disabling a feature, lowering a quota, or adding an authorized restriction remain available under those conditions.

Exposure-increasing or recovery controls such as opening/cutting over admission, resuming ticks, enabling a feature, increasing quota/capacity, lifting a restriction, or performing remediation remain conditionally available. Missing telemetry alone does not forbid them, but every ordinary authoritative compatibility, recovery, freshness, fence, audit, and owner-acknowledgement gate still applies.

A write fails closed when authentication, tenant/jurisdiction binding, required durable audit, request identity/digest, owner API, current owner state, or an action-specific mandatory safety gate is unavailable. Logging & Admin never performs direct Redis or owner-database mutation and never reports success before durable owner acknowledgement.

Observability-backed dashboards, log/metric/trace search, and alert views degrade explicitly to unavailable or `unknown`. Cached or stale telemetry is labelled with its time and cannot be presented as current authority.

The two availability classes have independent deadlines, circuit breakers, thread/connection pools, and readiness/degradation reporting. They may share one deployable only while fault injection proves that observability saturation cannot starve core controls. Otherwise they split into separate deployables; this does not require a separate Kubernetes cluster.

## Consequences

- Operators retain safety and recovery controls during an observability incident.
- Telemetry does not become a hidden authorization or game-state authority.
- Dark monitoring increases delayed-detection risk, so authorization, durable audit, idempotency, rate limits, and owner acknowledgements are mandatory.
- Action-specific dependency classification and failure-injection evidence add maintenance work.
- Routine runtime overhead is small because core writes already require owner and audit handling and never synchronously query observability backends.

## Alternatives Considered

### Fail Closed for Every Administrative Action

This is simple but can prevent bans, admission closure, tick pause, and recovery while monitoring is the failed subsystem.

### Allow Every Write During Observability Loss

This ignores missing owner truth, fencing, audit, and action-specific safety gates and can turn an observability incident into unsafe mutation.

### Allow Only Exposure-Reducing Writes

This is conservative but incorrectly elevates telemetry into safety authority and can delay valid recovery even when authoritative domain state proves the action safe.

### Split the Service Immediately

Physical isolation is stronger but adds a deployable, routing/auth surface, and operating burden before evidence shows resource isolation inside one service is insufficient.

## Implementation and Proof Obligations

Select and report the required checks and evidence under the shared [Validation and Runtime Proof](../../developer-workflows/validation-and-runtime-proof.md) workflow; record execution results in PR/CI evidence or the owning implementation tracker rather than in this decision record.

Current separation is incomplete. Several owner-side control APIs or durable audits are missing, and the fact that rich observability integrations are not yet implemented is not proof of isolation. Some current mutations can occur before Logging & Admin audit persistence, and retry identity is not consistently stable.

Implementation must classify every endpoint and action, persist required intent before forwarding, use stable digest-bound identity, await durable owner acknowledgement, isolate resources, and expose partition-specific degradation. Environment fault tests must disable each observability backend independently and under saturation, prove permitted controls and durable audit continue, prove missing authoritative prerequisites fail closed, and prove observability views become explicitly unavailable without removing core readiness.

## Reversibility and Revisit Triggers

Action classification and the physical deployable boundary are reversible while the authority rules remain. Split the deployable if resource isolation cannot meet measured control-plane availability, or revisit a specific action when its owner introduces a new mandatory safety dependency.

## Required Documentation Alignment

- [design/architecture/microservices/logging-admin-service/runtime-and-data.md](../microservices/logging-admin-service/runtime-and-data.md)
- [design/architecture/microservices/logging-admin-service/api-contracts.md](../microservices/logging-admin-service/api-contracts.md)
- [design/architecture/microservices/logging-admin-service/operations.md](../microservices/logging-admin-service/operations.md)
- [design/architecture/system-architecture-testing.md](../system-architecture-testing.md)
