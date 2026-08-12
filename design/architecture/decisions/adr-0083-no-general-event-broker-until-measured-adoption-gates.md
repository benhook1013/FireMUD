# ADR 0083: No General Event Broker Until Measured Adoption Gates

## Status

Accepted

## Implementation Status

The no-general-broker boundary is accepted target state. Transactional-outbox coverage, independent consumer delivery state, reconstruction, retention, backpressure, and focused end-to-end proof remain incomplete.

## Canonical Design

- [Scripting & Automation: Control Plane Events](../system-architecture-scripting-control-plane-events.md)
- [Redis Architecture](../system-architecture-redis.md)

## Decision Record

- Decision date: 2026-07-20
- Decision key: `REDIS-03`
- Primary capability: `SF-2.2` Redis coordination and cache boundaries
- Affected capabilities: `SF-1.1`, `PO-4.1`, `AS-1.5`, `GR-1.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review with durable-broker-now and Redis-backed event-transport alternative analysis
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `REDIS-03`

## Context

FireMUD needs durable asynchronous delivery for control-plane events, audit flows, workflow updates, and other business consequences, but it does not currently have evidence that a general event broker is the lowest-cost reliable substrate. Redis already carries coordination hints and queue projections, yet its reset and tail-loss contract makes it unsuitable as the only record of accepted durable work.

Avoiding a broker does not make durable delivery best effort. Producers and consumers still need atomic event capture, independent delivery progress, idempotency, replay or reconstruction, bounded retention, and backpressure. Those guarantees must be stated for the transport that actually exists rather than implied through generic “event bus” language.

## Decision

FireMUD does not adopt a general event broker initially.

Durable asynchronous flows use:

- a PostgreSQL transactional outbox committed atomically with the producing service's authoritative state change;
- durable per-consumer delivery state so one consumer's progress, retry, or failure does not stand in for another consumer's result;
- idempotent delivery workers that can retry the same logical event without duplicating consumer effects; and
- authoritative reconstruction APIs that let consumers rebuild state when replay retention is exhausted or an event projection must be recreated.

Redis may carry only disposable wake signals, durable-row pointers, and observability or coordination hints for those flows. Losing Redis state may delay discovery or require a projection rebuild, but it cannot erase the outbox event, consumer delivery state, or authoritative source state. A Redis entry is never the sole durable event or the only pointer to accepted durable work.

Every asynchronous flow declares its actual:

- ordering scope and guarantee;
- event and delivery-state retention;
- replay window or reconstruction path; and
- producer and consumer backpressure behavior.

Flows do not claim global ordering, indefinite replay, independent consumer progress, or durable buffering unless their concrete PostgreSQL, worker, and API implementation provides and proves those properties.

Kafka, NATS, Pulsar, or another general broker is reconsidered only when measured adoption gates demonstrate material need. The evidence must cover:

- sustained fan-out to multiple consumers;
- replay volume, replay latency, or retained-history requirements that the PostgreSQL outbox and reconstruction path cannot meet economically;
- consumer independence requirements that make producer-owned delivery coordination a material constraint; and
- duplicated outbox, delivery, retention, or backpressure infrastructure whose measured engineering and operational cost exceeds the cost of a shared broker platform.

Crossing a gate does not make Redis durable authority and does not remove producer atomicity, stable event identity, idempotent consumers, reconstruction, or explicit flow contracts. Broker adoption requires a separate architecture decision defining its authority, topology, delivery, ordering, retention, replay, backpressure, failure, and operational boundaries.

## Consequences

- Durable event capture remains atomic with the owning PostgreSQL transaction without adding a general broker platform initially.
- Consumers fail, retry, and advance independently through durable per-consumer delivery state.
- Redis loss affects discovery latency and projections rather than accepted durable work.
- Each flow exposes its real ordering, retention, replay, reconstruction, and backpressure behavior instead of inheriting unspecified bus semantics.
- Producers and consumers must implement and operate outbox workers, delivery state, cleanup, reconstruction, metrics, and failure handling.
- Adding consumers or retaining larger histories may increase PostgreSQL storage, polling, indexing, and delivery cost.
- A broker remains available when measurements show that shared fan-out, replay, consumer independence, or duplicated infrastructure justify its platform and operational overhead.

## Alternatives Considered

### Adopt a Durable General Broker Now

Rejected initially because FireMUD has not measured the fan-out, replay demand, consumer independence, or duplicated delivery infrastructure needed to justify another stateful platform and its operational obligations.

### Use Redis as the Durable Event Transport

Rejected because Redis coordination state is disposable within its reset and tail-loss contract. Streams, lists, wake signals, and queue pointers may accelerate delivery, but they cannot preserve the only accepted event or consumer progress record.

## Implementation and Proof Obligations

Implement transactional outbox capture in the same PostgreSQL transaction as each producing state change; durable event identity and per-consumer delivery state; idempotent bounded workers; explicit retention and cleanup; reconstruction APIs; and flow-specific ordering, replay, and backpressure contracts. Redis acceleration must be rebuildable entirely from durable rows and authoritative service state.

Prove atomic producer commit and outbox insertion; rollback without orphan events; duplicate and concurrent delivery; worker crash before and after consumer effect and acknowledgement; independent progress and failure for multiple consumers; bounded retry and backpressure; retention cleanup without violating the declared replay window; reconstruction after retention expiry; Redis wake or pointer loss, duplication, delay, reset, and rebuild; producer and consumer restart; and conformance to each flow's declared ordering scope.

The current repository provides partial evidence through durable PostgreSQL work items and rebuildable Redis queue pointers. The broader repository does not yet demonstrate one complete transactional-outbox and per-consumer delivery-state path for every documented durable control-plane, audit, and workflow event family. The current implementation and focused end-to-end proof are not claimed by this decision.

## Reversibility and Revisit Triggers

Outbox schemas, worker topology, delivery-state layout, polling cadence, retention periods, reconstruction API shapes, and Redis wake mechanisms may evolve without changing PostgreSQL authority or the explicit per-flow contract. Revisit the no-general-broker choice when measured fan-out, replay demand, consumer independence, or duplicated delivery infrastructure crosses the adoption gates defined above.
