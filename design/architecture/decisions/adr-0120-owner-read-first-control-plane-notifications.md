# ADR 0120: Owner-Read-First Control-Plane Notifications

## Status

Accepted

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `CP-01`
- Decision date: 2026-07-20
- Decision key: `CP-01`
- Primary capability: `SF-1.1` service-to-service communication contracts
- Affected capabilities: `AS-1.6`, `AR-3.3`, `PO-1.1`, `PO-4.1`, `SF-2.3`
- Decision owner: FireMUD human product and architecture owner

## Consultation

- Prior CP-01 human reconciliation accepted one explicit owner-contract exception: `ScriptPinConvergenceTimedOut` is captured atomically by Game Session with the terminal timeout transition, while downstream notification delivery remains advisory and consumers recover by rereading the owner. This exception does not generalize durable delivery to other notifications.

## Context

The scripting control-plane catalogue previously implied that every listed state-change family required durable at-least-once delivery, generic tenant- or instance-scoped sequencing, replay, and reconstruction. Most identified consumers need fresh owner state or operator history rather than the notification itself as an independently guaranteed business consequence. Those consumers can recover through authoritative reads, while a notification only reduces freshness latency.

Treating every possible notification as durable would require transactional outbox capture, per-consumer delivery state, retry workers, retention cleanup, backpressure, and recovery proof before a named durable subscriber or delivery objective exists. It would also tempt safety-critical lifecycle transitions to rely on asynchronous propagation. Plugin final-execution fencing requires an idempotent control-plane command and durable acknowledgement under [ADR 0119](./adr-0119-epoch-fenced-per-instance-plugin-activation.md); a notification cannot be that barrier.

## Decision

Each producing service durably stores its authoritative state and, where chronology matters, append-only history with a stable producer-owned cursor. That state and history API are the authority and reconstruction path. Consumers obtain correctness through direct owner reads and may use bounded-staleness caches and controlled polling with rate limits, jitter, and herd control.

Current scripting control-plane notification families are advisory unless a separate contract names a durable asynchronous consumer and delivery objective. Redis or gRPC may carry lossy, duplicate, delayed, or reordered wake-ups that tell a consumer to reread the owning service. Missing a wake-up may delay freshness until the next poll, but cannot lose accepted state or make a consumer projection authoritative.

Safety-critical or completion-critical transitions use idempotent commands and durable acknowledgements. Notification publication or consumption is never the correctness, containment, activation, publication, rollback, or completion barrier.

### Selectively Durable Asynchronous Flows

A producer adopts a transactional outbox only when a named consumer genuinely requires a guaranteed asynchronous consequence or a defined delivery service level. That flow follows [ADR 0083](./adr-0083-no-general-event-broker-until-measured-adoption-gates.md): atomic producer capture, a stable event identity, independent durable consumer progress, idempotent delivery and effects, explicit ordering and retention, bounded retry and backpressure, and an authoritative reconstruction path.

Adopting durable delivery for one family does not make every control-plane notification durable. Signer-revocation notifications remain advisory today and are candidates for targeted durable flows only if a concrete compliance, alerting, or operational subscriber later requires guaranteed delivery. `ScriptPinConvergenceTimedOut` is an existing named durable producer consequence: Game Session captures exactly one event atomically with the timeout workflow transition, while downstream delivery remains an advisory wake-up and consumers recover by rereading Game Session's authoritative workflow/status API.

### Identity and Ordering

Every notification or durable event has a stable `eventId`. Durable consumers use `eventId` as the delivery deduplication identity. `controlPlaneRequestId` correlates an operator request and its consequences; it is not a universal event identity because one request may legitimately produce multiple events.

Consumers use producer-owned aggregate versions and history cursors such as `scriptPinEpoch`, `pluginActivationEpoch`, a patch-status version, or an opaque owner-history cursor. FireMUD does not allocate generic cross-service `tenantSequence` or `instanceSequence` counters. Advisory consumers ignore stale aggregate versions and reread the owner after gaps, contradictions, cache expiry, wake-up loss, or restart. A targeted durable flow declares only the ordering scope it actually implements.

## Consequences

- Owner state and append-only history remain sufficient to rebuild consumers without retaining every notification.
- UI and operational projections may be less fresh after a lost wake-up, bounded by polling and cache policy.
- Authoritative services receive additional read and polling traffic and must expose efficient bounded reads, rate limits, jitter guidance, and herd control.
- Safety transitions remain explicit command-and-acknowledgement protocols rather than depending on eventual notification delivery.
- The platform avoids outbox tables, consumer ledgers, retry workers, retention cleanup, dead-letter handling, and cross-service sequence allocation for families with no demonstrated durable subscriber.
- A future guaranteed asynchronous consumer requires a targeted flow contract and ADR 0083 infrastructure rather than silently upgrading an advisory notification.
- There is no retained stream for arbitrary future subscribers or analytics; those needs use owner history or justify a durable flow.

## Alternatives Considered

### Make Every Control-Plane Family Durable

Capture every state change in a transactional outbox and retain independent consumer progress, replay, reconstruction, sequencing, and backpressure for every listed family. Rejected because current correctness and history consumers can reread authoritative owners, while the cost and proof burden would be paid before durable consumers and service levels are known.

### Best-Effort Notifications Without Authoritative Recovery

Publish lossy notifications and let projections become the usable cross-service state. Rejected because lost notifications would create unrecoverable drift. Lossy wake-ups are acceptable only because durable owner state and history remain the authority and reconstruction path.

### Shared Tenant and Instance Sequence Counters

Allocate monotonic `tenantSequence` or `instanceSequence` values spanning event families and producer services. Rejected because this introduces a cross-service sequencing authority and contention without a demonstrated global-order requirement.

## Implementation and Proof Obligations

The current control-plane event document does not establish one complete durable outbox and independent-consumer delivery path for every listed family. This decision removes that universal target; it does not claim that owner history APIs, notification paths, bounded polling, cache freshness, or targeted durable flows are implemented. The separately named `ScriptPinConvergenceTimedOut` producer capture remains governed by its rollout contract and is not generalized to other families.

Each owner must prove durable authoritative state and any promised append-only history or cursor behavior. Advisory-notification proof must cover loss, duplication, delay, reordering, Redis reset or gRPC disconnection, polling recovery, stale-cache expiry, restart, read throttling, and herd control. Consumers must demonstrate that notification loss affects freshness only and that stale aggregate versions cannot overwrite newer state.

Safety-critical command paths must prove idempotent request handling, durable acknowledgement, retry after ambiguous failure, and fail-closed behavior without notification delivery. Any future durable asynchronous flow must separately prove every ADR 0083 obligation, including atomic outbox capture, independent consumer progress, stable `eventId` deduplication, explicit ordering and retention, retry and backpressure, and reconstruction after retention expiry.

## Reversibility and Revisit Triggers

Notification transports, polling intervals, cache limits, owner-history retention, and cursor representations may evolve without changing owner authority. Adopt a targeted durable flow when a named consumer requires guaranteed asynchronous delivery or a defined delivery service level. Requiring generic cross-service sequencing or making projections authoritative requires a new decision.

## Required Documentation Alignment

- [Scripting control-plane notifications](../system-architecture-scripting-control-plane-events.md)
