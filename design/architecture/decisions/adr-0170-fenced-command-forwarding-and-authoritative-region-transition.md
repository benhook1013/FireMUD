# ADR 0170: Fenced Command Forwarding and Authoritative Region Transition

## Status

Accepted

Supersedes [ADR 0011](./adr-0011-gameplay-session-front-end-and-region-execution.md).

## Implementation Status

This decision is partially implemented. Front-door routing and game-instance fence seams exist, but region-partitioned owner forwarding, generation-bound output, bounded failure handling, and focused proof remain gaps. The authoritative implementation and proof status for `SESSION-01` is [`GR-1.1` in the Game Session Runtime and Tick Coordination tracker](../../project-management/implementation-tracking/game-session-runtime-and-tick-coordination.md#capability-status).

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-21
- Human review disposition: Revised
- Review source: `SESSION-01`
- Decision date: 2026-07-21
- Decision key: `SESSION-01`
- Primary capability: `GR-1.1` session routing and execution
- Affected capabilities: `GR-1.3`, `AA-2.1`, `SF-1.2`, `SF-2.3`, `PO-2.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of socket/executor separation, sequencing versus idempotency, stale ownership, movement transactions, asynchronous output, failure semantics, and current implementation gaps

## Context

ADR 0011 correctly separates session front-end socket ownership from lease-owner mutation, but overstates what per-session sequencing protects and leaves region movement vulnerable to becoming an invented three-party routing transaction. The wider design subsequently established durable `commandId` and effect identities as retry authority and domain-owned spatial/effect results as mutation truth. Current implementation has front-door and fence primitives but not true region-partitioned owner forwarding.

## Decision

A Game Session front-end owns the edge-facing socket, connection-local protocol state, bounded input/output queues, and presentation delivery. The current fenced lease owner for a target region is the only Game Session executor allowed to admit and mutate region-scoped tick work.

The front-end resolves an owner through a directory or lease projection and forwards a request carrying complete gameplay scope: `tenantId`, `gameInstanceId`, `playableStateNamespaceId`, and separately validated server-derived `playableStateScope`; it also carries `commandId`, target region, expected executor fence, session identity, front-end generation, and `sessionSequence`. The namespace is the durable gameplay identity dimension; scope is separately validated routing/authorization/migration-fence evidence and is not a uniqueness key. The directory is a routing hint, not authority. The receiving executor validates current ownership and fence at admission and rejects stale or missing authority.

`sessionSequence` preserves FIFO for commands from one live connection generation. It is not a dedupe key, effect identity, transaction fence, or cross-connection global order. `commandId`, effect identity, and owning domain request identities provide idempotency and ambiguous-result reconciliation. A stale-owner rejection before admission may refresh routing and retry the same logical identity; an ambiguous response never creates a new identity and is reconciled through the authoritative command-status surface.

Movement or another cross-region action is not a source-release/destination-accept/front-end-pointer distributed transaction. The responsible executor coordinates the normal authoritative spatial/effect contract. Once that durable result establishes the character's new location, the front-end updates its execution-region pointer as a derived routing projection. A stale pointer can cause one rejected forward and refresh, but cannot authorize mutation or overwrite the authoritative spatial result.

Asynchronous output is addressed to the active `{sessionId, frontEndGeneration}` registration and carries stable originating command/effect correlation where applicable. Output from a superseded generation is ignored or retained only under the bounded reconnect buffer contract; it must not leak to a replacement controller.

Forwarding queues, retries, stale-route refresh, output buffering, and per-session in-flight work are bounded. Metrics distinguish route refresh, stale-fence rejection, forwarding saturation/timeout, ambiguous command result, output-generation mismatch, and forced close.

## Consequences

- The Gateway remains shard-unaware and ordinary lease movement need not reconnect a client.
- The extra internal hop remains a latency and availability cost when the front-end is not the executor.
- FIFO, idempotency, owner authority, and spatial truth have separate identities instead of overloading `sessionSequence` or a routing pointer.
- Region moves reuse domain mutation semantics and avoid a special three-party commit protocol.
- Current region-partitioned leases, forwarding, generation-bound output, and focused failure proof remain implementation work.

## Alternatives Considered

### Socket Affinity to the Lease Owner

This avoids routine internal forwarding but couples connection movement to lease rebalancing and pushes shard knowledge or reconnect behavior toward the edge.

### Dedicated Session Router Service

This centralizes routing but adds another stateful hot-path authority without eliminating executor fencing. It is not justified for the current scale.

### Three-Party Region Transition Transaction

Source release, destination acceptance, and front-end pointer commit appear explicit but duplicate spatial authority, create partial-commit recovery states, and make routing metadata determine gameplay truth. It is rejected.

## Implementation and Proof Obligations

Proof must cover stale owner-directory data, lease loss before and after admission, lost responses, same-command retries, connection takeover while output is in flight, queue saturation, slow executors, movement across region boundaries, pointer repair after durable spatial success, and absence of duplicate effects. Implementation status remains `partial`: current code has front-door routing and game-instance fence seams but no true region-partitioned forwarding.

## Reversibility and Revisit Triggers

The internal transport and directory may change without changing the authority split. Revisit the front-end model if measured forwarding latency, saturation, or availability cost is material enough that connection affinity or a dedicated router has a better end-to-end failure model.
