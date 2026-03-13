# Entity Management Service

## Overview

Handles player characters, NPCs, items, and all inventory/containment. Provides CRUD operations for entities and exposes them to other services. This includes player inventories and equipment, container contents (chests, corpses, banks, bags), and items on the ground in rooms (room/ground inventory) modeled as items inside dedicated room-ground container entities keyed by `(tenantId, gameInstanceId, roomInstanceId)` (a `RoomInstanceRef`) rather than being stored in the World Management Service.

### Responsibilities

- Persist characters, NPCs, and items with optimistic locking
- Provide CRUD and query APIs for other services
- Own and manage all inventories and item containment; character location and instance metadata live in the World Management Service
- Coordinate deferred writes through Game Session Service

World Management is therefore the sole owner of authoritative character and NPC location tables (`character_location`, `npc_location`) for each game instance. Entity Management reads location via World Management gRPC APIs or shared projections but must not persist its own competing location fields or treat cached location as authoritative.

For canonical naming and scoping rules, see [Identifier Glossary](../../system-architecture-identifier-glossary.md).

## Architecture / Design Notes

- Uses JPA for persistence of entity data.
- Exposes gRPC endpoints for other microservices.
- Caches frequently accessed character data in Redis for quick lookups.
- Applies **optimistic locking** to avoid conflicting updates on the same entity.
- **Database writes are deferred and batched** for ordinary entity updates, not triggered on every gameplay action. The Game Session Service coordinates real-time updates using Redis; the database is normally updated when ticks complete.
- Spatial containment mutations that participate in cross-service effects are the exception: before Entity Management acknowledges a spatial `EffectId` back to Game Session, it must durably flush the effect’s idempotency guard plus the affected containment/container rows for that effect within the same local transaction. A participant acknowledgement must never be emitted for Redis-only staged state.
- This design reduces write frequency and contention, making optimistic locking a natural fit — most entities are updated by only one process at a time, and conflicts are rare.
- Item transfers and other gameplay actions span services but execute within ticks
  using Redis scripts for rollback. Sagas are reserved for non-gameplay
  workflows. See [Transaction Strategies](../../system-architecture-transactions.md).
- For long-running, non-gameplay workflows such as publishing a game version,
  this service participates as a domain step in Saga flows coordinated by the
  Game Design Service as described in
  [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md).
- All entity tables include a `tenantId` column. Service methods always filter on
  this value so character data for different games remains isolated; Redis keys
  mirror this prefix. Details are in the [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
  document.
- Gameplay-facing gRPC endpoints do not parse JWT tokens. The Game Session Service
  injects identity context using `SessionContext` and may request a new JWT from
  the Account Service if a player's roles change. It does not validate tokens for
  gameplay. Traffic between services still uses mutual TLS certificates as outlined in the
  [Security Architecture](../../system-architecture-security.md).
- Design-time writes are a separate surface:
  - Entity Management exposes **design APIs** used by the Game Design Service to mutate Draft template rows keyed by `(tenantId, versionId)` (item/NPC templates, balance curves, loot tables).
  - These design APIs must validate JWTs and enforce designer/admin authorization for the target `tenantId` and Draft `versionId`.
  - Design APIs must reject any attempt to write templates for Published/Active/Failed versions.

- Utilizes the [Shared Libraries](../../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.
- Service methods are annotated with `@Timed` so inventory and character operations emit Prometheus metrics.

## Data Model and Versioning

Entity Management maintains a clear separation between **template/design data**
and **live runtime entities** so authoring workflows cannot corrupt active games:

- Template tables (for example item and NPC definitions, balance curves) are
  stored as versioned design records keyed by `(tenantId, versionId)` and are
  updated only through design-time workflows orchestrated by the Game Design
  Service. Entity Management accepts template writes only for Draft versions;
  once a version is marked Published in the Game Design Service, the associated
  template rows for that `(tenantId, versionId)` are treated as immutable and
  may only be read by runtime flows.
- Live runtime entities (characters, inventories, containers including
  room-ground containers) are stored in runtime tables keyed by
  `tenantId` plus runtime identifiers such as `entityId` and game-instance or
  shard identifiers. These rows are mutated only by tick-driven gameplay flows.
- Publishing a version finalizes template rows for that `(tenantId, versionId)`
  and records them as immutable inputs for future game instances. Runtime
  entity state never changes those template rows; it only references them via
  stable identifiers.

Template identifiers are stable within each version: a given template ID must
not be repurposed to represent a different conceptual entity while any
non-Retired version still references it. When switching a game instance to a
new `runtime_version`, the Game Session Service and Entity Management treat
missing or incompatible templates as a fatal configuration error for that
launch; the version selection must be corrected rather than silently
substituting defaults or partial data.

See [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md)
and [Item & Equipment Balancing Tools](../game-design-service/item-equipment-balancing.md)
for how design-time definitions flow into these versioned templates.

### Saga Participation

Entity Management does not orchestrate its own Saga workflows and does not use
Sagas for tick-driven gameplay or inventory operations. For long-running,
non-gameplay workflows such as publishing or rolling back a game version, it
participates as a domain step in Sagas coordinated by the Game Design Service
and Game Session Service. These workflows finalize or validate versioned
template data for `(tenantId, versionId)` without touching live runtime
entities. See [Transaction Strategies](../../system-architecture-transactions.md)
and [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md)
for the overall Saga patterns.

### Redis Role and Prefixes

- **Coordination Redis participation**
  - Acquires tick locks via shared helpers using keys of the form `tick:{tenantRegionTag}:lock:<entityId>` so locks share a hash tag with tick queues and pending state as described in [Redis Architecture](../../system-architecture-redis.md#key-format-examples).
  - Treats lock TTLs and other coordination parameters as opaque values derived by the Game Session Service and shared helpers; it does not define its own coordination-specific configuration.
- **Cache/Rate-Limit Redis usage**
  - Uses **Cache/Rate-Limit Redis** to cache frequently accessed character graphs and related aggregates under prefixes such as `character-cache:<tenantId>:<characterId>`, following the key naming and TTL/versioning patterns in [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md).
  - These character graph caches are treated as **Class A, versioned caches**:
    - Cached payloads include a stable version or `lastModified` value derived from the authoritative character tables (for example, the `character.version` or `last_modified` columns exposed via Entity Management APIs).
    - Readers validate versions against PostgreSQL (or version fields surfaced via gRPC) before reusing cached data; on mismatch they recompute the graph and overwrite the cache atomically (value + TTL).
    - TTLs (for example, `FIREMUD_CHARACTER_CACHE_TTL_SECONDS`) act as a safety valve for memory and stale entries, not as the primary correctness mechanism.
  - Future inventory/containment caches use the `inventory:<tenantId>:<containerId>` prefix from the Redis cache catalog:
    - Inventories and containers (including room-ground containers) are also treated as **Class A**: authoritative state and versions live in PostgreSQL, and cache entries must be invalidated via events or version checks when items move.
    - Event-based invalidation is driven by Entity Management’s own domain events (inventory changed, item moved, container destroyed); listeners delete or refresh affected `inventory:*` keys.
    - Implementations must document which APIs expose the version/`lastModified` fields used for these caches (for example, the container `version` column surfaced on inventory read APIs) and keep them aligned with the central `inventory:*` entry in `system-architecture-redis-cache.md` and the reset matrix in `system-architecture-redis-reset-and-recovery.md`.
  - Cache metrics for `character-cache:*` and `inventory:*` should follow the recommendations in `system-architecture-redis-cache.md` (for example `cache.character_hits_total` / `cache.character_misses_total` and `cache.inventory_hits_total` / `cache.inventory_misses_total`) so hit/miss behavior and key counts are observable.
  - Tests covering these caches are expected to exercise the Class A scenarios described in `system-architecture-redis-cache.md` (miss → populate → hit, version mismatch and event-driven invalidation, and behavior after a cache reset); see this service’s testing docs for where those tests live.
- Any change to Redis usage in this service should be reviewed against the [Redis Design Checklist](../../system-architecture-redis-design-checklist.md) to confirm prefix registration, role selection, slotting, and observability updates.

#### Version Sources for Entity Caches

Entity Management is the **invalidator of record** for `character-cache:*` and `inventory:*`:

- Authoritative versions or `lastModified` values for characters and containers are stored alongside the corresponding aggregates in PostgreSQL and surfaced via Entity Management’s gRPC APIs.
- Cache payloads for `character-cache:<tenantId>:<characterId>` and `inventory:<tenantId>:<containerId>` must embed those same version fields so readers can compare cached vs authoritative versions before reuse.
- When schema or API fields that act as “the” version for these aggregates change, this section and the central cache catalog (`system-architecture-redis-cache.md`) must be updated together so reviewers can see exactly which columns/fields drive Class A cache correctness.

Testing expectations for these caches follow the “Class A (versioned, correctness-critical) caches” guidance in `system-architecture-redis-cache.md`: unit/integration tests should cover version mismatches, event-driven invalidation, and repopulation after a Redis reset.

> If you change Redis usage for this service, you must read and apply:
>
> - [Redis Architecture](../../system-architecture-redis.md)
> - [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md)
> - [FireMUD Redis Lua Patterns](../../system-architecture-redis-lua-patterns.md)
> - [Redis Operations & Migrations](../../system-architecture-redis-operations.md)

## Key Features

- Character and NPC management.
- Item storage and inventory handling.
- Experience and level tracking.
- NPC respawn scheduling with configurable delays.
- Character creation templates pulled from the Game Design Service.
- Supports instance-based spaces in conjunction with the World Management Service
  so characters can enter private dungeons or personalized housing without affecting
  the shared world state.
- Crafting recipe management with validation.
- Cross-game character listing via account linkage.

### Data Model

- `character` and `npc` tables share a base entity for stats and inventory slots.
- `item` table stores equipment, consumables, and quest objects.
- Many-to-many tables define inventory and equipment relationships, including container contents and room/ground inventory. Room/ground inventory is modeled as items whose container references a synthetic room-ground container entity keyed by `(tenantId, gameInstanceId, roomInstanceId)` so limits such as max items on the ground or special container rules can be enforced consistently without cross-instance collisions.
- Character location and instance membership are stored by the World Management
  Service rather than this service, but all item instances and inventories remain owned and persisted here.
- Entity graphs cache inventory relationships for fast lookups.

#### Instance termination cleanup contract

Synthetic room-ground containers scoped by `(tenantId, gameInstanceId, roomInstanceId)` must be removed through the cross-service `InstanceTermination` Saga described in World Management docs:

- Game Session must already have closed admissions for the target instance before cleanup starts.
- Entity Management owns cleanup of containers and contained items for a terminating `gameInstanceId`.
- Cleanup must be idempotent and guarded by a durable saga step key so retries converge without double-deletes.
- Entity Management must not treat world row deletion as implicit cleanup confirmation; World Management marks an instance `TERMINATED` only after this service confirms cleanup completion.

### gRPC APIs

- `CreateCharacter` – builds a new player character from a template.
- `UpdateEntity` – updates stats or equipment for a character or NPC.
- `QueryInventory` – lists items for an entity with pagination.
- `ListCharactersByAccount` – returns all characters owned by an account across tenants.
- `ListRoomEntities` – returns players, NPCs, and visible items present in a room, scoped by `(tenantId, gameInstanceId, roomInstanceId)` (a `RoomInstanceRef`) so room presence and ground items are instance-safe.
- `GetDraftDesignDigest` – returns publish-gating digest for Draft entity templates using typed scope request `GetDraftDesignDigestRequest { tenantId, scope: oneof { versionId, scriptPatchVersion } }`. Entity Management supports `versionId` scope only and must return `UNSUPPORTED_SCOPE` for `scriptPatchVersion`. Minimum response fields are `{tenantId, scope, appliedCommitId, contentDigest, digestSchemaVersion}`. `appliedCommitId` means the highest Game Design commit whose full revision set has been durably applied to the target Draft entity scope. `contentDigest` must cover only version-scoped entity template/binding rows (for example item/NPC templates, loot mappings, balance curves) and must exclude live runtime entities and audit/history metadata.

### Digest Input Manifest

Entity Management is a required publish-gate participant and must maintain a stable digest manifest for `GetDraftDesignDigest(versionId)`:

- Included objects:
  - version-scoped entity-template tables such as item, NPC, equipment, loot-table, and balance-curve definitions keyed by `(tenantId, versionId)`;
  - normalized template-binding rows that affect published entity semantics, such as loot mappings or equipment/archetype constraints.
- Excluded objects:
  - all live runtime entities, inventories, containers, room-ground containers, and any rows keyed by `gameInstanceId` or `entityId`;
  - audit/history/provenance tables and non-semantic timestamps;
  - applied-revision ledgers when those rows do not affect entity semantics.
- Canonicalization rules:
  - serialize included relations in stable table order, then primary-key order;
  - include only semantic fields plus stable identifiers referenced cross-service;
  - normalize encoded structured fields before hashing.
- `digestSchemaVersion` must increment whenever included objects, semantic field selection, or serialization semantics change.

Publish gating must fail closed if Entity Management cannot attest a digest consistent with this manifest.

### LOOK entity listing contract

`ListRoomEntities` is the dedicated endpoint for `LOOK` to discover which characters, items, and NPCs occupy a room. The response includes:

- `tenantId`, `gameInstanceId`, and `roomInstanceId` (a `RoomInstanceRef`) so consumers can unambiguously scope the entity list to a running instance.
- An `entitySnapshotId` so consumers can cache or invalidate entity lists deterministically.
- `asOfTickId` (or equivalent monotonic read-fence token) echoing the fence used to materialize this entity list.
- `entities[]`, each with `entityId`, `displayName`, `entityType` (`PLAYER`, `NPC`, `ITEM`), and optional `role`/`affiliation`.
- `stateFlags` such as `isHidden`, `isInCombat`, or `isQuestTarget` so Game Logic can mask stealthy entities or highlight objectives.
- `visionPriority` to help sort players before NPCs and list visible items at the end, keeping `LOOK` render ordering consistent.
- `reloadHint` (enum) that signals whether the list is stable or dynamic, allowing Game Logic to decorate the `LOOK` output (for example, “Someone just entered from the east.”).

Game Logic treats `entitySnapshotId` as the canonical cache key for LOOK-relevant entity presence for a specific `RoomInstanceRef` at a specific read fence. When composing a full LOOK view, Game Logic combines:

- `worldSnapshotId` from World Management’s `GetRoomSnapshot`, and
- `entitySnapshotId` from `ListRoomEntities`,

then returns a `lookSnapshotId` (for example `worldSnapshotId + ":" + entitySnapshotId + ":" + asOfTickId`) alongside the rendered `LookResult` so Game Session can cache the final transcript deterministically.

Room-entity data is derived from runtime entity state plus authoritative world location. Ground items are discovered by querying items contained by the synthetic room-ground container for the target `RoomInstanceRef`. Characters and NPCs are included when their current location (owned by World Management) matches the target `RoomInstanceRef`:

- The caller obtains the authoritative occupant `entityId` set and read fence from World Management before invoking `ListRoomEntities`.
- `ListRoomEntities` joins those caller-supplied occupant `entityId` values to its own runtime entity rows to materialize display data plus room-ground inventory state owned by Entity Management.
- `ListRoomEntities` must accept caller-supplied occupancy references together with the World Management read fence token (`asOfTickId`); when Entity Management cannot serve the same fence it must return `STALE_READ_FENCE` / `READ_FENCE_UNAVAILABLE` instead of returning mixed-tick data.
- The read fence is satisfied only by durable post-commit state. Redis-staged containment changes that have not yet committed the effect guard and container/item row updates for that fence are not eligible to satisfy `asOfTickId`.

Entity Management must not maintain a competing “room occupancy index” that can drift from World Management’s location tables. Visibility and filtering rules are applied after aggregation so LOOK output remains player-correct.

Concrete per-effect required writes and reconciliation rules live in `design/architecture/system-architecture-spatial-and-ambient-effects-catalog.md`.

Cross-service retry orchestration is owned by the Game Session Service reconciliation backlog described in [Transaction Strategies](../../system-architecture-transactions.md#reconciliation-owner-of-record-spatialambient-effects). Entity Management must expose participant acknowledgements for each `EffectId`; it is not the owner of cross-service retry scheduling.

Only entities approved by the `EntityVisibilityPolicy` are returned; hidden NPCs, private inventory, or offstage summons are filtered out so `LOOK` always aligns with the player’s perspective. The response deliberately omits detailed stats to keep the text output focused on presence rather than numbers.

### Implementation status (LOOK slice)

- **Live:** The seeded `firemud.look.rooms` entries provide the visible entities for the demo rooms, `ListRoomEntities` is wired into Game Logic's `ResolveLook`, and the resulting instrumentation is captured in `../../../project-management/look-instrumentation.md`.
- **Stubbed:** Real-time behaviors such as item respawns, stealth/aura-driven visibility, and inventory states still rely on static fixtures so regression tests remain reproducible.
- **Deferred:** Future slices will catalog metadata from the `character_location` and `npc_location` tables, support multi-instance visibility rules, and surface richer context hints (combat alerts, quest markers) while keeping the public DTO focused on display data.

## Dependencies

- **Internal:**
  - Game Design Service supplies character templates and item definitions.
  - Game Session Service coordinates runtime updates via Redis queues.
- **External:** PostgreSQL for entity data.

> See [**Gateway Architecture**](../../system-architecture-gateway.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../system-architecture-protocol-bridging.md) for
details on shared infrastructure components.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.

## Environment Variables

This service uses the shared configuration described in
[Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md).
It requires the [PostgreSQL credentials](../../infrastructure/environment-and-secrets.md#postgresql-credentials)
and [Redis connection](../../infrastructure/environment-and-secrets.md#redis-connection)
variables.
TLS certificates are supplied via [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates). Peer services can be discovered using variables prefixed `FIREMUD_SERVICES_`.
The OpenTelemetry collector endpoint can be overridden via `OTEL_ENDPOINT` (see [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md)).

Additional variables specific to this service:

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `FIREMUD_CHARACTER_CACHE_TTL_SECONDS` | TTL for cached character graphs in Redis (seconds) | `60` |

## Proto Files

Service interface definitions are stored in
[../../../../protos/entity-management/v1](../../../../protos/entity-management/v1). After editing the
proto files, run `./gradlew generateProto` to update generated sources.

## Related Documentation

- [System Architecture Overview](../../system-architecture-overview.md)
- [Tick System and Runtime Design](../../system-architecture-ticks.md)
- [Redis Architecture](../../system-architecture-redis.md)
- [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- [Service Responsibility Matrix](../../service-responsibility-matrix.md)
- [User Journeys – World and Entity Design](../../user-journeys-creators.md#2-world-and-entity-design)
- [gRPC API Style & Versioning Guidelines](../../system-architecture-grpc.md)
- [Shared Libraries Overview](../../system-architecture-shared-libraries.md)
- [Database Migrations](../../system-architecture-database-migrations.md)
- [Backup & Disaster Recovery](../../system-architecture-backup-recovery.md)
- [Logging & Monitoring](../../system-architecture-logging-monitoring.md)
- [Authentication & Authorization](../../system-architecture-authentication.md)
- [Security Architecture](../../system-architecture-security.md)
- [Testing Strategy](../../system-architecture-testing.md)
- [CI/CD Pipeline](../../system-architecture-cicd.md)

## Additional Details

### REST & gRPC Endpoints

#### REST

- `GET /ping` – basic health check returning `"pong"`.

```bash
curl http://localhost:8080/ping
```

#### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`entity_management_service.proto`](../../../../protos/entity-management/v1/entity_management_service.proto).
- `CreateCharacter(CreateCharacterRequest) returns (CreateCharacterResponse)` – builds a new player character.
- `UpdateEntity(UpdateEntityRequest) returns (UpdateEntityResponse)` – updates stats or equipment.
- `QueryInventory(QueryInventoryRequest) returns (QueryInventoryResponse)` – lists items for an entity.
- `ListRoomEntities(ListRoomEntitiesRequest) returns (ListRoomEntitiesResponse)` – returns players, NPCs, and visible items present in a room, scoped by `RoomInstanceRef`.

```bash
grpcurl -plaintext localhost:6565 entity_management.v1.EntityManagementService/Ping
```

#### Design-Time APIs

Entity Management also exposes **design-time** APIs used by the Game Design Service to write Draft template rows keyed by `(tenantId, versionId)` (for example item/NPC templates, loot tables, and balancing records).

- Auth: design-time APIs must validate JWTs and enforce designer/admin authorization for the target `tenantId`.
- Mutability: design-time writes are allowed only for Draft versions; attempts to write templates for Published/Active/Failed versions must fail fast.
- Runtime isolation: runtime gameplay flows and tick-driven handlers must never call design APIs.

Entity Management must also expose a read-only design-time synchronization surface so the Game Design Service can validate convergence before publish:

- `GetDraftDesignDigest(GetDraftDesignDigestRequest)` uses request shape `{tenantId, scope: oneof {versionId, scriptPatchVersion}}`. Entity Management supports `versionId` scope only and returns `UNSUPPORTED_SCOPE` otherwise.
- Response returns `{tenantId, scope, appliedCommitId, contentDigest, digestSchemaVersion}` as described in `design/architecture/microservices/game-design-service/world-editing-tools.md`.

Digest input manifest requirements (Entity Management):

- Included objects: version-scoped entity/binding rows for `(tenantId, versionId)` (for example item/NPC/equipment templates, loot/balance mappings, and other publish-scoped template relations).
- Excluded objects: live runtime entity/inventory/container rows, room-ground runtime containment, audit/history tables, and non-semantic write-time metadata fields (`created_at`, `updated_at`).
- Canonicalization: deterministic table ordering, primary-key ordering within table, and stable field encoding.
- `digestSchemaVersion` must be incremented when included/excluded object sets or canonicalization rules change.

### Tick Locking

This service participates in tick processing by acquiring Redis locks before mutating entity state. The `TickLockService` uses the `tick:{tenantRegionTag}:lock:<entityId>` key described in the [Redis Architecture](../../system-architecture-redis.md) document so that lock keys share a hash tag with tick queues and pending state. Lock TTLs come from the **shared tick/Redis helpers** that implement the canonical formulas defined in [Tick Concepts & Invariants](../../system-architecture-tick-concepts-and-invariants.md#tick-budget-ttls-and-region-health-conceptual):

- The Game Session Service exposes `game.tick-interval-ms` as the primary pacing knob.
- Internally it derives a soft budget and TTLs using the shared helpers (for example `tick_budget_ms = tick_interval_ms * 0.8`, `lock_ttl_ms = clamp(tick_budget_ms * 8, 500, 5_000)`).

Entity Management treats `lock_ttl_ms` as an opaque value supplied by shared helpers; it does not define its own lock TTL configuration. This keeps locks alive long enough for normal ticks to complete while still bounding the recovery window for stalled ticks.

At runtime, the Game Session Service also compares **observed tick execution time** to `tick_lock_ttl_ms` (the effective lock TTL for the region, derived from `lock_ttl_ms`) as described in the [Tick System design](../../system-architecture-ticks.md#timeout-and-fairness-policy). Regions whose `p99` tick runtime begins to approach or exceed a configured fraction of that TTL are treated as degraded, and operators are expected to either increase the tick interval or simplify per-tick work. Entity Management does not adjust TTLs itself; it relies on the shared helpers and scheduler behavior to keep lock usage within safe bounds.

Entity Management assumes the **per-command execution phases** described in the [Tick System design](../../system-architecture-ticks.md#per-command-execution-phases): commands that touch multiple entities in the same region resolve their target set first (for example, the two parties in a trade or all entities in a room for AoE effects), then acquire the necessary `tick:{tenantRegionTag}:lock:<entityId>` keys in a deterministic order. If any required lock is unavailable, the command fails, staged changes are rolled back via Redis, and the Game Session Service reschedules the work using the retry mechanisms described in the Tick System and Redis designs.

### Tick Idempotency

Entity Management implements tick idempotency using the **per-aggregate last-tick state** pattern described in the [Tick System and Runtime Design](../../system-architecture-ticks.md#domain-idempotency-rules-region-epoch--tickid-in-postgresql) document:

- A shadow table (for example `entity_tick_state`) tracks `(last_region_epoch, last_tick_id)` (and associated tenant/region metadata) per `entityId`.
- Tick-driven handlers that mutate an entity:
  - Load the current tick state for that `entityId`.
  - Treat calls where `(last_region_epoch, last_tick_id) >= (currentRegionEpoch, currentTickId)` as **replays/out-of-order** and perform a no-op (or validation-only check).
  - Apply changes only when `(last_region_epoch, last_tick_id) < (currentRegionEpoch, currentTickId)`, then update `(last_region_epoch, last_tick_id) = (currentRegionEpoch, currentTickId)` in the same transaction as the entity update.

Complex multi-entity operations (for example trades that touch two inventories) use the **operation-level effect guard** pattern described in the same tick document, inserting a `(tenantId, regionId, region_epoch, tickId, effectKey)` row into a guard table before applying changes so replays of the same logical effect become safe no-ops instead of double-applications.

Examples:

- **Damage application** – when a tick instructs Entity Management to apply damage to `entityId`, the handler:
  - Reads `entity_tick_state` for that `entityId`.
  - Skips the update if `(last_region_epoch, last_tick_id) >= (currentRegionEpoch, currentTickId)` (replay/out-of-order), or applies the HP change and sets `(last_region_epoch, last_tick_id) = (currentRegionEpoch, currentTickId)` in the same transaction if `(last_region_epoch, last_tick_id) < (currentRegionEpoch, currentTickId)`.

- **Trade between two entities** – when a tick performs a trade between `fromEntityId` and `toEntityId`:
  - The handler computes a deterministic `effectKey` such as `trade:<fromEntityId>:<toEntityId>:<itemId>`.
  - It inserts `(tenantId, regionId, region_epoch, tickId, effectKey)` into the guard table before moving items between inventories.
  - On primary-key conflict, the trade is treated as an already-applied effect for that tick and becomes a no-op.

- [System Architecture Diagram](../../system-architecture-diagram.md)
- [System Context Diagram](../../system-context-diagram.md)
