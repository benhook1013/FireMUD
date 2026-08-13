# FireMUD System Architecture: Spatial & Ambient Effects Catalog

This document defines the **required contracts** for tick-driven (and tick-adjacent) effects that touch both world-state and entity-state. It exists to prevent partial-commit corruption under at-least-once delivery and to make reconciliation deterministic.

Effects described here are **not** optional guidance: any new implementation that introduces a spatial or ambient effect must add an entry to this catalog before the effect is used by runtime gameplay.

## Common Requirements (All Effects)

- Every effect has a canonical `EffectId` computed by Game Session and propagated unchanged to all participating services. For a deterministic effect plan, its root identity combines the admitted command identity with the plan ordinal allocated when the plan is created; retries never allocate a new ordinal. See `design/architecture/system-architecture-transactions.md`.
- Every effect must be scoped by instance identifiers. For room-scoped effects, this is `RoomInstanceRef = (tenantId, gameInstanceId, roomInstanceId)`. See `design/architecture/system-architecture-identifier-glossary.md`.
- Every authoritative participant derives a durable guard identity from the root `EffectId`, typed operation, and target aggregate, and binds it to an immutable request digest and durable result. Same guard plus the same request replays that result; reuse with a different operation, target, or digest fails closed.
- Correctness-sensitive mutations carry exact scope, epoch, and relevant owner-state preconditions such as current location, holder, and aggregate version. A presentation read fence is not a mutation precondition.
- Single-owner mutations name only their actual mutation owner. Derived reactions use deterministic child effect identities and declare whether they are required or optional; optional reactions do not delay logical player success.
- The default reconciliation policy is **retry until convergence using the same `EffectId`**. Do not generate compensating deletes inside the tick loop.
- Cross-service room reads used to render player-visible outcomes (for example `LOOK`) carry one causal floor containing tenant, game instance, room, region epoch, and committed tick. World Management and Entity Management must match that scope and epoch, prove that they have applied through at least the requested tick, and return their distinct actual component versions. Component-version equality is neither required nor meaningful; bounded newer skew is exposed in the composite snapshot identity. Services return `READ_FENCE_MISMATCH`, `STALE_READ_FENCE`, or `READ_FENCE_UNAVAILABLE` for mixed scope/epoch, below-floor data, unavailable evidence, or bounded-policy expiry rather than mixing invalid data. Correctness-sensitive mutations use exact owner-specific preconditions instead. Canonical semantics are defined in [ADR 0059](./decisions/adr-0059-causal-floor-cross-service-presentation-reads.md) and `design/architecture/system-architecture-identifier-glossary.md`. The current same-token comparison seam does not yet implement this contract.

## Spatial Effects

### Move (Actor Relocation)

Documents: `design/architecture/microservices/world-management-service/README.md`, `design/architecture/microservices/entity-management-service/README.md`

Required inputs:

- `EffectId`
- `actorEntityId` (the character/NPC entity id)
- `fromRoomInstanceRef`, `toRoomInstanceRef`
- expected `regionEpoch`, current World location, and relevant World location/aggregate version

Required writes:

- **World Management (authoritative occupancy/location)**
  - Update `character_location` / `npc_location` so the actor’s location becomes `toRoomInstanceRef`.
  - Update occupancy projections so `ListRoomOccupants(toRoomInstanceRef)` includes the actor and `ListRoomOccupants(fromRoomInstanceRef)` does not.
- **Entity Management (containment)**
  - No required write for pure movement unless the game models “carried room state” as containment. Movement must not be implemented by moving an entity between synthetic room-ground containers.

Reconciliation:

- Retry World Management with the same participant guard until the World transaction converges or the durable effect is terminalized. Pure movement has no Entity mutation or acknowledgement to reconcile.

### Drop (Inventory → Ground)

Required inputs:

- `EffectId`
- `actorEntityId`
- `itemEntityId`
- `roomInstanceRef` (where the drop occurs)
- World-authoritative actor-location evidence for the admitted room and epoch
- expected current holder and relevant Entity aggregate version

Required writes:

- **World Management**
  - No required write unless the game also models a world-side “sound/door/hazard reaction”; such reactions must be expressed as separate ambient effects with their own `EffectId` (derived deterministically from the parent).
- **Entity Management**
  - Move `itemEntityId` into the synthetic room-ground container for `roomInstanceRef`.

Reconciliation:

- Retry the Entity mutation with the same participant guard until it converges or is terminalized. Derived ambient reactions have deterministic child effect identities and explicit required/optional classification; do not undo a committed item move to compensate for an optional reaction.

### Pickup (Ground → Inventory)

Required inputs:

- `EffectId`
- `actorEntityId`
- `itemEntityId`
- `roomInstanceRef`
- World-authoritative actor-location evidence for the admitted room and epoch
- expected room-ground holder and relevant Entity aggregate version

Required writes:

- **World Management**
  - No required write.
- **Entity Management**
  - Move `itemEntityId` out of the synthetic room-ground container for `roomInstanceRef` into the actor’s inventory container.

Reconciliation:

- Retry the Entity mutation using the same participant guard until it converges or is terminalized. Return replay/no-op only when the stored operation, target, and request digest match; a different intervening move is stale/conflict rather than replay.

## Ambient Effects (World Management Authoritative)

Ambient effects are durable mutations to world instance facts such as doors, hazards, and weather. World Management owns those typed runtime facts and their versions; Game Logic owns their interpretation and gameplay consequences, while Game Design owns authored defaults. Scripts, automation, and operators submit effect intent rather than owning or directly writing runtime state. Correctness-bearing changes pass through Game Session's durable effect admission and outcome/reconciliation path even when World is the only mutation participant. World applies exact fenced preconditions and an operation/aggregate/request-digest-bound idempotency result through typed mutation contracts such as `ApplyRoomAmbientStatePatch`; see [ADR 0060](./decisions/adr-0060-world-owned-ambient-facts-and-logic-owned-consequences.md).

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
- the target runtime region identity (must never be a version-scoped template identifier); room weather is derived from World Management's authoritative room-to-region membership
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
