# Runtime, Persistence, Concurrency, And Recovery Review

Use this prompt to review how gameplay and automation mutations remain correct through concurrency, retries, partial failures, crashes, replay, and recovery.

Apply the [shared review contract](./00-shared-review-contract.md).

## Starting Sources

- `design/architecture/system-architecture-ticks.md`
- `design/architecture/system-architecture-tick-concepts-and-invariants.md`
- `design/architecture/system-architecture-tick-execution-flows.md`
- `design/architecture/system-architecture-tick-failures-and-operations.md`
- `design/architecture/system-architecture-redis.md`
- the Redis runtime, Lua, ownership, operations, reset, and recovery documents linked from it
- `design/architecture/system-architecture-transactions.md`
- `design/architecture/system-architecture-database-migrations.md`
- `design/architecture/system-architecture-versioning-runtime.md`
- runtime/data and API documents for Game Session, World Management, Entity Management, Game Logic, and Automation & Scripting
- the owning implementation trackers, production code, schemas, and focused proof

## Review

Trace command, movement, entity, effect, combat, timer, schedule, automation, publication, and operator mutation paths. Check:

- the authoritative SQL, Redis, workflow, asset, or derived store for each state transition;
- transaction and compensation boundaries;
- single-writer claims, leases, fencing, epochs, versions, and optimistic concurrency;
- idempotency keys, duplicate delivery, replay, reordering, retry, timeout, dead-letter, and reconciliation behavior;
- crashes before, during, and after durable commit or external handoff;
- cache invalidation, reset, failover, bounded loss, restore, and convergence to durable truth;
- migration compatibility with the canonical pre-v1 data-retention boundary;
- capacity, fairness, bounded fan-out, and work-budget enforcement where correctness depends on them;
- operator-visible recovery evidence and player-visible terminal outcomes; and
- focused negative-path and cross-service proof for the claimed implementation.

Do not treat Redis as durable authority where architecture defines it as coordination or cache. Do not infer atomicity across stores or services without an explicit contract and implementation mechanism.

## Output

Provide:

1. a mutation and failure-path coverage table;
2. race, ownership, fencing, idempotency, replay, partial-commit, and crash-safety findings;
3. data-authority, migration, reset, restore, and reconciliation gaps;
4. missing proof or operator evidence; and
5. the review state required by the shared contract.
