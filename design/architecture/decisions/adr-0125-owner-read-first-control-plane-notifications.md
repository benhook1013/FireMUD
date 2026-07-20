# ADR 0125: Owner-Read-First Control-Plane Notifications

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `CP-01`
- Primary capability: `SF-1.1` service-to-service communication contracts
- Affected capabilities: `AS-1.6`, `AR-3.3`, `PO-1.1`, `PO-4.1`, `SF-2.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of mandatory durable event delivery, owner-read recovery, lossy invalidation, targeted asynchronous consequences, ordering identity, and operational burden

## Context

The scripting control-plane event catalogue previously required every listed state-change family to use durable at-least-once delivery, generic tenant- or instance-scoped sequencing, replay, and reconstruction. Most identified consumers need fresh owner state or operator history rather than the event itself as an independently guaranteed business consequence. Those consumers can recover through authoritative reads, while a notification only reduces freshness latency.

Treating every possible notification as a durable event would require transactional outbox capture, per-consumer delivery state, retry workers, retention cleanup, backpressure, and recovery proof before a named durable subscriber or delivery objective exists. It would also tempt safety-critical lifecycle transitions to rely on asynchronous event propagation. Plugin final-execution fencing, for example, requires an idempotent control-plane command and durable acknowledgement under ADR 0124; an event cannot be its correctness barrier.

Some future flows may require guaranteed asynchronous consequences. Signer-revocation propagation to an independent compliance or alerting system and rollback-convergence timeout delivery to an alerting pipeline are plausible candidates, but no such subscriber and delivery service level are currently adopted. ADR 0083 already defines the required transport when a durable asynchronous flow is justified.

## Decision

### Authoritative Owner State and Recovery

Each producing service durably stores its authoritative state and, where chronology matters, append-only history with a stable producer-owned cursor. That state and history API are the authority and reconstruction path. Consumers obtain correctness through direct owner reads and may use bounded-staleness caches and controlled polling with rate limits, jitter, and herd control.

Current scripting control-plane event families are advisory notifications unless a separate contract names a durable asynchronous consumer and delivery objective. Redis or gRPC may carry lossy, duplicate, delayed, or reordered wake-ups that tell a consumer to reread the owning service. Missing a wake-up may delay freshness until the next poll, but cannot lose accepted state or make a consumer's projection authoritative.

Safety-critical or completion-critical transitions use idempotent commands and durable acknowledgements. Notification publication or consumption is never the correctness, containment, activation, or completion barrier.

### Selectively Durable Asynchronous Flows

A producer adopts a transactional outbox only when a named consumer genuinely requires a guaranteed asynchronous consequence or a defined delivery service level. That flow then follows ADR 0083: atomic producer capture, a stable event identity, independent durable consumer progress, idempotent delivery and effects, explicit ordering and retention, bounded retry and backpressure, and an authoritative reconstruction path.

Adopting durable delivery for one family does not make every control-plane notification durable. Signer-revocation and rollback-convergence-timeout notifications remain advisory today and are explicit candidates for targeted durable flows if a concrete compliance, alerting, or operational subscriber later requires guaranteed delivery.

### Identity and Ordering

Every notification or durable event has a stable `eventId`. Durable consumers use `eventId` as the delivery deduplication identity. `controlPlaneRequestId` correlates an operator request and its consequences; it is not a universal event identity because one request may legitimately produce multiple events.

Consumers use producer-owned aggregate versions and history cursors such as `scriptPinEpoch`, `pluginActivationEpoch`, a patch-status version, or an opaque owner-history cursor. FireMUD does not allocate generic cross-service `tenantSequence` or `instanceSequence` counters. Advisory consumers ignore stale aggregate versions and reread the owner after gaps, contradictions, cache expiry, or wake-up loss. A targeted durable flow declares only the ordering scope it actually implements.

## Consequences

- Owner state and append-only history remain sufficient to rebuild consumers without retaining every notification.
- UI and operational projections may be less fresh after a lost wake-up, bounded by their polling and cache policy.
- Authoritative services receive additional read and polling traffic and must expose efficient bounded reads, rate limits, jitter guidance, and stable cursors where chronology matters.
- Safety transitions remain explicit command-and-acknowledgement protocols rather than depending on eventual event delivery.
- The initial platform avoids outbox tables, consumer ledgers, retry workers, retention cleanup, dead-letter handling, and cross-service sequence allocation for event families with no demonstrated durable subscriber.
- A future guaranteed asynchronous consumer requires a targeted flow contract and ADR 0083 infrastructure rather than silently upgrading an advisory notification.
- There is no ready-made retained stream for arbitrary future subscribers or analytics; those needs must use owner history or justify a durable flow.

## Alternatives Considered

### Make Every Control-Plane Family Durable

Capture every state change in a transactional outbox and retain independent consumer progress, replay, reconstruction, sequencing, and backpressure for every listed family. This gives future subscribers a uniform stream and minimizes freshness delay after process restart. Rejected because current correctness and history consumers can reread authoritative owners, while the cost and proof burden would be paid before durable consumers and service levels are known.

### Best-Effort Notifications Without Authoritative Recovery

Publish lossy notifications and let projections become the only usable cross-service state. Rejected because lost notifications would create unrecoverable drift. Lossy wake-ups are acceptable only because durable owner state and history remain the authority and reconstruction path.

### Shared Tenant and Instance Sequence Counters

Allocate a monotonic `tenantSequence` or `instanceSequence` spanning event families and producer services. Rejected because it introduces a cross-service sequencing authority and contention without a demonstrated global-order requirement. Producer-owned aggregate epochs and opaque owner-history cursors expose the order each consumer actually needs.

## Implementation and Proof Obligations

The current control-plane event document and implementation evidence do not establish one complete durable outbox and independent-consumer delivery path for every listed family. This decision removes that universal target; it does not claim the owner history APIs, notification paths, bounded polling, cache freshness, or targeted durable flows are already implemented.

Each owner must prove durable authoritative state and any promised append-only history or cursor behavior. Advisory notification proof must cover loss, duplication, delay, reordering, Redis reset or gRPC disconnection, bounded polling recovery, stale-cache expiry, restart, read throttling, and herd control. Consumers must demonstrate that notification loss affects freshness only and that stale aggregate versions cannot overwrite newer state.

Safety-critical command paths must prove idempotent request handling, durable acknowledgement, retry after ambiguous failure, and fail-closed behavior without notification delivery. Any future durable asynchronous flow must separately prove every ADR 0083 obligation, including atomic outbox capture, independent consumer progress, stable `eventId` deduplication, explicit ordering and retention, retry and backpressure, and reconstruction after retention expiry.

## Reversibility and Revisit Triggers

Notification transports, polling intervals, cache limits, owner-history retention, and cursor representations may evolve without changing owner authority. Adopt a targeted durable flow when a named consumer requires guaranteed asynchronous delivery or a defined delivery service level. Reconsider a shared durable broker only under the measured adoption gates in ADR 0083. Requiring generic cross-service sequencing or making projections authoritative requires a new decision.

## Required Documentation Alignment

- `design/architecture/system-architecture-scripting-control-plane-events.md`
