# Entity Management Service Runtime and Data

This document defines Entity Management’s runtime model, persistence ownership, versioning rules, Redis role, and instance/cutover data classification.

## Architecture and Design Notes

- Uses JPA for persistence of entity data.
- Exposes gRPC endpoints for other microservices.
- Caches frequently accessed character data in Redis for quick lookups.
- Applies optimistic locking to avoid conflicting updates on the same entity.
- Entity Management instances are intended to be replaceable workers over authoritative persistent state and documented caches. Item, inventory, containment, and character data that must survive instance loss belongs in the service-owned database rows and cache invalidation model, not as the sole authoritative copy in one process.
- Database writes are deferred and batched for ordinary entity updates, not triggered on every gameplay action. The Game Session Service coordinates real-time updates using Redis; the database is normally updated when ticks complete.
- Spatial containment mutations that participate in cross-service effects are the exception: before Entity Management acknowledges a spatial `EffectId` back to Game Session, it must durably flush the effect’s idempotency guard plus the affected containment/container rows for that effect within the same local transaction. A participant acknowledgement must never be emitted for Redis-only staged state.
- Runtime item/equipment/container mutation RPCs that carry an `effectId` use `entity_mutation_effects` as the current operation-level guard. Entity Management records the applied protobuf response for `{tenantId, effectId}` and returns that stored response on duplicate delivery so `GET`, `DROP`, `PUT`, `TAKE`, `WEAR`, and `REMOVE` cannot double-apply after Game Session replay or retry. The `entitymanagement.mutation.effect.execution{operation,effect_status}` metric distinguishes first apply, replay/no-op, in-progress conflict, rejected reuse, and unreadable stored-response outcomes.
- This design reduces write frequency and contention, making optimistic locking a natural fit because most entities are updated by only one process at a time and conflicts are rare.
- Item transfers and other gameplay actions span services but execute within ticks using Redis scripts for rollback. Sagas are reserved for non-gameplay workflows. See [Transaction Strategies](../../system-architecture-transactions.md).
- For long-running, non-gameplay workflows such as publishing a game version, this service participates as a domain step in durable publish workflows coordinated by the Game Design Service as described in [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md).
- All entity tables include a `tenantId` column. Service methods always filter on this value so character data for different games remains isolated; Redis keys mirror this prefix. Details are in [Multi-Tenancy](../../system-architecture-multi-tenancy.md).
- Gameplay-facing gRPC endpoints do not parse JWT tokens. The Game Session Service injects identity context using `SessionContext` and may request a new JWT from the Account Service if a player's roles change. It does not validate tokens for gameplay. Traffic between services still uses mutual TLS certificates as outlined in the [Security Architecture](../../system-architecture-security.md).
- Design-time writes are a separate surface:
  - Entity Management exposes design APIs used by the Game Design Service to mutate Draft template rows keyed by `(tenantId, versionId)` (item/NPC templates, balance curves, loot tables).
  - These design APIs must validate JWTs and enforce designer/admin authorization for the target `tenantId` and Draft `versionId`.
  - Design APIs must reject any attempt to write templates for Published/Active/Failed versions.
- Utilizes the [Shared Libraries](../../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.
- Service methods are annotated with `@Timed` so inventory and character operations emit Prometheus metrics.

This means another Entity Management instance of the same type should be able to serve the same runtime data after restart without requiring gameplay clients to reconnect just because one non-edge worker disappeared. Any such visible restart remains implementation debt, not target behavior.

## Data Model and Versioning

Entity Management maintains a clear separation between template/design data and live runtime entities so authoring workflows cannot corrupt active games:

- Template tables (for example item and NPC definitions, balance curves) are stored as versioned design records keyed by `(tenantId, versionId)` and are updated only through design-time workflows orchestrated by the Game Design Service. Entity Management accepts template writes only for Draft versions; once a version is marked Published in the Game Design Service, the associated template rows for that `(tenantId, versionId)` are treated as immutable and may only be read by runtime flows.
- Live runtime entities (characters, inventories, containers including room-ground containers) are stored in runtime tables keyed by `tenantId` plus runtime identifiers such as `entityId` and game-instance or shard identifiers. These rows are mutated only by tick-driven gameplay flows.
- Publishing a version finalizes template rows for that `(tenantId, versionId)` and records them as immutable inputs for future game instances. Runtime entity state never changes those template rows; it only references them via stable identifiers.

Template identifiers are stable within each version: a given template ID must not be repurposed to represent a different conceptual entity while any non-Retired version still references it. When switching a game instance to a new `runtime_version`, the Game Session Service and Entity Management treat missing or incompatible templates as a fatal configuration error for that launch; the version selection must be corrected rather than silently substituting defaults or partial data.

### Replacement-Instance State Classification

Entity Management must classify its runtime persistence surface for cutover and migration tooling:

- `S1` entity-owned durable state:
  - `character` identity/account-ownership rows and equivalent progression/currency records that do not require version remapping when referenced templates remain valid;
  - stable player-owned inventory/container membership for item instances that remain valid against the target version without remapping.
- `S2` entity-owned version-mapped durable state:
  - equipment-slot bindings for equipped items whose template validity depends on the target version;
  - learned-ability, starter-loadout, class/archetype, or equivalent durable character references whose validity depends on target-version template identifiers;
  - inventory or character rows that remain durable but reference templates requiring an approved remap to the target version.
- `S3` entity-owned ephemeral state:
  - synthetic room-ground containers and their contents keyed by `(tenantId, gameInstanceId, roomInstanceId)`;
  - transient containment, encounter-specific entities, corpses, summons, or equivalent rows whose lifecycle is tied to the source `gameInstanceId`;
  - any row family explicitly documented as instance-scoped only.

Initial-slice row-family inventory:

- `character` rows are `S1` only within the resolved playable-state namespace. Shared-state realms use the tenant-live namespace, while isolated-state realms use the selected `gameInstanceId` namespace.
- Player progression/currency/account-ownership rows attached to `character` and not requiring template remap are `S1` only after the caller proves the same resolved `{tenantId, gameInstanceId, playableStateScope}` target as the character row. Mutation APIs must not update progression/resource-style state by global `characterId` alone.
- Inventory membership / containment rows for durable player-owned containers remain `S1` when every referenced item template is still valid against the target version.
- `equipment_bindings` rows are `S2`.
- Durable learned-ability, class/archetype, starter-loadout, or similar template-reference rows are `S2`.
- Durable inventory or character rows that need an approved template remap to remain valid are `S2`.
- Synthetic room-ground containers keyed by `(tenantId, gameInstanceId, roomInstanceId)` and their containment rows are `S3`.
- Encounter-scoped NPCs, corpses, summons, temporary containers, and any containment rows tied only to the source `gameInstanceId` are `S3`.

Initial-slice rule:

- If a row family is not explicitly documented as `S1` or `S2`, treat it as `S3` for cutover purposes.
- Replacement-instance workflows must not infer template remaps from names, display text, or best-effort similarity; only approved `remapSetId` mappings may satisfy `S2` compatibility.

Implementation notes:

- The cutover-validation RPC now exists as `ValidateEntityUpgradeMappings(tenantId, sourceGameInstanceId, targetVersionId, remapSetId?)`.
- The first live implementation slice enumerates tenant-surviving families (`character`, `inventory`, `character_equipment`, `character_friend`) plus the currently persisted instance-scoped families (`room_ground_inventory`, `item_instances`, `item_stacks`, `container_instances`).
- `character` and `character_friend` rows are supported `S1` survivor state in the current slice. Their presence does not require a remap set by itself.
- `inventory` and `character_equipment` rows are treated as current `S2` template-bound survivor state. If either family has rows and no approved `remapSetId` was frozen by launch resolution, validation returns `result=INCOMPATIBLE`, `hasS2Rows=true`, `remapSetRequired=true`, and `ENTITY_REMAP_REQUIRED`.
- When template-bound `S2` rows exist and the caller supplies the frozen approved `remapSetId`, the current slice reports `COMPATIBLE` and echoes that id. Entity Management does not infer remaps and does not create a second remap identity; Game Design remains the source of truth for approval and the prepared cutover artifact binds the exact id used.

Entity upgrade validation minimum contract:

- The service must expose a cutover-validation API that accepts `tenantId`, `sourceGameInstanceId`, `targetVersionId`, and optional `remapSetId`.
- The response must enumerate the entity-owned row families checked, the referenced template identifiers, and per-family outcomes `COMPATIBLE`, `REQUIRES_MAPPING`, or `INCOMPATIBLE`.
- If the service currently has no `S2` rows for a given source instance, it must report that explicitly rather than collapsing the result into a generic success.

Cutover fence contract:

- Replacement-instance validation and migration must run against a durable, fenced snapshot of Entity Management state for the source `gameInstanceId`; validating against Redis-staged or partially flushed deferred writes is not allowed.
- Before invoking entity cutover validation or snapshot/export for a source instance, Game Session must quiesce gameplay admission and mutation for that `gameInstanceId`.
- Entity Management must then flush all deferred `S1` and `S2` writes for that source instance to PostgreSQL and return a committed fence token or epoch that identifies the durable state used for validation.
- The validation response must either include that fence token/epoch or be bound to an API contract that makes the same durable fence observable to the caller.
- `durableFenceToken` is an opaque server-issued value. Callers may persist and compare it for equality/identity, but they must not infer ordering, encode semantics, or generate successor tokens client-side unless a future API explicitly adds those guarantees.
- If Entity Management cannot flush deferred durable state for the source instance, cutover validation must fail closed rather than validating stale database rows.

Illustrative responses for the current live first slice:

- Current first-cut response with only instance-scoped `S3` families:

```json
{
  "tenantId": "t1",
  "sourceGameInstanceId": "g-old",
  "targetVersionId": "v2",
  "checkedFamilies": [
    "room_ground_inventory",
    "item_instances",
    "item_stacks",
    "container_instances"
  ],
  "stateClassesChecked": ["S3"],
  "hasS2Rows": false,
  "result": "COMPATIBLE",
  "remapSetRequired": false
}
```

Target-state illustrative responses:

- Durable rows present but no remap required:

```json
{
  "tenantId": "t1",
  "sourceGameInstanceId": "g-old",
  "targetVersionId": "v2",
  "durableFenceToken": "entity-cutover-fence:g-old:184",
  "checkedFamilies": [
    {
      "family": "equipment_bindings",
      "referencedTemplateIds": ["itemTemplateId:iron-sword"],
      "outcome": "COMPATIBLE"
    }
  ],
  "hasS2Rows": true,
  "result": "COMPATIBLE",
  "remapSetRequired": false
}
```

`durableFenceToken` values in these examples are illustrative opaque tokens only. Clients and operators must not infer structure from the string shape beyond equality comparison against the corresponding cutover/readiness contract.

- Durable rows require remap:

```json
{
  "tenantId": "t1",
  "sourceGameInstanceId": "g-old",
  "targetVersionId": "v3",
  "durableFenceToken": "entity-cutover-fence:g-old:231",
  "checkedFamilies": [
    {
      "family": "class_assignment",
      "referencedTemplateIds": ["classTemplateId:ranger-v1"],
      "outcome": "REQUIRES_MAPPING"
    }
  ],
  "hasS2Rows": true,
  "result": "INCOMPATIBLE",
  "remapSetRequired": true
}
```

- No `S2` rows for a source instance:

```json
{
  "tenantId": "t1",
  "sourceGameInstanceId": "g-old",
  "targetVersionId": "v2",
  "durableFenceToken": "entity-cutover-fence:g-old:240",
  "checkedFamilies": [],
  "hasS2Rows": false,
  "result": "COMPATIBLE",
  "remapSetRequired": false
}
```

- Durable flush could not complete, validation refused:

```json
{
  "tenantId": "t1",
  "sourceGameInstanceId": "g-old",
  "targetVersionId": "v2",
  "error": {
    "code": "CUTOVER_FENCE_UNAVAILABLE",
    "message": "Deferred durable writes for sourceGameInstanceId=g-old could not be flushed to PostgreSQL; cutover validation refused."
  }
}
```

See [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md) and [`item-equipment-balancing.md`](../game-design-service/item-equipment-balancing.md) for how design-time definitions flow into these versioned templates.

## Runtime Data Model

- `character` and `npc` tables share a base entity for stats and inventory slots.
- `item` table stores equipment, consumables, and quest objects.
- Many-to-many tables define inventory and equipment relationships, including container contents and room/ground inventory. Room/ground inventory is modeled as items whose container references a synthetic room-ground container entity keyed by `(tenantId, gameInstanceId, roomInstanceId)` so limits such as max items on the ground or special container rules can be enforced consistently without cross-instance collisions.
- Actor gameplay state is persisted separately from the legacy character stat columns. `actor_resource_states` stores current/base/max resource values with source provenance, while `actor_active_conditions` stores active condition keys, stack counts, source provenance, start/expiry timestamps, and effect payload JSON. Reads merge baseline character stat fields with persisted resource rows so later mutation/effect slices can converge on the new state model without removing the bootstrap character fields first.
- The first shared effect-evaluation seam is an in-process Entity Management service that evaluates typed resource modifiers and granted states deterministically. Active condition payloads and equipped item-template payloads are `CONTINUOUS` effect sources: they contribute while their source exists and never write derived values into current resources during reads. When an attached source changes a bounded-resource maximum, its attach, detach, refresh, expiry, or replacement mutation performs the one idempotent capacity normalization using the declaration override or resolved tenant/game `actorState.capacityChangePolicy` captured by that mutation. Evaluation reads never normalize current state. Gameplay-attested `ApplyActorCondition` calls are the first `INSTANT` mutation path, creating active condition/action-state rows through the same internal mutation service under an idempotent effect id. A scheduled Entity Management job also expires elapsed active-condition rows on a bounded cadence. Player command wiring remains future work.
- Target-state active-condition rows are condition instances with stable instance ids, frozen definition/release snapshots, source provenance, stack counts, expiry, and applied-effect snapshots. Condition-definition DML, not source-id coincidence, selects `REPLACE`, `REFRESH`, `STACK`, or `PARALLEL` reapplication behavior and its duration policy. Typed removal resolves exact keys, authored tags, or permitted sources in deterministic definition priority and instance-id order; it must not expose raw row or payload deletion to player-facing callers.
- Character location and instance membership are stored by the World Management Service rather than this service, but all item instances and inventories remain owned and persisted here.
- Entity graphs cache inventory relationships for fast lookups.

### Runtime Actor Identity

Entity Management owns one persisted runtime actor for every active gameplay being. `actorId` is an opaque canonical gameplay-facing identity; services must not substitute composite reference strings such as `PLAYER:<characterId>` or re-derive different player/NPC identity forms at each boundary.

- The actor core carries `tenantId`, `gameInstanceId`, `actorKind`, display name, and presence state. It does not persist universal targetability or visibility fields.
- A `PLAYER` actor is unique for its active `{tenantId, gameInstanceId, characterId}` scope and links to the durable account and character records. Disconnect/reconnect changes presence on that actor rather than creating a replacement identity.
- An `NPC` actor links to one NPC runtime instance. An authored NPC definition may create many concurrent runtime NPC actors and is not itself actor identity.
- `PET` and `SUMMON` extend the same core when implemented. God/admin behavior is a capability and authorized presentation overlay on a `PLAYER` actor, not a separate actor kind.

Each actor also has one persisted, release-admitted `dispositionKey`: its main gameplay state. The disposition supplies base action-admission through published `ActionAdmissionTag` denials and semantic feedback policy. Conditions and equipment are explicit continuous overlays over that base; they can only add tag denials or otherwise narrow it and never grant behavior denied by the main disposition. They may also contribute ordinary game-authored state facts for later reusable `ObservationPolicy` and `TargetingPolicy` evaluation, but those facts do not bypass action admission or become universal visibility/targetability fields. Recovery, immunity, revival, and other exceptional main-state changes use an idempotent instant condition removal/prevention or disposition transition, so continuous sources do not become competing death/defeat lifecycle owners. Transport/session presence is a separate fact and must not be repurposed as disposition.

The actor is the shared subject for gameplay targeting, effects, stats, conditions, and communication. It does not move other service ownership:

- World Management remains authoritative for an actor's room location and the room occupancy view.
- Game Session owns only ephemeral session attachment, protocol state, and player-facing presence projection.
- Account Service remains authoritative for account identity and authorization inputs.

For published targeting predicates whose facts it owns, Entity Management exposes a bounded `TargetingFactSnapshot` for the requested scoped actor ids. The response includes only the requested actor-state facts and an actor-state revision token; it is not a general actor read. Before applying an approved effect plan, Entity Management validates the recorded token for every material Entity-owned fact. If any token is stale, it reports a pre-commit mismatch so Game Logic can discard and re-resolve the plan under the same effect id before any source cost or target mutation commits.

Character and NPC records remain the durable domain records for their respective variants. The runtime actor links those records into one gameplay subject model; it does not replace character progression, authored NPC definitions, or World Management location state.

### Containment and Equipment Model

Entity Management's target-state containment model is container-first:

- Every item instance lives in a container or is attached through a first-class equipment binding.
- Character and NPC inventories are hidden containers owned by that entity.
- Bags, chests, corpses, banks, vendor stock, and similar holders are containers using the same core containment rules.
- Room-ground inventory is also a container, not a special world-owned list. World Management remains authoritative for the room identity and occupancy, while Entity Management owns the attached room-ground container and its contents.

Room-ground containers must feel attached to a room instance rather than like an unrelated global mapping:

- The room identity comes from World Management via `RoomInstanceRef`.
- Entity Management persists the attached ground container and its contents using a deterministic runtime identity derived from `(tenantId, gameInstanceId, roomInstanceId)`.
- Implementations may store the attached container as a synthetic entity or equivalent runtime record, but the conceptual contract is "this room instance has a ground container."

Equipment is intentionally not modeled as "just another bag position":

- Equipped items remain owned by the same runtime entity but are represented through a first-class equipment relation.
- Equipment slot definitions are game-configured design data, not fixed platform-wide enums.
- Body layouts or equivalent runtime configuration determine which slots are available to a particular character/NPC/species.
- Item definitions declare compatibility through configurable slot groups, attachment rules, or equivalent game-defined constraints rather than through a hardcoded universal slot set.

This keeps the platform compatible with games that need unusual body plans or attachment models such as horns instead of hands, asymmetric limbs, species-specific slot topologies, or non-humanoid wearable layouts.

### Inventory Queries and Type Filtering

The current minimal `QueryInventory -> item_ids[]` shape is only sufficient for the earliest bootstrap slices. The target-state inventory contract should support richer gameplay and UI queries:

- Query by containment scope such as inventory container, room-ground container, nested container, bank, vendor stock, or corpse.
- Query by equipped state and slot binding.
- Query by structural properties such as stackability, quantity, visibility, accessibility, and ownership.
- Query by game-defined item types, tags, or category taxonomies so gameplay commands and GUIs can filter for concepts such as quest items, reagents, weapons, salvage, consumables, rarity classes, or other design-defined groupings.

Current `06.3` note:

- the live runtime model already treats ordinary items as distinct `item_instances`;
- item definitions now already expose an explicit authored `stackable` capability flag, defaulting to non-stackable;
- authored stackability still remains a later holder/query behavior, not an implied consequence of item-definition sameness;
- until that authored seam exists, same-definition items should remain separate physical instances rather than silently merging into aggregate quantity state.

This richer query model is required both for player actions and for future clients with multiple inventory panes, filtered item grids, equipment screens, contextual loot UIs, or admin/operator tooling.

### Inventory Transfer Audit

Inventory and equipment mutations must be auditable. This is a deliberate design requirement because item duplication bugs or invalid transfers can be difficult to investigate after the fact.

Every transfer-like mutation should emit an audit record using one canonical movement model:

- item instance id
- item definition/template id
- quantity or stack delta
- source container id or equipped binding
- destination container id or equipped binding
- actor entity/account/session when applicable
- action reason such as `pickup`, `drop`, `loot`, `put`, `take`, `equip`, `unequip`, `split_stack`, `merge_stack`, `create`, `destroy`, or `admin_grant`
- tenant id, game instance id, and room context when applicable
- timestamp plus correlation id / command id / effect id

Because everything is modeled as container movement plus equipment bindings, the audit format can stay uniform across player inventories, room-ground transfers, nested containers, equipment changes, scripted rewards, and administrative interventions.

Where possible, the audit write must participate in the same local transactional boundary as the authoritative containment mutation so the system does not record state changes without an audit trail or emit audit rows for rolled-back state.

Operators and later fraud/dupe-detection tooling should also be able to derive lightweight invariants from these records, for example:

- the same item instance appearing in multiple locations
- negative or impossible stack counts
- transfer retries reusing the same correlation id unexpectedly
- suspicious create/destroy imbalances
- source or destination containers inconsistent with room/entity ownership rules

### Instance Termination Cleanup Contract

Synthetic room-ground containers scoped by `(tenantId, gameInstanceId, roomInstanceId)` must be removed through the cross-service `InstanceTermination` Saga described in World Management docs:

- Game Session must already have closed admissions for the target instance before cleanup starts.
- Entity Management owns cleanup of containers and contained items for a terminating `gameInstanceId`.
- Cleanup must be idempotent and guarded by a durable workflow step key so retries converge without double-deletes.
- Entity Management must not treat world row deletion as implicit cleanup confirmation; World Management marks an instance `TERMINATED` only after this service confirms cleanup completion.

### Workflow Participation

Entity Management does not orchestrate its own synchronous saga or Temporal workflows and does not use them for tick-driven gameplay or inventory operations. For long-running, non-gameplay workflows such as publishing or rolling back a game version, it participates as a domain step in workflows coordinated by the Game Design Service and Game Session Service. These workflows finalize or validate versioned template data for `(tenantId, versionId)` without touching live runtime entities. See [Transaction Strategies](../../system-architecture-transactions.md) and [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md) for the overall workflow patterns.

## Redis Role and Prefixes

- **Coordination Redis participation**
  - Acquires tick locks via shared helpers using keys of the form `tick:{tenantRegionTag}:lock:<entityId>` so locks share a hash tag with tick queues and pending state as described in [Redis Architecture](../../system-architecture-redis.md#key-format-examples).
  - Treats lock TTLs and other coordination parameters as opaque values derived by the Game Session Service and shared helpers; it does not define its own coordination-specific configuration.
- **Cache/Rate-Limit Redis usage**
  - Uses Cache/Rate-Limit Redis to cache frequently accessed character graphs and related aggregates under prefixes such as `character-cache:<tenantId>:<characterId>`, following the key naming and TTL/versioning patterns in [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md).
  - These character graph caches are treated as Class A, versioned caches:
    - Cached payloads include a stable version or `lastModified` value derived from the authoritative character tables (for example, the `character.version` or `last_modified` columns exposed via Entity Management APIs).
    - Readers validate versions against PostgreSQL (or version fields surfaced via gRPC) before reusing cached data; on mismatch they recompute the graph and overwrite the cache atomically (value + TTL).
    - TTLs (for example, `FIREMUD_CHARACTER_CACHE_TTL_SECONDS`) act as a safety valve for memory and stale entries, not as the primary correctness mechanism.
  - Future inventory/containment caches use the `inventory:<tenantId>:<containerId>` prefix from the Redis cache catalog:
    - Inventories and containers (including room-ground containers) are also treated as Class A: authoritative state and versions live in PostgreSQL, and cache entries must be invalidated via events or version checks when items move.
    - Event-based invalidation is driven by Entity Management’s own domain events (inventory changed, item moved, container destroyed); listeners delete or refresh affected `inventory:*` keys.
    - Implementations must document which APIs expose the version/`lastModified` fields used for these caches and keep them aligned with the central `inventory:*` entry in `system-architecture-redis-cache.md` and the reset matrix in `system-architecture-redis-reset-and-recovery.md`.
  - Cache metrics for `character-cache:*` and `inventory:*` should follow the recommendations in `system-architecture-redis-cache.md` so hit/miss behavior and key counts are observable.
  - Tests covering these caches are expected to exercise the Class A scenarios described in `system-architecture-redis-cache.md` (miss -> populate -> hit, version mismatch and event-driven invalidation, and behavior after a cache reset).
- Any change to Redis usage in this service should be reviewed against the [Redis Design Checklist](../../system-architecture-redis-design-checklist.md) to confirm prefix registration, role selection, slotting, and observability updates.

### Version Sources for Entity Caches

Entity Management is the invalidator of record for `character-cache:*` and `inventory:*`:

- Authoritative versions or `lastModified` values for characters and containers are stored alongside the corresponding aggregates in PostgreSQL and surfaced via Entity Management’s gRPC APIs.
- Cache payloads for `character-cache:<tenantId>:<characterId>` and `inventory:<tenantId>:<containerId>` must embed those same version fields so readers can compare cached vs authoritative versions before reuse.
- When schema or API fields that act as the version for these aggregates change, this section and the central cache catalog (`system-architecture-redis-cache.md`) must be updated together so reviewers can see exactly which columns/fields drive Class A cache correctness.

Testing expectations for these caches follow the Class A guidance in `system-architecture-redis-cache.md`: unit/integration tests should cover version mismatches, event-driven invalidation, and repopulation after a Redis reset.

If you change Redis usage for this service, you must read and apply:

- [Redis Architecture](../../system-architecture-redis.md)
- [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md)
- [FireMUD Redis Lua Patterns](../../system-architecture-redis-lua-patterns.md)
- [Redis Operations & Migrations](../../system-architecture-redis-operations.md)
