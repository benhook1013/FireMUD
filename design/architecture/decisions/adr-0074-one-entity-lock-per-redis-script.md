# ADR 0074: One Entity Lock per Redis Script

## Status

Accepted

## Implementation Status

The one-lock rule is target state; registry rejection, caller migration, and focused proof that no multi-lock sequence remains are incomplete.

## Canonical Design

- [Redis Lua Patterns](../system-architecture-redis-lua-patterns.md)
- [Tick Concepts and Invariants](../system-architecture-tick-concepts-and-invariants.md)
- [Redis Design Checklist](../system-architecture-redis-design-checklist.md)

## Decision Record

- Decision date: 2026-07-19
- Decision key: `TICK-10`
- Primary capability: `GR-1.3` tick coordination and entity locking
- Affected capabilities: `SF-2.2`, `GR-4.1`, `PO-4.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review with independent lock-contract validation and rare-whitelist and arbitrary-lock alternative analysis
- Human review status: Completed
- Human review date: 2026-07-19
- Human review disposition: Revised
- Review source: `TICK-10`

## Context

Redis entity locks coordinate tick attempts; they do not own gameplay invariants. The current tick model serializes one in-flight tick per region and does not provide general concurrent entity execution. A multi-lock facility would therefore add acquisition, release, TTL, contention, and failure semantics before the runtime has a concurrency model that benefits from it.

Multi-entity gameplay remains necessary, but its correctness belongs in owning PostgreSQL transactions or durable effect coordination rather than Redis lock lifetime.

## Decision

Each Redis tick Lua invocation may acquire at most one entity lock. This is a hard initial rule:

- there is no multi-entity-lock whitelist or configurable lock-count cap;
- acquiring several entity locks piecemeal across Lua invocations is prohibited; and
- a script must fail closed if its declared key set or operation would require more than one entity lock.

When one service owns a multi-entity invariant, it applies the complete mutation in one owning PostgreSQL transaction. Examples include transfers between Entity-owned holders or another operation whose affected aggregates can be committed atomically by one authority.

When authority is split, the command uses:

- exact aggregate identity, scope, epoch, version, holder, and location preconditions as applicable;
- stable durable effect identity and operation/request-digest-bound owner guards;
- decomposed durable effect legs with declared required and optional outcomes;
- bounded retries and reconciliation; and
- a reservation, saga, or other tick-adjacent durable workflow where the invariant cannot tolerate ordinary partial convergence.

Redis entity locks are a coordination optimization. They do not provide cross-database atomicity and do not replace current lease possession, the durable executor fence, exact owner preconditions, durable idempotency guards, or command-level outcome reconciliation.

Trade, grapple, group movement, and multi-target combat remain supported through the owning-database and durable-effect model. This decision forgoes only a possible lower-latency Redis reservation optimization for an operation that would otherwise try to hold several entity locks at once.

The current serial one-region-tick model and absence of a general dependency/wave plan for concurrent entity execution make that optimization unnecessary initially.

A future bounded atomic multi-lock Lua facility requires a new ADR. It must be justified by measured concurrent contention and arrive with:

- a declared dependency/wave concurrency model;
- a hard atomic key-count bound and one-slot proof;
- Redis event-loop latency and script-complexity budgets;
- lock TTL, lease-loss, contention, and partial-attempt proof; and
- failure, replay, cleanup, and observability contracts.

## Consequences

- Lock scripts remain constant-sized, shard-local, easier to audit, and less likely to block the Redis event loop.
- Piecemeal acquisition cannot introduce hold-and-wait deadlocks or partial lock sets.
- Multi-aggregate correctness remains with the database or durable workflow that can actually enforce it.
- Split-authority actions may incur extra durable effect, retry, or workflow latency.
- Some future concurrent mechanics may miss a low-latency reservation optimization until measured need and a concurrency model justify it.
- Ordinary serial tick actions pay no new per-action cost from this rule.

## Alternatives Considered

### Rare Whitelisted Multi-Lock Scripts

Rejected initially because a whitelist, count declaration, deterministic ordering, cleanup rules, and CI enforcement would preserve complexity for an optimization the serial executor does not need. This remains the bounded future alternative requiring a new ADR and proof.

### Arbitrary Multi-Entity Locks

Rejected because unbounded or piecemeal locking increases Redis event-loop work, contention, TTL and cleanup ambiguity, and deadlock risk without creating authoritative cross-database atomicity.

### Treat Redis Locks as the Gameplay Transaction

Rejected because lock loss, executor failure, or cross-service partial commit can outlive Redis coordination. Only owning transactions, fences, guards, and durable workflows establish gameplay truth.

## Implementation and Proof Obligations

The Lua registry and CI must reject scripts declaring or receiving more than one entity-lock key and reject piecemeal multi-lock patterns. Callers must use shared script categories and canonical key helpers.

Prove one-lock acquisition, replay, stale lease and lock rejection, lock expiry during an attempt, executor-fence rejection, cleanup after failure, registry and CI enforcement, and absence of multi-lock call sequences. Prove representative trade, grapple, group movement, and multi-target combat through owning transactions or durable effect/workflow paths, including exact stale-precondition rejection, retry, partial outcome, and reconciliation behavior.

The current implementation, registry enforcement, gameplay-family migration, and focused proof are not claimed by this decision.

## Reversibility and Revisit Triggers

Revisit only when a dependency/wave concurrency model exists and measurements show that durable owner/effect coordination creates material avoidable latency or contention for a concrete command family. Any relaxation requires the new ADR and the bounded atomic Lua and failure proof defined above.
