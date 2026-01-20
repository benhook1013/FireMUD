# Entity Management Service

## Overview

Handles player characters, NPCs, items, and all inventory/containment. Provides CRUD operations for entities and exposes them to other services. This includes player inventories and equipment, container contents (chests, corpses, banks, bags), and items on the ground in rooms (room/ground inventory) modeled as items inside dedicated room-ground container entities keyed by room or instance ID rather than being stored in the World Management Service.

### Responsibilities

- Persist characters, NPCs, and items with optimistic locking
- Provide CRUD and query APIs for other services
- Own and manage all inventories and item containment; character location and instance metadata live in the World Management Service
- Coordinate deferred writes through Game Session Service

## Architecture / Design Notes

- Uses JPA for persistence of entity data.
- Exposes gRPC endpoints for other microservices.
- Caches frequently accessed character data in Redis for quick lookups.
- Applies **optimistic locking** to avoid conflicting updates on the same entity.
- **Database writes are deferred and batched**, not triggered on every gameplay action. The Game Session Service coordinates real-time updates using Redis; the database is only updated when ticks complete.
- This design reduces write frequency and contention, making optimistic locking a natural fit — most entities are updated by only one process at a time, and conflicts are rare.
- Item transfers and other gameplay actions span services but execute within ticks
  using Redis scripts for rollback. Sagas are reserved for non-gameplay
  workflows. See [Transaction Strategies](../../system-architecture-transactions.md).
  This service does not participate in any saga workflows.
- All entity tables include a `tenantId` column. Service methods always filter on
  this value so character data for different games remains isolated; Redis keys
  mirror this prefix. Details are in the [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
  document.
- Gameplay-facing gRPC endpoints do not parse JWT tokens. The Game Session Service
  injects identity context using `SessionContext` and may request a new JWT from
  the Account Service if a player's roles change. It does not validate tokens for
  gameplay. Traffic between services still uses mutual TLS certificates as outlined in the
  [Security Architecture](../../system-architecture-security.md).

- Utilizes the [Shared Libraries](../../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.
- Service methods are annotated with `@Timed` so inventory and character operations emit Prometheus metrics.

### Redis Role and Prefixes

- **Coordination Redis participation**
  - Acquires tick locks via shared helpers using keys of the form `tick:{tenantRegionTag}:lock:<entityId>` so locks share a hash tag with tick queues and pending state as described in [Redis Architecture](../../system-architecture-redis.md#key-format-examples).
  - Treats lock TTLs and other coordination parameters as opaque values derived by the Game Session Service and shared helpers; it does not define its own coordination-specific configuration.
- **Cache/Rate-Limit Redis usage**
  - Uses **Cache/Rate-Limit Redis** to cache frequently accessed character graphs and related aggregates under prefixes such as `character-cache:<tenantId>:<characterId>`, following the key naming and TTL/versioning patterns in [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md).
  - These character graph caches are treated as **Class A, versioned caches**:
    - Cached payloads include a stable version or `lastModified` value derived from the authoritative character tables.
    - Readers validate versions against PostgreSQL (or version fields surfaced via gRPC) before reusing cached data; on mismatch they recompute the graph and overwrite the cache atomically (value + TTL).
    - TTLs (for example, `FIREMUD_CHARACTER_CACHE_TTL_SECONDS`) act as a safety valve for memory and stale entries, not as the primary correctness mechanism.
  - Future inventory/containment caches use the `inventory:<tenantId>:<containerId>` prefix from the Redis cache catalog:
    - Inventories and containers (including room-ground containers) are also treated as **Class A**: authoritative state and versions live in PostgreSQL, and cache entries must be invalidated via events or version checks when items move.
    - Event-based invalidation is driven by Entity Management’s own domain events (inventory changed, item moved, container destroyed); listeners delete or refresh affected `inventory:*` keys.
    - Implementations must document which APIs expose the version/`lastModified` fields used for these caches and keep them aligned with the central `inventory:*` entry in `system-architecture-redis-cache.md`.
- Any change to Redis usage in this service should be reviewed against the [Redis Design Checklist](../../system-architecture-redis-design-checklist.md) to confirm prefix registration, role selection, slotting, and observability updates.

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
- Many-to-many tables define inventory and equipment relationships, including container contents and room/ground inventory. Room/ground inventory is modeled as items whose container references a synthetic room-ground container entity keyed by room/instance identifier so limits such as max items on the ground or special container rules can be enforced consistently.
- Character location and instance membership are stored by the World Management
  Service rather than this service, but all item instances and inventories remain owned and persisted here.
- Entity graphs cache inventory relationships for fast lookups.

### gRPC APIs

- `CreateCharacter` – builds a new player character from a template.
- `UpdateEntity` – updates stats or equipment for a character or NPC.
- `QueryInventory` – lists items for an entity with pagination.
- `ListCharactersByAccount` – returns all characters owned by an account across tenants.
- `ListRoomEntities` – returns players, NPCs, and visible items present in a room, filtered by `tenantId` and `roomId`.

### LOOK entity listing contract

`ListRoomEntities` is the dedicated endpoint for `LOOK` to discover which characters, items, and NPCs occupy a room. The response includes:

- `roomId`, `tenantId`, and a `snapshotId` so consumers can cache or invalidate entity lists deterministically.
- `entities[]`, each with `entityId`, `displayName`, `entityType` (`PLAYER`, `NPC`, `ITEM`), and optional `role`/`affiliation`.
- `stateFlags` such as `isHidden`, `isInCombat`, or `isQuestTarget` so Game Logic can mask stealthy entities or highlight objectives.
- `visionPriority` to help sort players before NPCs and list visible items at the end, keeping `LOOK` render ordering consistent.
- `reloadHint` (enum) that signals whether the list is stable or dynamic, allowing Game Logic to decorate the `LOOK` output (for example, “Someone just entered from the east.”).

Room-entity data is currently seeded through the `firemud.look.rooms` configuration (per-tenant/room entries in `services/entity-management-service/src/main/resources/application.yml`). Each room definition lists the `entities` with their `entity-id`, `entity-type`, friendly display name, `state-flags`, `vision-priority`, and visibility hints so the recorded LOOK transcripts stay deterministic during this vertical slice. Once the shared location cache is reliable, the configuration can be replaced with live reads from `character_location`/`npc_location` tables while item instances and room/ground inventory continue to live in this service.

Only entities approved by the `EntityVisibilityPolicy` are returned; hidden NPCs, private inventory, or offstage summons are filtered out so `LOOK` always aligns with the player’s perspective. The response deliberately omits detailed stats to keep the text output focused on presence rather than numbers.

### Implementation status (LOOK slice)

- **Live:** The seeded `firemud.look.rooms` entries provide the visible entities for the demo rooms, `ListRoomEntities` is wired into Game Logic's `ResolveLook`, and the resulting instrumentation is captured in `../../project-management/look-instrumentation.md`.
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
- [User Journeys – World and Entity Design](../../user-journeys.md#3-world-and-entity-design)
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

```bash
grpcurl -plaintext localhost:6565 entity_management.v1.EntityManagementService/Ping
```

### Tick Locking

This service participates in tick processing by acquiring Redis locks before mutating entity state. The `TickLockService` uses the `tick:{tenantRegionTag}:lock:<entityId>` key described in the [Redis Architecture](../../system-architecture-redis.md) document so that lock keys share a hash tag with tick queues and pending state. Lock TTLs follow the simplified formula from the Redis design:

- The Game Session Service exposes `game.tick-interval-ms` as the primary pacing knob.
- Internally it derives a soft budget and TTLs, for example:
  - `tick_budget_ms = tick_interval_ms * 0.8`
  - `lock_ttl_ms = clamp(tick_budget_ms * 8, 500, 5_000)`

Entity Management treats `lock_ttl_ms` as an opaque value supplied by shared helpers; it does not define its own lock TTL configuration. This keeps locks alive long enough for normal ticks to complete while still bounding the recovery window for stalled ticks.

At runtime, the Game Session Service also compares **observed tick execution time** to `lock_ttl_ms` as described in the [Tick System design](../../system-architecture-ticks.md#timeout-and-fairness-policy). Regions whose `p99` tick runtime begins to approach or exceed a configured fraction of `lock_ttl_ms` are treated as degraded, and operators are expected to either increase the tick interval or simplify per-tick work. Entity Management does not adjust TTLs itself; it relies on the shared helpers and scheduler behavior to keep lock usage within safe bounds.

Entity Management assumes the **per-command execution phases** described in the [Tick System design](../../system-architecture-ticks.md#per-command-execution-phases): commands that touch multiple entities in the same region resolve their target set first (for example, the two parties in a trade or all entities in a room for AoE effects), then acquire the necessary `tick:{tenantRegionTag}:lock:<entityId>` keys in a deterministic order. If any required lock is unavailable, the command fails, staged changes are rolled back via Redis, and the Game Session Service reschedules the work using the retry mechanisms described in the Tick System and Redis designs.

### Tick Idempotency

Entity Management implements tick idempotency using the **per-aggregate last-tick state** pattern described in the [Tick System and Runtime Design](../../system-architecture-ticks.md#domain-idempotency-rules-tickid-in-postgresql) document:

- A shadow table (for example `entity_tick_state`) tracks `last_tick_id` (and associated tenant/region metadata) per `entityId`.
- Tick-driven handlers that mutate an entity:
  - Load the current tick state for that `entityId`.
  - Treat calls where `last_tick_id >= currentTickId` as **replays** and perform a no-op (or validation-only check).
  - Apply changes only when `last_tick_id < currentTickId`, then update `last_tick_id = currentTickId` in the same transaction as the entity update.

Complex multi-entity operations (for example trades that touch two inventories) use the **operation-level effect guard** pattern described in the same tick document, inserting a `(tenantId, regionId, tickId, effectKey)` row into a guard table before applying changes so replays of the same logical effect become safe no-ops instead of double-applications.

Examples:

- **Damage application** – when a tick instructs Entity Management to apply damage to `entityId`, the handler:
  - Reads `entity_tick_state` for that `entityId`.
  - Skips the update if `last_tick_id >= currentTickId` (replay), or applies the HP change and sets `last_tick_id = currentTickId` in the same transaction if `last_tick_id < currentTickId`.

- **Trade between two entities** – when a tick performs a trade between `fromEntityId` and `toEntityId`:
  - The handler computes a deterministic `effectKey` such as `trade:<fromEntityId>:<toEntityId>:<itemId>`.
  - It inserts `(tenantId, regionId, tickId, effectKey)` into the guard table before moving items between inventories.
  - On primary-key conflict, the trade is treated as an already-applied effect for that tick and becomes a no-op.

- [System Architecture Diagram](../../system-architecture-diagram.md)
- [System Context Diagram](../../system-context-diagram.md)
