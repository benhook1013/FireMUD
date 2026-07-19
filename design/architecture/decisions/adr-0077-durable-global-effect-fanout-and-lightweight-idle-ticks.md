# ADR 0077: Durable Global Effect Fan-Out and Lightweight Idle Ticks

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `TICK-18`
- Primary capability: `GR-1.2` regional tick execution and cadence
- Affected capabilities: `GR-2.1`, `AS-1.4`, `SF-1.4`, `PO-4.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of durable global-effect injection, idle cadence, full-pipeline ticks, sleep/fast-forward, and active-only polling alternatives

## Context

A global or multi-region effect must reach every region selected by the effect without turning a transient wake signal into gameplay authority. Expanding only at dispatch time would also let a topology change alter the intended participant set after the effect was accepted. Durable fan-out introduces partial-injection and overload states that need explicit ownership and visibility.

An idle region has no domain work to execute, but its tick/game-time timeline still orders timers, remote work, deadlines, and later commands. Running the complete gameplay pipeline for every empty boundary wastes resources. Sleeping until work appears is cheaper, but skipping a range safely requires proof that no durable due work was overlooked and that every observer can interpret a compacted timeline.

## Decision

### Global and Multi-Region Effects

Every global or multi-region effect first creates one durable parent identity. At acceptance, the parent freezes both the affected region set and the topology generation from which that set was resolved. Retry or reconciliation uses that recorded set; it does not expand the effect again from current topology.

Fan-out creates an idempotent durable child or injection row for every affected region before publishing any wake signal for that region. The parent and children expose pending, injected, terminal, and feature-specific outcome information sufficient to show partial injection. Game Session owns fan-out reconciliation unless a feature explicitly assigns another durable coordinator. That owner retries missing injections, reconciles duplicate attempts to the same child identity, and derives the visible parent outcome.

Redis markers, notifications, or equivalent wake signals are disposable latency hints. Losing a hint cannot erase a child, and duplicating one cannot create another logical regional effect. Each child enters the target region's ordinary durable inbound-effect path and remains subject to that region's lease, executor fence, epoch checks, lane budgets, and idempotency rules.

Global effect admission has a bounded global outstanding-work cap and explicit backpressure. Within that capacity, per-region child admission is deterministic and fair so a hot region or large fan-out cannot indefinitely exclude another eligible region. Rejected, delayed, or terminalized children remain visible on the durable parent rather than disappearing behind wake delivery.

Forcing a tick means waking an eligible region for its next canonical cadence boundary. It never creates an off-cadence tick or a second regional timeline. A region in canonical `PAUSED` or `STALLED` state is not bypassed by a global wake. Its durable child waits for normal recovery or reaches a feature-declared terminal outcome; the parent reconciliation owner records and incorporates that result.

### Idle Cadence

The initial model physically advances every region at each canonical cadence boundary, including when the region is idle. A truly empty boundary records a lightweight durable, fenced empty-tick watermark or heartbeat under the current region epoch, lease, and executor fence. It advances the committed tick identifier without invoking domain services, acquiring entity locks, creating Redis `pending` state, or creating an effect batch.

Tick/game time therefore continues while a region is idle. It freezes only while the region is explicitly `PAUSED` or `STALLED`, and resumes from that frozen timeline under the canonical recovery rules. Wall-clock timer eligibility and recovery remain governed separately by [ADR 0072](adr-0072-class-specific-timer-durability-and-recovery.md); an empty tick does not reinterpret wall-clock deadlines as tick/game time.

Logical sleep or fast-forward ranges are deferred until measurements show that physical empty-boundary advancement is materially costly. Adopting ranges requires a separate ADR proving complete durable due-work indexing, pause accounting across the skipped interval, deterministic range materialization and replay, correctness under wake loss, and migration of every heartbeat or tick-progress consumer to range-aware semantics.

## Consequences

- Global effects survive lost wake hints and retries without changing their frozen participant set or duplicating a regional effect.
- Operators and features can observe partial injection, backpressure, waiting paused or stalled regions, and terminal regional outcomes through one reconciliation owner.
- Global caps and fair regional admission bound fan-out pressure, but a large effect may take several cadence boundaries to inject and finish.
- A global wake does not weaken regional isolation, health gates, fairness, or cadence semantics.
- Idle tick/game time remains simple and explicit, while empty regions avoid domain RPCs, entity locks, Redis pending work, and effect-batch creation.
- Every idle region still incurs one small durable fenced write per cadence boundary.
- Sleep and fast-forward optimizations remain unavailable until their indexing, replay, pause, wake-loss, and consumer-migration contracts are accepted and proven.

## Alternatives Considered

### Run Every Full Pipeline Tick

Rejected because an empty region does not need domain resolution, entity locking, pending coordination, or effect-batch machinery merely to preserve its timeline. A fenced watermark or heartbeat establishes the required physical advancement with less work.

### Sleep and Fast-Forward Logical Tick Ranges

Deferred because a missed wake, incomplete due index, pause boundary, replay, or range-unaware heartbeat consumer could silently skip gameplay work or misstate region health. Measured cost and the separate ADR and proof described above are required before adopting this optimization.

### Poll Only Active Regions

Rejected because activity hints are not a complete durable clock or due-work index. An idle region could stop advancing tick/game time, miss a timer or global child after a lost hint, or resume on a non-canonical timeline.

## Implementation and Proof Obligations

Proof must cover atomic durable parent creation with the frozen affected region set and topology generation; idempotent child creation and injection; parent, child, and visible partial-injection states; crash and retry at every fan-out boundary; lost, duplicate, delayed, and reordered wake hints; topology change after acceptance; reconciliation-owner failover; global cap and backpressure behavior; deterministic fair regional admission; and parent outcome derivation from waiting, applied, and feature-terminalized children.

Regional proof must show that force-tick wakes target only the next canonical cadence boundary, never create off-cadence execution, and cannot bypass `PAUSED` or `STALLED`. It must cover both child waiting and each supported feature's declared terminalization behavior.

Idle proof must cover one fenced durable advancement per empty cadence boundary; lease loss, takeover, duplicate attempt, and replay; races between empty-boundary commitment and newly due work; no domain RPC, entity lock, Redis `pending`, or effect batch for a truly empty tick; continued tick/game-time timer and deadline ordering while idle; explicit freeze and resume in `PAUSED` and `STALLED`; and the separate ADR 0072 wall-clock behavior.

The current implementation, reconciliation surface, admission controls, idle watermark path, and focused runtime proof are not claimed by this decision.

## Reversibility and Revisit Triggers

Global caps, fairness weights, and wake mechanisms may be calibrated without changing durable parent and child authority or cadence semantics. Revisit fan-out when measured load requires a different bounded admission shape or topology evolution requires a new participant-selection contract.

Revisit physical idle advancement only when measurements show material storage or scheduling cost. Any sleep or fast-forward replacement requires the separate ADR and durable due-index completeness, pause accounting, range materialization and replay, wake-loss, and heartbeat-consumer migration proof defined above.
