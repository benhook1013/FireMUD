# ADR 0054: Split Spatial Authority with Causal Read Composition

## Status

Accepted

## Implementation Status

The decision is accepted; current LOOK composition proves only bounded scope equality, not the target causal-floor/composite-version contract. The current proto seam still carries scope markers rather than the target `CausalReadFence`, `servedThroughTickId`, and component-version fields, and World-authoritative movement/targeting proof remains incomplete, including the durable barrier and actor-lock/executor-fence attestation flow.

## Canonical Design

- [Transaction Strategies](../system-architecture-transactions.md)
- [Identifier Glossary](../system-architecture-identifier-glossary.md)

## Decision Record

- Decision date: 2026-07-19
- Decision key: `TICK-04`
- Primary capability: `SF-2.3` Cross-service runtime consistency and effect convergence
- Affected capabilities: `GR-2.2`, `GR-3.2`, `GR-4.1`, `GR-1.4`, `SF-1.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `TICK-04`, including split-authority validation and monolithic-authority alternative passes
- Human review status: Completed
- Human review date: 2026-07-19
- Human review disposition: Revised
- Review source: `TICK-04`

## Context

A monolithic spatial service could make room reads and spatial mutations local, but would combine topology, occupancy, ambient state, inventory, equipment, containment, and ground items into one scaling and failure domain. Moving only ground items to World would make every drop and pickup a cross-owner transfer.

The existing split is sound, but two contracts are too weak. Reusing an `EffectId` without binding its operation and payload can turn a collision into a false replay. The current LOOK “fence” is effectively a room-scope equality string, not evidence that independent databases served one exact temporal snapshot. Spatial targeting also needs an explicit critical section: a location fact must not be validated and then used after a same-actor movement has interleaved.

## Decision

### Authority

- World Management exclusively owns room topology, character/NPC location, occupancy, and persistent ambient world state.
- Entity Management exclusively owns inventory, equipment, containment, and room-ground holders keyed by `RoomInstanceRef`.
- Game Logic resolves actions and composes reads; it owns no competing spatial state.
- Game Session owns durable cross-service effect intent, required-participant status, retry, and reconciliation.
- The canonical Weather aggregate scope (region-scoped versus room-scoped) remains an explicit unresolved World-owner decision; this ADR does not choose it.

`MOVE` commits World-owned location and occupancy before destination presentation is resolved. For `DROP` and `PICKUP`, Game Session's durable in-flight barrier and actor-lock/fence gate carry World attestation evidence through the Entity-local holder commit, binding `regionId` from Game Session's durable region authority alongside `RoomInstanceRef`, `regionEpoch`, `executorFence`, the same root `EffectId`, and the unchanged `requestDigest`; World validation and Game Logic re-resolution preserve that binding, and Entity verifies it at commit. Lock expiry, owner crash, or fence change leaves the barrier reconciliation-required and blocks a conflicting `MOVE` until terminal evidence. A later valid `MOVE` is allowed after Entity commit and barrier terminalization. The detailed barrier/handoff contract is in [Transaction Strategies](../system-architecture-transactions.md#drop-pickup-targeting-and-actor-fence-critical-section); this closes TOCTOU through durable evidence and fencing, not a distributed World/Entity transaction. An item never has two holders and an actor never has two authoritative locations; those invariants remain within one owning transaction rather than reconciliation.

### Effect Identity and Participant Guards

For a root mutation, Game Session assigns one stable root `EffectId` to the logical effect and persists intended pre/post state and required participants. Each participant derives a deterministic guard identity from the root effect, typed operation, and target aggregate. Its durable guard binds that identity to an immutable request digest and stored outcome. The separate [ADR 0182 proposal](./adr-0182-deterministic-effect-id-allocation-and-replay-binding.md) is not accepted target state and may not be used as an implementation requirement.

- Same identity plus the same request returns the prior durable result.
- Same identity with a different operation, target, or request digest fails closed.
- Derived reactions use deterministic child effect identities rather than accidental reuse. Any future allocator or replay binding remains subject to the non-authoritative [ADR 0182 proposal](./adr-0182-deterministic-effect-id-allocation-and-replay-binding.md).
- Participant acknowledgement means the guard and effect-visible domain rows committed together.
- Player success waits for all declared required participants under ADR 0053.

### Read Composition (Superseded by ADR 0059)

The presentation-read contract recorded in this subsection is superseded by [ADR 0059](./adr-0059-causal-floor-cross-service-presentation-reads.md), which is canonical for causal-floor requests, served-through proof, component versions, and presentation-read acceptance. This ADR retains the split spatial/effect identity, mutation precondition, and durable targeting/barrier decisions; those mutation rules do not use the presentation causal floor as a mutation precondition.

Presentation reads follow ADR 0059 exclusively; this ADR retains no independent causal-floor, served-through, component-version, or acceptance rules.

### Mutation Preconditions

Correctness-sensitive mutations carry exact expected scope, epoch, location, and relevant aggregate versions or attestations. The owner fails closed when those preconditions are stale.

## Consequences

- Domain ownership remains cohesive without a monolithic spatial database.
- Movement, drop, and pickup keep their uniqueness invariants inside one owner transaction.
- Cross-service effects require durable operation/digest-bound guards and reconciliation state.
- LOOK remains available without distributed MVCC, while its composite identity honestly exposes component versions.
- Correctness-sensitive mutations may fail/retry more often when location or aggregate preconditions change.

## Alternatives Considered

### Monolithic Spatial Service

Rejected because it combines unrelated topology and item domains, couples scaling/failure, and makes later separation harder.

### World Owns Ground Items

Rejected because drop and pickup would transfer item authority between World and Entity instead of remaining one Entity transaction.

### Exact Equality Token for Every Read

Rejected because the current token proves scope rather than time, while genuine cross-database historical equality would require a substantially heavier snapshot protocol than presentation needs.

## Implementation and Proof Obligations

Proof must cover guard request-digest mismatch, duplicate replay, crash after one participant, MOVE before destination LOOK, DROP/PICKUP against stale location and re-resolution through Game Logic under the same root `EffectId`, World-attestation binding and Entity rejection, actor-lock lease expiry, owner crash, fence-change fencing, old Entity commit versus new MOVE, barrier handoff/reconciliation and terminal evidence, a valid MOVE after commit, no double holder/location, participant reconciliation, PICKUP replay only for the matching participant guard/digest/exact destination (with another holder treated as conflict/stale/reconcile), same-floor presentation, served-through-floor validation, opaque component-version handling, mixed region/scope/epoch rejection, and lagging-participant retry.

## Reversibility and Revisit Triggers

Component versions and causal-floor fields can evolve without changing ownership. Revisit if causal-read retries materially harm gameplay, one service becomes an operational bottleneck, or a feature truly requires exact historical multi-service snapshots.
