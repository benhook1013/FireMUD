# ADR 0061: Single-Owner Spatial Mutations Across Split Authority

## Status

Accepted

## Implementation Status

World and Entity ownership seams, movement primitives, and some idempotency proof exist, but the complete attested targeting, durable actor barrier, exact owner preconditions, and cross-service crash/reconciliation proof for `DROP`/`PICKUP` remain incomplete. The causal-floor presentation contract is target-state and must not be used as a mutation precondition.

## Canonical Design

- [Spatial and Ambient Effects Catalog](../system-architecture-spatial-and-ambient-effects-catalog.md)
- [Transaction Strategies](../system-architecture-transactions.md)

## Decision Record

- Decision date: 2026-07-19
- Decision key: `SPATIAL-01`
- Primary capability: `GR-2.3` authoritative spatial and ambient state
- Affected capabilities: `GR-2.2`, `GR-3.2`, `GR-4.1`, `SF-2.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review with independent contract validation and monolithic, transferred-item, Entity-location, and flexible-owner alternative analysis
- Human review status: Completed
- Human review date: 2026-07-19
- Human review disposition: Revised
- Review source: `SPATIAL-01`

## Context

Spatial state is not one aggregate. Room topology, occupancy, actor location, ambient facts, item identity, inventory, equipment, containment, and room-ground holders have different invariants and scaling boundaries. A broad statement that World Management owns “spatial state” obscures Entity Management's containment authority.

The split does not require a distributed write for every common action. Pure movement changes World-owned actor location; drop and pickup change Entity-owned item containment. Cross-service evidence, deterministic effects, and reconciliation protect admission and derived reactions without inventing a second mutation participant.

## Decision

Authority is split as follows:

- World Management owns topology, character/NPC location, occupancy, and persistent ambient facts.
- Entity Management owns item identity, inventory, equipment, containment, and synthetic room-ground holders keyed by `RoomInstanceRef`.
- Game Logic resolves actions and composes presentation; it owns no competing spatial persistence.
- Game Session owns the stable root effect identity and durable logical outcome/reconciliation.

Each logical mutation declares its actual authoritative participants. A single-owner mutation does not add another required mutation participant merely because it consumed evidence from another service.

- `MOVE` mutates World location/occupancy in one World transaction. Pure movement has no Entity mutation participant.
- `DROP` and `PICKUP` mutate the item's holder in one Entity transaction. They use the existing World `TargetingFactSnapshot` actor-location/location-version token, admitted for the same room/epoch and validated under the Game Session actor/executor fence before Entity commits; Entity also checks the expected holder and aggregate version. Stale evidence re-resolves under the same root `EffectId` and immutable request digest, without transferring item authority to World.

Every owner derives a participant guard identity from the root `EffectId`, typed operation, and target aggregate, and binds it to an immutable request digest and durable result. Same guard and same request replays the prior result; the same guard with a different operation, target, or digest fails closed.

Correctness mutations carry exact scope, epoch, and relevant expected state:

- movement includes expected current location and World location/aggregate version;
- drop and pickup include World-authoritative actor-location evidence plus expected current holder and Entity aggregate version; and
- ambient changes include the owning World aggregate and expected version.

Derived ambient or gameplay reactions use deterministic child effect identities and declare whether they are required or optional. Only declared required participants delay logical player success. Presentation reads use ADR 0059's causal floor and distinct component versions, never service-local snapshot equality; that presentation causal floor is not a mutation guard and is never substituted for the World targeting token.

## Consequences

- The one-location and one-holder invariants each remain inside one owning database transaction.
- Common movement and item transfers do not pay for a multi-owner mutation protocol.
- Cross-service evidence can become stale, so exact preconditions may reject and retry an action.
- New mechanics must identify their owner, preconditions, required participants, and child reactions before adoption.
- The existing catalog and implementation need stronger guards, preconditions, and focused failure proof.

## Alternatives Considered

### Monolithic Spatial Service

Rejected despite simpler local reads and transactions for a typical MUD workload because it couples topology, ambient simulation, actor location, every item, inventory, and equipment into one database and failure/scaling boundary. It remains the strongest simplification alternative if the service split proves operationally unjustified.

### World Owns Room-Ground Items

Rejected because item authority would transfer between World and Entity on every drop and pickup, making the one-holder invariant distributed.

### Entity Owns Actor Location

Rejected because World would lose local authority over occupancy, traversal, placement, capacity, and room targeting; it relocates rather than removes the split.

### Flexible Authority Per Effect

Rejected because content-defined ownership would create competing tables and effect-specific failure semantics.

### Weaker Guards and Best-Effort Preconditions

Rejected because at-least-once delivery and stale room evidence could duplicate or apply a mutation in the wrong location.

## Implementation and Proof Obligations

Proof must cover digest-conflict rejection, duplicate replay, stale epoch/location/holder/version, move without an Entity mutation, drop/pickup without World item ownership, no double location or holder, deterministic required and optional child effects, crash and reconciliation, and causal-floor room presentation. The effect catalog is the required inventory for new spatial and ambient effect families.

## Reversibility and Revisit Triggers

Adding an effect family is local if it follows an existing owner. Revisit the service split if measured operational cost exceeds its isolation value or a concrete aggregate repeatedly requires atomic World and Entity writes; changing authority then requires an explicit live-state migration design.
