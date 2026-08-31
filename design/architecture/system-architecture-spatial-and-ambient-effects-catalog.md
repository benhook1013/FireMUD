# FireMUD System Architecture: Spatial & Ambient Effects Catalog

This document defines the **required contracts** for tick-driven (and tick-adjacent) effects that touch both world-state and entity-state. It exists to prevent partial-commit corruption under at-least-once delivery and to make reconciliation deterministic.

Effects described here are **not** optional guidance: any new implementation that introduces a spatial or ambient effect must add an entry to this catalog before the effect is used by runtime gameplay.

## Implementation Status

The target `DROP`/`PICKUP` contracts below are not yet fully implemented or proven: the current request and focused proof do not demonstrate the complete durable spatial-barrier and attested-targeting path. Durable remote execution currently covers only bounded effect families and partial recovery; the full cross-region follow-up, isolated reconciliation-worker, and static-topology maintenance-cutover proof remains open. The Weather aggregate selector is also intentionally unresolved, so Weather mutation remains fenced and non-mutating until the World owner accepts its exact scope.

## Common Requirements (All Effects)

- [Transaction Strategies](./system-architecture-transactions.md) owns split spatial authority and operation-bound effect behavior. [Identifier Glossary](./system-architecture-identifier-glossary.md#cross-service-effect-identity) owns root `EffectId` and participant guard identity, while its [causal-read fence contract](./system-architecture-identifier-glossary.md#cross-service-causal-read-fence-identity) owns the causal floor and composite component-version identity. This catalog retains only effect-local writes, guards, and reconciliation consequences. The pending [ADR 0182 proposal](./decisions/adr-0182-deterministic-effect-id-allocation-and-replay-binding.md) is non-authoritative.
- Every effect must be scoped by instance identifiers. For room-scoped effects, this is `RoomInstanceRef = (tenantId, gameInstanceId, roomInstanceId)`. See `design/architecture/system-architecture-identifier-glossary.md`.
- Game Session assigns one stable root `EffectId` to each command-root logical effect. Each participating owner derives its participant-guard identity from the persisted mutation `EffectId`—the root for a root mutation or the persisted generated-child `EffectId` for a child, with the enclosing root retained only as lineage—plus the typed operation and target aggregate, within the owner-declared runtime-family partition: S1/S2 use `(tenantId, playableStateNamespaceId)`, explicit S3 uses `(tenantId, gameInstanceId)`, and an unknown or unclassified family fails closed. The guard binds to the immutable request digest and stored outcome. Matching retries return the prior result, while a changed operation, target, partition, or digest fails closed under the owner contract in [Transaction Strategies](./system-architecture-transactions.md).
- The default reconciliation policy is **retry until convergence using the same persisted mutation `EffectId`**, retaining the enclosing root lineage where applicable. Do not generate compensating deletes inside the tick loop.
- Presentation reads follow the causal-floor and component-proof contract in [Identifier Glossary](./system-architecture-identifier-glossary.md). Current `worldSnapshotId`/`entitySnapshotId` values are scope markers only. Effect entries below record only local invalidation consequences; floor allocation, propagation, response acceptance, and composite-identity rules remain in the canonical contract.
- For World-owned door, weather, and hazard effects, the participant guard, typed ambient-state mutation, and World component-version advance commit in one World-local transaction. A matching replay returns the stored outcome without applying the mutation or incrementing the component version twice. The canonical transaction rule belongs in [Transaction Strategies](./system-architecture-transactions.md); this catalog records only the effect-local consequence and proof obligation.
- Set-state reconciliation must resolve the participant guard within the same owner-declared runtime-family partition by the current persisted mutation `EffectId`, typed operation, exact target aggregate, and immutable `requestDigest`. A matching guard returns its stored outcome; mutable state already matching the requested value is not replay evidence by itself. When a new request has no matching guard but the state is already satisfied, the owner records a new guarded no-op under that request's current `EffectId` and immutable digest. A missing, conflicting, or ambiguous guard remains reconciliation-required rather than being inferred as replay; an unknown or unclassified runtime family fails closed.

## Cross-Region Effects and Reconciliation

Cross-region follow-up/result ownership, timeout arbitration, retry/reconciliation, and topology/reset behavior are canonical in [Tick System and Runtime Design](./system-architecture-ticks.md), [Tick Execution Flows](./system-architecture-tick-execution-flows.md), [Tick Failures & Operations](./system-architecture-tick-failures-and-operations.md), and [Transaction Strategies](./system-architecture-transactions.md). This catalog retains only effect-local consequences and proof obligations.

- Each effect entry identifies its actual mutation participant(s), preserves the root/participant guard and immutable request digest, and declares required or optional local consequences. Durable scheduling of a remote leg is not itself a successful effect.
- A target leg preserves exact target identity and feature preconditions; its owner revalidates them under the current authority and fence. Redis markers are wake-up hints only and are not effect evidence.
- Effect-local proof covers owner-transaction guard/replay, digest-conflict rejection, stale target/precondition rejection, crash/retry convergence, and required/optional outcome reporting. The owner documents define how timeout, late-result, reset, and topology outcomes are recorded.

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
- **Entity Management**
  - Pure movement has no Entity participant or containment write. It must not be implemented by moving an entity between synthetic room-ground containers.

Reconciliation:

- Retry World Management using the same command-root `EffectId` and participant guard until the World location/occupancy mutation converges under the current epoch/fence.
- There is no Entity success/failure or retry branch for pure `MOVE`. An Entity leg exists only for a future MOVE variant that explicitly writes containment; that variant must declare Entity as a participant with its own write, guard, and reconciliation contract.

`MOVE` commits World location/occupancy before destination presentation. `DROP` and `PICKUP` commit entirely in Entity against the admitted room scope, using the shared actor-lock/executor-fence and durable-barrier contract defined in [Transaction Strategies](./system-architecture-transactions.md#drop-pickup-targeting-and-actor-fence-critical-section). Lock expiry or handoff cannot admit a conflicting `MOVE` while the barrier lacks terminal evidence; Game Session owns retry orchestration and invokes Game Logic to re-resolve stale evidence under the same command-root `EffectId`, preserving the `requestDigest`. This catalog records only the effect-local writes and reconciliation consequences. An item never has two holders and an actor never has two authoritative locations.

### Drop (Inventory → Ground)

Required inputs:

- `EffectId`
- `actorEntityId`
- `itemInstanceId`
- `roomInstanceRef` (where the drop occurs)
- expected current item holder and relevant Entity aggregate version
- Derived validation metadata: the World `TargetingFactSnapshot` attestation bound to the root `EffectId`, `actorEntityId`, `RoomInstanceRef`, `regionEpoch`, current `executorFence`, and request digest, plus the Game Session actor-lock/barrier context; these are not a second effect identity.

Required writes:

- **World Management**
  - No required write unless the game also models a world-side “sound/door/hazard reaction”; such reactions must be expressed as separate ambient effects with their own `EffectId` (derived deterministically from the parent).
- **Entity Management**
  - Move `itemInstanceId` into the synthetic room-ground container for `roomInstanceRef`.

Reconciliation:

- Retry the Entity mutation using the same participant guard until it converges or is terminalized. Derived ambient reactions use deterministic child effect identities and explicit required/optional classification; do not undo a committed item move to compensate for an optional reaction.

### Pickup (Ground → Inventory)

Required inputs:

- `EffectId`
- `actorEntityId`
- `itemInstanceId`
- `roomInstanceRef`
- expected current room-ground holder and relevant Entity aggregate version
- Derived validation metadata: the World `TargetingFactSnapshot` attestation bound to the root `EffectId`, `actorEntityId`, `RoomInstanceRef`, `regionEpoch`, current `executorFence`, and request digest, plus the Game Session actor-lock/barrier context; these are not a second effect identity.

Required writes:

- **World Management**
  - No required write.
- **Entity Management**
  - Move `itemInstanceId` out of the synthetic room-ground container for `roomInstanceRef` into the actor’s inventory container.

Reconciliation:

- Retry the EMS move using the same command-root `EffectId` until applied. Treat an already-moved item as replay only when the stored participant guard matches the same command-root `EffectId`, immutable request digest, and exact actor-inventory destination. If the item is held by another actor/container or the destination differs, return a conflict/stale/reconciliation outcome rather than replay/no-op.

## Ambient Effects (World Management Authoritative)

Ambient effects are durable mutations to World-owned runtime facts such as doors, hazards, and weather. World owns the typed fact and authoritative version; Game Logic owns interpretation and gameplay consequences, while Game Design owns authored defaults. Player, script, automation, and operator changes enter through Game Session's durable effect admission and outcome path, even when World is the only mutation participant. They must use typed effect-shaped mutation contracts such as `ApplyRoomAmbientStatePatch`, with exact scope/epoch/version preconditions and an operation/aggregate/request-digest-bound guard, not direct table writes. See [ADR 0060](./decisions/adr-0060-world-owned-ambient-facts-and-logic-owned-consequences.md) and [ADR 0061](./decisions/adr-0061-single-owner-spatial-mutations-across-split-authority.md).

Weather mutation admission and reconciliation remain fenced and non-mutating until the World owner accepts the exact region- versus room-scoped aggregate selector. This includes current `world_event` and `region_instance.weather` write paths; this catalog does not choose that selector.

### Door Toggle

Required inputs:

- `EffectId`
- `roomInstanceRef`
- `doorId`
- `targetState` (OPEN/CLOSED/LOCKED)

Required writes:

- **World Management**
  - Apply the door state mutation under the owner participant guard for the persisted mutation `EffectId`, typed `DOOR_TOGGLE` operation, and target room/door aggregate, bound to the request digest and stored outcome.
  - **Target-state only:** advance the World-owned ambient component version used in the composite `LOOK` identity in that same local transaction so the Game Session presentation cache can invalidate. A matching guard replay returns the prior result without a second version increment. The current `worldSnapshotId` scope marker provides no freshness proof and is not a cache-invalidation authority.

Reconciliation:

- Retry WMS with the same persisted mutation `EffectId` until the participant guard and door mutation converge. The replay/no-op path is valid only when the stored guard matches `DOOR_TOGGLE`, the exact room/door target aggregate, and the immutable request digest. If no matching guard exists but the door is already in `targetState`, issue the current request under a new guard and record a guarded no-op; mutable door state alone never proves replay. A conflicting or ambiguous guard remains reconciliation-required.

### Weather Update

Required inputs:

- `EffectId`
- region- or room-scoped instance identifiers (must never be version-scoped template identifiers)
- The exact target-aggregate selector owned by the World weather contract; this catalog does not choose whether the canonical aggregate is region- or room-scoped.
- new weather state (typed, schema-versioned)

Required writes:

- **World Management**
  - Once the World weather contract has accepted its exact target aggregate selector, persist the typed weather update under the owner participant guard for that aggregate, with the typed operation and immutable request digest, and advance the relevant World-owned ambient component version in the same local transaction. A matching guard replay returns the prior result without a second version increment. Until then, no Weather write is admitted.

Reconciliation:

- After the selector is accepted, retry WMS with the same persisted mutation `EffectId` until the participant guard and weather mutation converge. The replay/no-op path is valid only when the stored guard matches the typed weather operation, the exact World-selected target aggregate, and the immutable request digest. If no matching guard exists but weather is already at the requested value, issue the current request under a new guard and record a guarded no-op; mutable weather state alone never proves replay. Until the World weather contract defines the exact aggregate selector, every Weather request remains fenced/reconciliation-required and a same-state observation is not replay evidence.

### Hazard State Update (Gameplay-Authoritative)

Required inputs:

- `EffectId`
- `roomInstanceRef`
- `hazardId`
- `targetState` (ACTIVE/INACTIVE)

Required writes:

- **World Management**
  - Persist hazard state as typed ambient room state under the owner participant guard for the persisted mutation `EffectId`, typed `HAZARD_STATE_UPDATE` operation, and target room/hazard aggregate, bound to the request digest and stored outcome.
  - **Target-state only:** advance the World-owned ambient component version used in the composite `LOOK` identity in that same local transaction so downstream LOOK/gameplay caches invalidate deterministically. A matching guard replay returns the prior result without a second version increment. The current `worldSnapshotId` scope marker provides no freshness proof and is not a cache-invalidation authority.

Read/API contract:

- Hazard state used by gameplay is authoritative in World Management and exposed via typed ambient fields in `GetRoomSnapshot`.
- **Target-state only:** Game Logic must use the World component version in the composite identity as the cache validator for hazard reads; when it advances, cached hazard state is stale. The current scope-derived `worldSnapshotId` marker is not freshness proof and must not validate a cache.
- Game Logic and Automation & Scripting must not maintain independent authoritative hazard tables or map-only hazard interpretations.

Reconciliation:

- Retry WMS with the same persisted mutation `EffectId` until the participant guard and hazard mutation converge. The replay/no-op path is valid only when the stored guard matches `HAZARD_STATE_UPDATE`, the exact room/hazard target aggregate, and the immutable request digest. If no matching guard exists but hazard state is already `targetState`, issue the current request under a new guard and record a guarded no-op; mutable hazard state alone never proves replay. A conflicting or ambiguous guard remains reconciliation-required.
