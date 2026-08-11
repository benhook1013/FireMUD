# ADR 0054: Split Spatial Authority with Causal Read Composition

## Status

Accepted

## Implementation Status

The decision is accepted; current LOOK composition proves only bounded scope equality, not the target causal-floor/composite-version contract. The current proto seam still carries scope markers rather than the target `CausalReadFence`, `servedThroughTickId`, and component-version fields, and World-authoritative movement/targeting proof remains incomplete.

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

The existing split is sound, but two contracts are too weak. Reusing an `EffectId` without binding its operation and payload can turn a collision into a false replay. The current LOOK “fence” is effectively a room-scope equality string, not evidence that independent databases served one exact temporal snapshot.

## Decision

### Authority

- World Management exclusively owns room topology, character/NPC location, occupancy, and persistent ambient room state.
- Entity Management exclusively owns inventory, equipment, containment, and room-ground holders keyed by `RoomInstanceRef`.
- Game Logic resolves actions and composes reads; it owns no competing spatial state.
- Game Session owns durable cross-service effect intent, required-participant status, retry, and reconciliation.

`MOVE` commits World-owned location and occupancy before destination presentation is resolved. `DROP` and `PICKUP` reuse the World `TargetingFactSnapshot` location/version token; World validates that token, while Game Session's actor/executor fence protects ordered execution before Entity commits. Stale targeting evidence is re-resolved under the same root `EffectId`. An item never has two holders and an actor never has two authoritative locations; those invariants remain within one owning transaction rather than reconciliation.

### Effect Identity and Participant Guards

Game Session assigns one stable root `EffectId` to the logical effect and persists intended pre/post state and required participants. Each participant derives a deterministic guard identity from the root effect, typed operation, and target aggregate. Its durable guard binds that identity to an immutable request digest and stored outcome.

- Same identity plus the same request returns the prior durable result.
- Same identity with a different operation, target, or request digest fails closed.
- Derived reactions use deterministic child effect identities rather than accidental reuse.
- Participant acknowledgement means the guard and effect-visible domain rows committed together.
- Player success waits for all declared required participants under ADR 0053.

### Read Composition

FireMUD does not claim a globally atomic historical snapshot across World and Entity databases for presentation reads.

Correctness-sensitive mutations carry exact expected scope, epoch, location, and relevant aggregate versions or attestations. The owner fails closed when those preconditions are stale.

For presentation composition such as `LOOK`, Game Session allocates a `CausalReadFence` from durable region commit authority and passes it on `ResolveLook`; Game Logic propagates that fence unchanged to World and Entity. The fence contains at least `(tenantId, gameInstanceId, roomInstanceId, regionEpoch, committedTickId)`. Each participant returns the same scope and epoch, a scoped comparable `servedThroughTickId`, and an opaque local component version. Game Logic accepts a response only when `servedThroughTickId >= requested committedTickId`; it never compares opaque component versions. The composite snapshot identity remains the requested floor plus the World and Entity opaque component versions; `servedThroughTickId` is validation proof, not a component-version ordering value. It never treats equality of scope strings as temporal equality.

Mixing tenant, game instance, room, or epoch, or a response whose `servedThroughTickId` is behind the requested floor, is rejected or retried. A feature requiring exact cross-database read-as-of semantics needs a separate historical snapshot design rather than overloading LOOK. The current proto and proof gaps remain explicit; current scope-marker responses do not establish this target contract.

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

Proof must cover guard request-digest mismatch, duplicate replay, crash after one participant, MOVE before destination LOOK, DROP/PICKUP against stale location and re-resolution under the same root `EffectId`, no double holder/location, participant reconciliation, same-floor presentation, served-through-floor validation, opaque component-version handling, mixed scope/epoch rejection, and lagging-participant retry.

## Reversibility and Revisit Triggers

Component versions and causal-floor fields can evolve without changing ownership. Revisit if causal-read retries materially harm gameplay, one service becomes an operational bottleneck, or a feature truly requires exact historical multi-service snapshots.
