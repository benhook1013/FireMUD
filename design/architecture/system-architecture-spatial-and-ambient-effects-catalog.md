# FireMUD System Architecture: Spatial & Ambient Effects Catalog

This document defines the **required contracts** for tick-driven (and tick-adjacent) effects that touch both world-state and entity-state. It exists to prevent partial-commit corruption under at-least-once delivery and to make reconciliation deterministic.

Effects described here are **not** optional guidance: any new implementation that introduces a spatial or ambient effect must add an entry to this catalog before the effect is used by runtime gameplay.

## Common Requirements (All Effects)

- Every effect has a canonical `EffectId` computed by Game Session and propagated unchanged to all participating services. See `design/architecture/system-architecture-transactions.md`.
- Every effect must be scoped by instance identifiers. For room-scoped effects, this is `RoomInstanceRef = (tenantId, gameInstanceId, roomInstanceId)`. See `design/architecture/system-architecture-identifier-glossary.md`.
- Every participating service must implement a **durable idempotency guard** keyed by `EffectId` so retries become no-ops rather than double-application.
- The default reconciliation policy is **retry until convergence using the same `EffectId`**. Do not generate compensating deletes inside the tick loop.
- For cross-service room reads used to render player-visible outcomes (for example `LOOK`), participants must support one same-scope read fence token. In the current adapter seam, World Management `worldSnapshotId` / `world_snapshot_id` and Entity Management `entitySnapshotId` / `entity_snapshot_id` are deterministic scope markers, not proof of mutation freshness. The target contract maps the World Management marker to a committed `roomSnapshotVersion` freshness fence and propagates that exact token to participants; it is not present in the current request/proto path. Future tick-ledger work may introduce an `asOfTickId` field only by updating the proto and architecture contracts together. Canonical scope/comparison semantics are defined in `design/architecture/system-architecture-identifier-glossary.md`. A participant that cannot satisfy the target requested fence returns `STALE_READ_FENCE` or `READ_FENCE_UNAVAILABLE`; a returned fence difference is a caller-side fresh-snapshot retry condition, never permission to mix data from different fences.

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
  - Advance `worldSnapshotId` for the room/instance so LOOK caching can invalidate.

Reconciliation:

- Retry WMS until the door state matches `targetState`. If the door is already in that state, treat as replay/no-op.

### Weather Update

Required inputs:

- `EffectId`
- region- or room-scoped instance identifiers (must never be version-scoped template identifiers)
- new weather state (typed, schema-versioned)

Required writes:

- **World Management**
  - Persist the typed weather update and advance the relevant snapshot/version fields.

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
  - Advance `worldSnapshotId` for the room instance so downstream LOOK/gameplay caches invalidate deterministically.

Read/API contract:

- Hazard state used by gameplay is authoritative in World Management and exposed via typed ambient fields in `GetRoomSnapshot`.
- Game Logic must treat `worldSnapshotId` as the cache validator for hazard reads; when it changes, cached hazard state is stale.
- Game Logic and Automation & Scripting must not maintain independent authoritative hazard tables or map-only hazard interpretations.

Reconciliation:

- Retry WMS with the same `EffectId` until hazard state matches `targetState`.
