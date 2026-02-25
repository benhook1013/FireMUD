# World Management Service

## Overview

The World Management Service stores and manages game world topology and content such as rooms, regions, and maps. It persists world state beyond player sessions, handles scheduled world events, and notifies other services over gRPC when the environment changes. Live entities, items, and inventories are always owned by the Entity Management Service; World Management never stores room inventory or item instances.

### Responsibilities

- Persist region, zone, and room data with tenant isolation
- Execute scheduled world events.
- Provide procedural generation support.
- Expose geometry and region metadata; pathfinding algorithms are handled by the Movement/Travel subsystem (Game Logic Service) via gRPC. World Management stores and publishes versioned navmesh/path graph artifacts as part of the `(tenantId, versionId)` template bundle so Game Logic can load consistent inputs.
- Notify Game Session and Automation services when the world changes
- Track character locations and instance occupancy
- Do not store or manage live item or inventory state; room inventory and ground items are derived from Entity Management queries scoped by room/instance identifiers.

### Identifiers

World Management distinguishes the following identifiers when storing and
serving data:

- `tenantId` – identifies the game (tenant) as described in
  [Multi-Tenancy](../../system-architecture-multi-tenancy.md).
- `versionId` – identifies a published world/template configuration owned by
  domain services as described in
  [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md).
- `gameInstanceId` – identifies a running game instance managed by the Game
  Session Service.

For canonical naming and scoping rules, see [Identifier Glossary](../../system-architecture-identifier-glossary.md).

World data uses two distinct identifier families. Do not use ambiguous names like `roomId` in APIs or schema documentation without specifying which family is in use.

- **Template identifiers (design-time, version-scoped):**
  - `regionTemplateId`, `zoneTemplateId`, `roomTemplateId` identify authored topology rows under `(tenantId, versionId)`.
  - A `RoomTemplateRef` is `(tenantId, versionId, roomTemplateId)`.
- **Instance identifiers (runtime, instance-scoped):**
  - `regionInstanceId`, `zoneInstanceId`, `roomInstanceId` identify materialized runtime rows under `(tenantId, gameInstanceId)`.
  - A `RoomInstanceRef` is `(tenantId, gameInstanceId, roomInstanceId)`.

Template/topology data is keyed by `(tenantId, versionId)`, while runtime world
instances are keyed by `(tenantId, gameInstanceId)` with references back to the
active `versionId`.

In practice this means:

- **Template tables** (world layout and generation metadata) are versioned by `(tenantId, versionId)` and are updated only through design-time workflows coordinated by the Game Design Service.
- **Instance tables** (per-game-instance regions, rooms and transient instances) are keyed by `(tenantId, gameInstanceId)` and are created or mutated only by world-creation Sagas and tick-driven gameplay flows. Runtime logic must never modify template rows for published versions.

### Template Identifier Invariants

World templates follow the same stability rules as entity templates:

- Template identifiers for regions, zones, rooms, and related topology (for example `region_template_id`, `room_template_id`) must not be repurposed to represent different conceptual locations while any non-Retired version or game template still references them.
- Structural changes to world layouts (such as splitting a region, renumbering rooms, or replacing a template) must be modeled as new template rows under the appropriate `(tenantId, versionId)` rather than mutating existing identifiers in-place. Older templates remain readable for all non-Retired versions that depend on them.
- Cross-service references from Game Design Service (revisions, game templates) or other domain services must use these stable template identifiers scoped by `(tenantId, versionId)` and follow the version-aware migration rules in [Database Migrations](../../system-architecture-database-migrations.md) when introducing replacements or removing deprecated templates.

### Character Location Ownership

World Management is the authoritative owner of character and NPC location for each game instance:

- Runtime tables such as `character_location` and `npc_location` live in this service’s schema and are written **only** by World Management logic as part of movement, instancing, and world-creation flows invoked by the Game Session Service.
- Other services, including Entity Management and Game Session, treat these tables as read-only and rely on World Management gRPC APIs or cached projections when they need to resolve where entities are.
- Any derived caches or denormalized views of location (for example, within Entity Management’s LOOK helpers) must be refreshed from World Management rather than persisting their own “authoritative” location fields.

#### Spatial Effects Contract (World ↔ Entity)

Movement, drops, pickups, and room presence are cross-service by design:

- World Management is authoritative for occupancy/location and for persistent ambient room state (weather, doors, hazards) keyed by runtime `RoomInstanceRef`.
- Entity Management is authoritative for containment and item instances, including synthetic room-ground containers keyed by the same runtime `RoomInstanceRef`.

All spatial effects must carry the target `RoomInstanceRef` and a canonical tick `EffectId`. Both services must implement durable idempotency guards so partial success can be retried safely until convergence. See [Transaction Strategies](../../system-architecture-transactions.md) and [Identifier Glossary](../../system-architecture-identifier-glossary.md).

Ambient world mutations (doors, hazards, weather) follow the same rule: they are applied only via effect-shaped commands carrying `EffectId` + `RoomInstanceRef`. Operators and scripts must not write World Management instance tables directly.

Concrete per-effect required writes and reconciliation rules live in `design/architecture/system-architecture-spatial-and-ambient-effects-catalog.md`.

## Architecture / Design Notes

- World data is stored in PostgreSQL. Redis holds only transient active state used during gameplay.
- Changes are persisted incrementally to avoid heavy writes.
- Background tasks trigger scheduled world changes and notify other services over gRPC.
- Supports procedural generation with options for dynamic world expansion.
- Uses a region → zone → room hierarchy for efficient lookups.
- Publishes world event notifications for NPC scripts and game logic processing.
- During version publishing the service participates in a Saga that finalizes versioned world template data for each `tenantId` and `version_id`, ensuring world data matches the active version. See
  [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md)
  and [Transaction Strategies](../../system-architecture-transactions.md).
- World creation for new games runs as a Saga, inserting a starter region instance and seeding initial world state for a particular `gameInstanceId` based on the published version. This Saga reads only template/topology rows keyed by `(tenantId, versionId)` and writes only instance rows keyed by `(tenantId, gameInstanceId)`; it never mutates template data for published versions. See [World Creation Workflow](world-creation-workflow.md).
- All world tables are keyed by `tenantId`; background jobs and gRPC queries
  include this filter so one game's world data never mixes with another's. See
  [Multi-Tenancy](../../system-architecture-multi-tenancy.md).
- Runtime gameplay gRPC operations do not validate JWTs directly. The Game Session Service binds player identity from Redis into `SessionContext`. It may request an updated JWT from the Account Service when roles change but does not perform token validation during gameplay. All traffic still uses mutual TLS as described in the [Security Architecture](../../system-architecture-security.md).
- Design-time writes are a separate surface:
  - World Management exposes **design APIs** used by the Game Design Service to mutate Draft template rows keyed by `(tenantId, versionId)`.
  - These design APIs must validate JWTs and enforce designer/admin authorization for the target `tenantId` and Draft `versionId` (consistent with the Game Design Service control-plane auth model).
  - Design APIs must reject any attempt to write templates for Published/Active/Failed versions.
- Utilizes the [Shared Libraries](../../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.
- gRPC endpoints include the shared `LoggingInterceptor`, `MetricsInterceptor`, and
  `TracingInterceptor` so logs, metrics, and traces are emitted consistently.

### Redis Role and Prefixes

- **Coordination Redis participation**
  - Does not own tick or session coordination prefixes; tick queues, locks, timers, and region leases remain owned by the Game Session Service and its Lua registry as described in [Redis Architecture](../../system-architecture-redis.md#redis-coordination-invariants).
  - Interacts with Coordination Redis only indirectly via Game Session and Automation & Scripting APIs; it does not issue coordination writes itself.
- **Cache/Rate-Limit Redis usage**
  - Uses **Cache/Rate-Limit Redis** to cache hot room and topology slices for active sessions under prefixes such as `room:<tenantId>:<gameInstanceId>:<roomInstanceId>` and `world-dynamic:<tenantId>:<aggregateId>`, consistent with [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md#key-naming-and-overwrite-expectations).
  - These caches are derived from PostgreSQL tables (`region`, `zone`, `room`, and related metadata) and are treated as **versioned, Class A caches** where version fields exist:
    - World records expose stable version or `lastModified` fields (for example per-room revision columns on `room` or related tables) that are surfaced through World Management’s gRPC APIs.
    - Cache entries store both the payload and the version; consumers compare versions to the authoritative value before reuse and recompute/overwrite on mismatch.
  - For simpler read-mostly slices or derived aggregates that are safe to recompute, World Management may use **TTL-only** caching (Class B) as long as:
    - TTLs remain short and bounded (for example, `WORLD_ROOM_CACHE_TTL_SECONDS`), and
    - The design explicitly states that occasional staleness is acceptable for the affected views and that authoritative reads go back to PostgreSQL when correctness is required.
    - TTL-only world caches use **distinct prefixes** from `world-dynamic:*` / `room:*` and are added to the central Cache/Rate-Limit Key Catalog in `system-architecture-redis-cache.md` with their own Class B entries; `world-dynamic:*` and `room:*` remain reserved for versioned, Class A aggregates. No TTL-only world prefixes are currently registered; new ones must be added to the catalog and this section before implementation.
  - Room/world cache invalidation follows the Redis cache design:
    - Domain events for room changes, region version activations, or world updates drive explicit deletion or refresh of affected `room:*` / `world-dynamic:*` keys.
    - TTL acts as a safety valve and memory control, not the primary correctness mechanism for Class A caches.
  - Cache metrics for `world-dynamic:*` and `room:*` should follow the recommendations in `system-architecture-redis-cache.md` (for example `cache.world_dynamic_hits_total` / `cache.world_dynamic_misses_total` and `cache.room_hits_total` / `cache.room_misses_total`), with gauges for key counts where available, so operators can see how these caches behave.
  - Tests for these caches are expected to exercise the Class A scenarios described in `system-architecture-redis-cache.md`, including version mismatches, event-driven invalidation, and behavior after cache resets; see this service’s testing docs for details.
- When changing Redis usage or adding new prefixes here, follow the [Redis Design Checklist](../../system-architecture-redis-design-checklist.md) to ensure correct role, slotting, and SLO coverage.

> If you change Redis usage for this service, you must read and apply:
>
> - [Redis Architecture](../../system-architecture-redis.md)
> - [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md)
> - [Redis Operations & Migrations](../../system-architecture-redis-operations.md)

## Key Features

- Region and location management for each running game instance (`tenantId`, `gameInstanceId`), including authoritative occupant/location tables owned by this service.
- Horizontal scaling via stateless replicas and stable in-cluster service discovery (no per-region shard routing plane or cross-cluster handoff contract in the core architecture).
- Instance-based zones are treated as regular rooms; this service records which
  characters occupy each instance so private dungeons or housing do not affect
  the shared world map.
- Persistent world state with incremental saves (rooms, regions, instances, and environmental state), excluding live entities, item instances, and inventories which are persisted by the Entity Management Service.
- Procedural generation tools for rooms and terrain.
- Region metadata persists `seed`, `generatorType`, and raw parameters for every generated region so maps can be re-created or inspected later.
- The Movement/Travel subsystem in the Game Logic Service performs pathfinding using the `room_exit` graph (and, where applicable, precomputed navmesh artifacts stored and published by World Management) and exposes results via gRPC.
- Event scheduling for world-wide holidays or timed modifiers. A `world_event` table
  stores pending events and a scheduled task processes them, updating regional weather
  or other state. Emitting gRPC notifications keeps other services synchronized.
- Chunk-based world snapshots for backup and recovery. These snapshots cover world topology and ambient world metadata only; live entities, items, and inventories are restored from the Entity Management Service.

### Data Model

- **Template tables** (keyed by `(tenantId, versionId)`):
  - `region_template`, `zone_template`, and `room_template` define the versioned world hierarchy for each game. These rows are treated as immutable once a version reaches the Published state described in [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md); design-time workflows create or update them only for Draft versions.
  - `terrain_template` and related tables capture generator outputs or authored terrain data where it is part of the versioned topology.
- **Instance tables** (keyed by `(tenantId, gameInstanceId)` and referring back to the active `versionId`):
  - `region_instance`, `zone_instance`, and `room_instance` materialize the topology for a running game instance based on the chosen version and any runtime procedural generation.
  - `instance` table tracks temporary copies of zones for instanced gameplay, with an `expires_at` column defining when instances enter the `InstanceTermination` workflow.
  - `world_instance_status` (or equivalent lifecycle column) tracks monotonic lifecycle transitions: `PREPARING` → (`ACTIVE` | `FAILED_PRE_ACTIVATION`) and `ACTIVE` → `TERMINATING` → `TERMINATED`.
  - `FAILED_PRE_ACTIVATION` is terminal for that `gameInstanceId`; recovery is modeled as provisioning a new `gameInstanceId` and rerunning world creation.
  - `world_instance_lifecycle_lock` (or equivalent fenced token) enforces single-writer lifecycle transitions per `(tenantId, gameInstanceId)` so activation and termination workflows cannot commit concurrently.
  - `character_location` table records the current room for each character, including which instance they are in; item locations and containment are modeled and stored by the Entity Management Service rather than this table.
- **Runtime configuration and events**:
  - `generation_rule` table stores per-tenant procedural generation parameters used by the [Procedural Generation Rules API](#procedural-generation-rules-api). These rules are runtime configuration defaults, not versioned design data; each generation run snapshots the effective rule set (including `schemaVersion`) alongside the affected template or instance records so that the inputs for that run remain reconstructable even if live rules are later updated.
  - An optional `generation_rule_override` table may store version-specific overrides keyed by `(tenantId, versionId)` for tenants that require different tuning per version; when present for a given version, overrides are applied instead of the tenant-global defaults when running generators for that version. Overrides may exist only for non-Retired versions and must be kept consistent with the version lifecycle and migration rules described in [Database Migrations](../../system-architecture-database-migrations.md).
  - `generation_run` (or equivalent) persists deterministic generation artifacts for replay-safe publish/reconciliation (`generationRunId`, `generationRequestId`, `generatorImplementationVersion`, canonical `configSnapshot`, and `outputDigest`).
  - `world_event` table stores timed changes such as weather updates.
  - `region_instance.weather` (or equivalent) records the current weather state for live regions; template rows may include default weather or climate metadata but are not updated during gameplay.
- Redis caches hot rooms for active sessions to speed up lookups.
- Cached rooms use keys `room:<tenantId>:<gameInstanceId>:<roomInstanceId>` and expire after `world.room.cache-ttl-seconds`. Caches must never be keyed by template identifiers because instance rows may differ due to runtime generation, instancing, or transient ambient state.

### gRPC APIs

- `GetRoom` – retrieves room data including exits and environmental effects.
- `GetRoomSnapshot` – returns a minimal, `LOOK`-focused view (room identity, names, descriptions, exit metadata, ambient state) scoped by `RoomInstanceRef`.
- `ListRoomOccupants` – returns the authoritative typed occupant list (`occupants`) for actors in a room, scoped by `RoomInstanceRef`. The legacy `occupantEntityIds` list is a derived compatibility mirror only.
- `ApplyRoomAmbientStatePatch` – applies an ambient state patch to the target `RoomInstanceRef`, guarded by `EffectId`.
- `GetDraftDesignDigest` – returns publish-gating digest for Draft world templates keyed by `(tenantId, versionId)`. Minimum response fields are `{tenantId, versionId, appliedCommitId or lastAppliedRevisionId, contentDigest, digestSchemaVersion}`. `contentDigest` must cover only version-scoped template/binding rows (for example region/zone/room templates and spawn bindings) and must exclude runtime/instance rows and audit metadata.
- `UpdateWorldState` – deprecated legacy bulk update surface. It is not authoritative for runtime mutations and remains only for backwards compatibility during migration.

### Instance termination contract (World ↔ Entity)

World Management owns the lifecycle of `gameInstanceId` rows, but teardown is a cross-service workflow:

- Game Session must first mark the instance non-admissible/draining before World transitions lifecycle.
- Expiry or operator shutdown transitions the instance to `TERMINATING` and starts an `InstanceTermination` Saga.
- Entity Management must acknowledge idempotent cleanup of containment and room-ground containers scoped to the same `(tenantId, gameInstanceId)` before World marks the instance `TERMINATED`.
- Scheduled expiry jobs must enqueue this Saga and must not directly delete world rows for a still-unconfirmed termination.
- Lifecycle fencing is mandatory: termination acquires the same per-instance lifecycle fence used by activation. If activation and termination race, termination is authoritative unless admission has already opened (`ACTIVE` committed).
- Game Session finalizes runtime `game_instances` termination only after World reports `TERMINATED`.

### LOOK snapshot contract

`GetRoomSnapshot` is the canonical endpoint feeding Game Logic’s `ResolveLook`. It should return:

- `tenantId`, `gameInstanceId`, and `roomInstanceId` (a `RoomInstanceRef`) so the caller can unambiguously scope the snapshot to a running instance.
- A stable `worldSnapshotId` (monotonic or content-hash) for this room’s LOOK-relevant world data so callers can cache or invalidate snapshots deterministically.
- A stable `roomName` (plus optional slug) suitable for UI display.
- `shortDescription` and `longDescription` text; descriptions longer than the `LOOK_MAX_DESCRIPTION_CHARS` config should be truncated with an ellipsis so clients don’t wrap aggressively.
- `exits`, each annotated with `label` (e.g., `NORTH`), `targetRoomInstanceId` (within the same `gameInstanceId`), and a human-friendly direction string (e.g., “arched passage toward the cavern mouth”). Game Logic renders this list into the `LOOK` exits line.
- `ambientState` fields such as `lighting`, `weather`, or `hazardLevel` to enrich the narrative without extra queries.
- `ambientStateV2` (typed, schema-versioned ambient state) as the canonical representation used by gameplay logic and cache keys.
- Legacy `ambientState` map support only as a derived compatibility payload for old clients; new runtime logic must not treat it as canonical.
- Optional `roomFlags` (for example `isQuestArea` or `isInstanceEntry`) so `LOOK` can warn players before they step into special zones.

Game Logic caches snapshots for the duration of a tick but refreshes them after movement. World Management publishes change events when rooms mutate so downstream caches remain consistent and `LOOK` clients never read stale text.

Room snapshots deliberately exclude live entities, items, and inventory contents; those are retrieved from the Entity Management Service using room- and instance-scoped queries when composing `LOOK` results.

Game Logic treats `worldSnapshotId` as the canonical cache key for LOOK-relevant world data for a specific `RoomInstanceRef`. When composing a full LOOK view, Game Logic combines:

- `worldSnapshotId` from `GetRoomSnapshot`, and
- `entitySnapshotId` from Entity Management’s `ListRoomEntities`,

then returns a `lookSnapshotId` (for example `worldSnapshotId + ":" + entitySnapshotId`) alongside the rendered `LookResult` so Game Session can cache the final transcript deterministically.

The `V10__seed_demo_world.sql` migration seeds the demo rooms referenced by this lifecycle (Candle-lit Antechamber and Crafting Hall of Ember) so integration tests and the LOOK transcripts stay stable. Developers can locate and extend that migration when the sample world needs more exits or environmental trivia.

### Implementation status (LOOK slice)

- **Live:** `GetRoomSnapshot` returns the room metadata, descriptions, and exit labels that Game Logic needs to render the canonical `LOOK` transcript, and the telemetry for this pipeline is documented in `../../../project-management/look-instrumentation.md`.
- **Stubbed:** The current snapshot data comes from the seeded demo rooms so scripted room events, line-of-sight lighting, and procedural text remain deterministic for regression tests.
- **Deferred:** Future work will push live snapshot updates through `/ws/game/**` so Gateway/TCP Proxy clients can react to world changes as soon as they happen.

### `/ws/game/**` LOOK contract and local overrides

- Telnet and WebSocket clients both route through the `/ws/game/**` WebSocket predicate on the Gateway and TCP Proxy stacks, so `LOOK` commands hit the same `LookCommandHandler` regardless of transport.
- Each authenticated success response follows the canonical contract:
  - The first line is `OK LOOK`.
  - `Room: <name> (Instance ID: <roomInstanceId>)`
  - `Short: <short description>`
  - `Long: <long description>`
  - `Exits: <comma-separated direction labels and descriptions>`
  - `Entities:` followed by a bulleted list of visible NPCs/players/items.
  - A blank line separates the response from subsequent commands so the transport remains stateless.
- Game Session renders the `LookResult` returned by Game Logic (which already includes both world and entity projections) into the textual transcript via `LookResultRenderer`, emitting the `gamesession.command.look.*` meters referenced in `../../../project-management/look-instrumentation.md` before replying to `/ws/game/**` clients or caching the payload for reconnection.
- Error responses emit `ERROR <CODE> <message>` in the same stream, covering `ROOM_NOT_FOUND`, `WORLD_UNAVAILABLE`, `ENTITY_UNAVAILABLE`, `LOOK_UNAVAILABLE`, and `NOT_AUTHENTICATED`.
- Override the World Management endpoint locally via the `FIREMUD_SERVICES_WORLD_MANAGEMENT_SERVICE` environment variable (some developer helpers refer to this value as `WORLD_SERVICE_ENDPOINT`) when running the Gateway or Game Session stack against custom world servers; the same override is wired into the `look` cross-service tests so the sample world and entity fixtures stay consistent.

## Dependencies

- **Internal:**
  - Game Design Service supplies generation rules and versioned world data.
  - Game Session Service queries rooms and receives world event updates.
  - Automation & Scripting Service reacts to scheduled world changes.
- **External:** PostgreSQL for world data, Redis for transient active state.

> See [**Gateway Architecture**](../../system-architecture-gateway.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../system-architecture-protocol-bridging.md) for
details on shared infrastructure components.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.

## Environment Variables

World Management Service uses the configuration scheme defined in
[Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md).
It depends on the [PostgreSQL credentials](../../infrastructure/environment-and-secrets.md#postgresql-credentials)
and [Redis connection](../../infrastructure/environment-and-secrets.md#redis-connection).
TLS certificates are supplied via [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates). Peer services can be discovered using variables prefixed `FIREMUD_SERVICES_`.
The OpenTelemetry collector endpoint can be overridden via `OTEL_ENDPOINT` (see [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md)).

Additional variables configure world data caching and housekeeping:

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `WORLD_ROOM_CACHE_TTL_SECONDS` | Seconds to retain room data in the cache | `60` |
| `WORLD_INSTANCE_EXPIRATION_HOURS` | Hours before a transient instance expires | `24` |
| `WORLD_EVENT_CHECK_DELAY_MS` | Delay between event processing checks (ms) | `60000` |

## Proto Files

The gRPC contract for world operations is located in
[../../../../protos/world-management/v1](../../../../protos/world-management/v1).
Run `./gradlew generateProto` to regenerate sources after editing these files.

## Related Documentation

- [System Architecture Overview](../../system-architecture-overview.md)
- [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md)
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

The service creates temporary **instances** of zones for dungeons or housing. Instances expire automatically based on the `world.instance.expiration-hours` property. Expiry processing enqueues `InstanceTermination` workflows; direct periodic deletion of instance rows is not a valid cleanup path.

### REST & gRPC Endpoints

#### REST

- `GET /ping` – basic health check returning `"pong"`.
- `GET /regions?tenantId=...` – list regions for a tenant.

The service exposes an OpenAPI specification under `/v3/api-docs` with a Swagger UI at `/swagger-ui.html` when running locally.

```bash
curl http://localhost:8080/ping
```

Requests to this service come from other internal services. Player identity is
established by the Game Session Service, so no JWT header is required here. See
the [Security Architecture](../../system-architecture-security.md) for details.

#### gRPC

- `Ping(PingRequest) returns (PingResponse)` – basic connectivity check defined in [`world_management_service.proto`](../../../../protos/world-management/v1/world_management_service.proto).
- `GetRoom(GetRoomRequest) returns (GetRoomResponse)` – fetches a room's JSON representation. The legacy `room_id` field is deprecated; callers should provide a `RoomInstanceRef`.
- `GetRoomSnapshot(GetRoomSnapshotRequest) returns (GetRoomSnapshotResponse)` – returns the minimal, LOOK-focused snapshot scoped by `RoomInstanceRef`.
- `ListRoomOccupants(ListRoomOccupantsRequest) returns (ListRoomOccupantsResponse)` – returns canonical typed occupants for a `RoomInstanceRef`.
- `ApplyRoomAmbientStatePatch(ApplyRoomAmbientStatePatchRequest) returns (ApplyRoomAmbientStatePatchResponse)` – applies effect-idempotent ambient mutations to a room instance.
- `UpdateWorldState(UpdateWorldStateRequest) returns (UpdateWorldStateResponse)` – deprecated compatibility API; new runtime behavior must use effect-shaped mutation RPCs.

Call the `Ping` method with:

```bash
grpcurl -plaintext localhost:6565 world_management.v1.WorldManagementService/Ping
```

Expected response:

```json
{
  "message": "pong"
}
```

#### Design-Time APIs

World Management also exposes **design-time** APIs used by the Game Design Service to write Draft template rows keyed by `(tenantId, versionId)` (for example creating/updating `room_template` rows and version-scoped topology bindings).

- Auth: design-time APIs must validate JWTs and enforce designer/admin authorization for the target `tenantId`.
- Mutability: design-time writes are allowed only for Draft versions; attempts to write templates for Published/Active/Failed versions must fail fast.
- Runtime isolation: runtime gameplay flows and world-creation Sagas must never call design APIs.

World Management must also expose a read-only design-time synchronization surface so the Game Design Service can validate convergence before publish:

- `GetDraftDesignDigest(tenantId, versionId)` returns `appliedCommitId` (or last applied revision), a stable `contentDigest`, and a `digestSchemaVersion` as described in `design/architecture/microservices/game-design-service/world-editing-tools.md`.

Digest input manifest requirements (World Management):

- Included objects: version-scoped topology/binding rows for `(tenantId, versionId)` (for example `region_template`, `zone_template`, `room_template`, spawn/population binding tables, and any version-scoped generation artifacts used for publish).
- Excluded objects: runtime/instance tables keyed by `gameInstanceId`, audit/history tables, and non-semantic write-time metadata fields (`created_at`, `updated_at`).
- Canonicalization: deterministic table ordering, primary-key ordering within table, and stable field encoding.
- `digestSchemaVersion` must be incremented when included/excluded object sets or canonicalization rules change.

### World Events

World events are persisted in the `world_event` table and processed periodically by `WorldEventService`.

World event invariants:

- Events are runtime-only and must be keyed by `(tenantId, gameInstanceId)` (they must not be stored as `(tenantId, versionId)` template artifacts).
- Event application must be idempotent. Each event carries a stable event identity (or derives one from `(tenantId, gameInstanceId, eventId, eventType, scheduledTickId)`), and World Management must guard against double-application on retries or restarts.
- A weather change event updates the runtime weather field (for example `region_instance.weather`) for the affected `(tenantId, gameInstanceId)` before notifying other services.
- Event application must use the same effect-shaped ambient mutation contract used by tick execution: durable mutations to ambient world state must be guarded by an `EffectId` and scoped by instance identifiers (for example `RoomInstanceRef` or `(tenantId, gameInstanceId, regionInstanceId)`).

### Saga Participation

World creation for a new game instance runs as a Saga using the helper utilities from `firemud-common`. Each step is described in [world-creation-workflow.md](world-creation-workflow.md) and can be rolled back if a later step fails. This ensures instance world state is created consistently even when the workflow spans multiple services.

- [System Architecture Diagram](../../system-architecture-diagram.md)
- [System Context Diagram](../../system-context-diagram.md)

## Procedural Generation Rules API

Administrators can tweak procedural generation without redeploying the service.
Rules are stored in the `generation_rule` table and managed over REST:

- `POST /generation/rules` – create or update a rule for a tenant
- `GET /generation/rules?tenantId=...` – list rules for a tenant

These endpoints allow live tuning of parameters such as room density or terrain
variation. Updates are persisted immediately and picked up by the procedural
generation engine on the next run.
