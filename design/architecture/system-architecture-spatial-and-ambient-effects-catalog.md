# FireMUD System Architecture: Spatial & Ambient Effects Catalog

This document defines the **required contracts** for tick-driven (and tick-adjacent) effects that touch both world-state and entity-state. It exists to prevent partial-commit corruption under at-least-once delivery and to make reconciliation deterministic.

Effects described here are **not** optional guidance: any new implementation that introduces a spatial or ambient effect must add an entry to this catalog before the effect is used by runtime gameplay.

## Common Requirements (All Effects)

- [Transaction Strategies](./system-architecture-transactions.md) owns split spatial authority and operation-bound effect behavior. [Identifier Glossary](./system-architecture-identifier-glossary.md#cross-service-effect-identity) owns root `EffectId` and participant guard identity, while its [causal-read fence contract](./system-architecture-identifier-glossary.md#cross-service-causal-read-fence-identity) owns the causal floor and composite component-version identity. This catalog retains only effect-local writes, guards, and reconciliation consequences.
- Every effect must be scoped by instance identifiers. For room-scoped effects, this is `RoomInstanceRef = (tenantId, gameInstanceId, roomInstanceId)`. See `design/architecture/system-architecture-identifier-glossary.md`.
- Every participating service must commit its durable guard with effect-visible domain rows so matching retries return the prior result and a changed operation, target, or digest fails closed.
- The default reconciliation policy is **retry until convergence using the same `EffectId`**. Do not generate compensating deletes inside the tick loop.
- Presentation reads use the causal floor and actual component versions defined in [Identifier Glossary](./system-architecture-identifier-glossary.md). Current `worldSnapshotId`/`entitySnapshotId` values are scope markers only. Target presentation requests carry the same room scope and epoch floor; participants return their actual component versions, bounded newer skew is allowed, and a participant behind the floor or a mixed scope/epoch is rejected or retried. The composed identity exposes the requested floor plus component versions; it does not claim an exact distributed historical snapshot.

## Spatial Effects

### Move (Actor Relocation)

Documents: `design/architecture/microservices/world-management-service/README.md`, `design/architecture/microservices/entity-management-service/README.md`

Required inputs:

- `EffectId`
- `actorEntityId` (the character/NPC entity id)
- `fromRoomInstanceRef`, `toRoomInstanceRef`

Required writes:

- **World Management (authoritative occupancy/location)**
  - Update `character_location` / `npc_location` so the actor’s location becomes `toRoomInstanceRef`.
  - Update occupancy projections so `ListRoomOccupants(toRoomInstanceRef)` includes the actor and `ListRoomOccupants(fromRoomInstanceRef)` does not.
- **Entity Management (containment)**
  - No required write for pure movement unless the game models “carried room state” as containment. Movement must not be implemented by moving an entity between synthetic room-ground containers.

Reconciliation:

- If EMS succeeds but WMS fails, retry WMS using the same `EffectId` until WMS converges.
- If WMS succeeds but EMS fails, treat EMS as no-op (movement does not require containment writes).

`MOVE` commits World location/occupancy before destination presentation. `DROP` and `PICKUP` commit entirely in Entity against the admitted room scope and a World-authoritative actor-location precondition; an item never has two holders and an actor never has two authoritative locations.

### Drop (Inventory → Ground)

Required inputs:

- `EffectId`
- `actorEntityId`
- `itemEntityId`
- `roomInstanceRef` (where the drop occurs)

Required writes:

- **World Management**
  - No required write unless the game also models a world-side “sound/door/hazard reaction”; such reactions must be expressed as separate ambient effects with their own `EffectId` (derived deterministically from the parent).
- **Entity Management**
  - Move `itemEntityId` into the synthetic room-ground container for `roomInstanceRef`.

Reconciliation:

- If the EMS move succeeds and any follow-up ambient effects fail, retry ambient effects using their effect ids. Do not undo the item move.

### Pickup (Ground → Inventory)

Required inputs:

- `EffectId`
- `actorEntityId`
- `itemEntityId`
- `roomInstanceRef`

Required writes:

- **World Management**
  - No required write.
- **Entity Management**
  - Move `itemEntityId` out of the synthetic room-ground container for `roomInstanceRef` into the actor’s inventory container.

Reconciliation:

- Retry the EMS move using the same `EffectId` until applied. If the item is already moved, treat as replay/no-op.

## Ambient Effects (World Management Authoritative)

Ambient effects are durable mutations to world instance state such as doors, hazards, and weather. They must use effect-shaped mutation contracts (for example `ApplyRoomAmbientStatePatch(RoomInstanceRef, EffectId, patch)`), not direct table writes.

### Door Toggle

Required inputs:

- `EffectId`
- `roomInstanceRef`
- `doorId`
- `targetState` (OPEN/CLOSED/LOCKED)

Required writes:

- **World Management**
  - Apply the door state mutation under an idempotency guard keyed by `EffectId`.
  - **Target-state only:** advance the World-owned ambient component version used in the composite `LOOK` identity so the Game Session presentation cache can invalidate. The current `worldSnapshotId` scope marker provides no freshness proof and is not a cache-invalidation authority.

Reconciliation:

- Retry WMS until the door state matches `targetState`. If the door is already in that state, treat as replay/no-op.

### Weather Update

Required inputs:

- `EffectId`
- region- or room-scoped instance identifiers (must never be version-scoped template identifiers)
- new weather state (typed, schema-versioned)

Required writes:

- **World Management**
  - Persist the typed weather update and advance the relevant World-owned ambient component version.

Reconciliation:

- Retry WMS until the weather state matches the intended value for the effect.

### Hazard State Update (Gameplay-Authoritative)

Required inputs:

- `EffectId`
- `roomInstanceRef`
- `hazardId`
- `targetState` (ACTIVE/INACTIVE)

Required writes:

- **World Management**
  - Persist hazard state as typed ambient room state under an idempotency guard keyed by `EffectId`.
  - **Target-state only:** advance the World-owned ambient component version used in the composite `LOOK` identity so downstream LOOK/gameplay caches invalidate deterministically. The current `worldSnapshotId` scope marker provides no freshness proof and is not a cache-invalidation authority.

Read/API contract:

- Hazard state used by gameplay is authoritative in World Management and exposed via typed ambient fields in `GetRoomSnapshot`.
- **Target-state only:** Game Logic must use the World component version in the composite identity as the cache validator for hazard reads; when it advances, cached hazard state is stale. The current scope-derived `worldSnapshotId` marker is not freshness proof and must not validate a cache.
- Game Logic and Automation & Scripting must not maintain independent authoritative hazard tables or map-only hazard interpretations.

Reconciliation:

- Retry WMS with the same `EffectId` until hazard state matches `targetState`.
