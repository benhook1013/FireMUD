# ADR 0099: Bounded Atomic Generation With Staging for Large Outputs

## Status

Accepted

## Implementation Status

Bounded atomic visibility, request uniqueness, scope fencing, and ownership-safe cleanup are target state. Current generation paths do not yet prove enforced row/byte admission limits, complete atomic reader visibility, digest/count-checked large-output staging, terminal population failures, or ownership-safe abandoned-run cleanup.

## Canonical Design

- [Procedural Generation](../system-architecture-procedural-generation.md#integration-guidelines)
- [World Management Procedural Generation Control](../microservices/world-management-service/procedural-generation-control.md)
- [World Management Runtime and Data](../microservices/world-management-service/runtime-and-data.md)

## Decision Record

- Decision date: 2026-07-20
- Decision key: `PROC-04`
- Primary capability: `GR-2.1` world topology, rooms, regions, and runtime instances
- Affected capabilities: `GR-1.4`, `SF-2.3`, `AS-1.5`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led review of atomic visibility, transaction bounds, staged finalization, retry convergence, terminal population failures, and cleanup ownership
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `PROC-04`

## Context

Generation can produce a connected topology graph plus follow-up population work. A crash, duplicate request, or ownership change must not expose half a graph, apply the same logical request twice, or let cleanup erase player-created or manually authored state. At the same time, requiring every graph to fit one unbounded database transaction creates avoidable lock, memory, log, and timeout risk, while requiring a complete staging subsystem before measured output size needs it adds premature machinery.

The durable invariant is atomic reader visibility and replay-safe convergence, not one persistence mechanism for every graph size.

## Decision

World Management fully constructs and validates a generation result before beginning the transaction that can make it visible. Validation includes graph integrity, scope and tenant ownership, configuration and schema support, output row and byte counts, and any digest used for finalization. The visibility transaction performs no generator execution or network calls.

An output within enforced row and serialized-byte limits may be persisted in one owner-local database transaction. That transaction uses a stable business `generationRequestId`, a uniqueness constraint for the target scope and request, and the current scope fence or compare-and-set epoch. It writes the complete result and advances its generation state atomically. Readers therefore observe either the prior authoritative scope or the complete new scope, never an intermediate graph. A duplicate request returns or converges on the recorded outcome; a stale writer fails its fence rather than overwriting a newer scope.

Outputs that cannot satisfy the bounded transaction limits use private staging keyed by the generation run. Chunked staging is never reader-visible authoritative topology. Each chunk contributes to a canonical output digest and expected row counts. A short owner-local finalize transaction checks the stable request identity, uniqueness, current scope fence, terminal staging state, digest, counts, and declared scope, then atomically installs or selects the staged graph and advances generation state. Missing, duplicate, or inconsistent staged content fails closed and remains non-visible.

The initial implementation may reject a graph deterministically as oversized instead of supporting chunked staging. Staging becomes required before the product admits outputs above the tested single-transaction row or byte limits; callers may not bypass those limits.

After topology is committed, runtime population is replayed with its original stable effect identities until every retryable item converges without duplicate effects. A permanently invalid population item reaches an explicit terminal failure with durable diagnostics; it is not retried forever and does not make committed topology partially disappear. Cleanup and abandonment may delete only objects carrying explicit generation ownership for that run and scope. They must never infer ownership from location or broad scope membership and must never delete player-created, manually authored, or otherwise non-generation-owned state. Whole-instance deletion remains permissible only for an explicitly ephemeral instance under its lifecycle authority.

## Consequences

- Readers receive all-or-nothing topology even when generation or persistence is retried after failure.
- Small, measured outputs avoid staging overhead while large outputs have a bounded finalize transaction.
- Stable identities, uniqueness, and scope fences make duplicate delivery and ownership changes converge safely.
- Oversized graphs can be unavailable in the initial slice until staging is implemented.
- Staging requires digest/count bookkeeping, abandoned-run retention, and ownership-aware garbage collection.
- Retryable population may remain incomplete after topology becomes visible, while permanent invalidity becomes a diagnosable terminal result rather than an endless retry loop.

## Alternatives Considered

### Put Every Output in One Unbounded Transaction

Rejected because validation or network work inside a long transaction and unbounded row or byte volume can create excessive locks, database log pressure, memory use, and timeouts. A single transaction is acceptable only inside enforced and proved limits.

### Require Chunked Staging for Every Output Immediately

Rejected as an initial prerequisite because bounded graphs can meet the invariant with a simpler owner-local transaction. The design preserves staging as the required scale path without forcing it before supported output sizes need it.

### Write Chunks Directly Into Reader-Visible Topology

Rejected because readers could observe partial or internally inconsistent graphs, and a retry or stale writer could mix generations.

### Compensate Failures With Broad Deletes

Rejected because ownership inference is unsafe once players, creators, or later workflows can modify the same scope. Replay-safe convergence and explicit generation ownership provide a narrower recovery boundary.

## Implementation and Proof Obligations

Enforce tested row and serialized-byte admission limits for the single-transaction path. Persist the stable request identity, target scope, output counts and digest, immutable generation inputs, lifecycle state, generation ownership, and scope fence. The database must enforce request uniqueness, and every commit or finalize predicate must reject a stale fence atomically.

Proof must cover readers during commit and finalize; crashes before, during, and after persistence; duplicate delivery; concurrent writers; stale scope fences; exact-limit and over-limit graphs; staging chunk loss, duplication, corruption, and digest or count mismatch; retry convergence without duplicate population; permanent invalid population diagnostics; abandoned staging collection; and attempts to clean up player-created, manually authored, or unrelated state. Network-call and full-validation exclusion from the visibility transaction must also be demonstrated.

The current architecture text describes stable request identities, uniqueness, single-writer scopes, staged finalization, and population retries, but this decision does not claim that enforced size bounds, atomic reader visibility, digest-checked chunked finalization, terminal invalid-population handling, or ownership-safe cleanup are implemented or proved.

## Reversibility and Revisit Triggers

Transaction limits, staging chunk size, retention windows, and digest schema may evolve from measured database behavior while preserving atomic visibility, replay-safe identity, fences, and ownership-safe cleanup. Implement or expand staging before raising admitted output size beyond the proved single-transaction envelope. Revisit the topology only if measured scale makes owner-local staged finalization impractical; do not replace it with partially visible live writes or broad compensating deletion.
