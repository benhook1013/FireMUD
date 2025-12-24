# World Management Service

## Overview

The World Management Service stores and manages game world topology and content such as rooms, regions, and maps. It persists world state beyond player sessions, handles scheduled world events, and notifies other services over gRPC when the environment changes. Live entities, items, and inventories are always owned by the Entity Management Service; World Management never stores room inventory or item instances.

### Responsibilities

- Persist region, zone, and room data with tenant isolation
- Execute scheduled world events.
- Provide procedural generation support.
- Expose geometry and region metadata; pathfinding is handled by the Movement/Travel subsystem (Game Logic Service) via gRPC with navmesh support.
- Notify Game Session and Automation services when the world changes
- Track character locations and instance occupancy
- Do not store or manage live item or inventory state; room inventory and ground items are derived from Entity Management queries scoped by room/instance identifiers.

## Architecture / Design Notes

- World data is stored in PostgreSQL. Redis holds only transient active state used during gameplay.
- Changes are persisted incrementally to avoid heavy writes.
- Background tasks trigger scheduled world changes and notify other services over gRPC.
- Supports procedural generation with options for dynamic world expansion.
- Uses a region → zone → room hierarchy for efficient lookups.
- Publishes world event notifications for NPC scripts and game logic processing.
- During version publishing the service participates in a Saga that finalizes versioned world data for each `tenantId` and `version_id`, ensuring world data matches the active version. See
  [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md)
  and [Transaction Strategies](../../system-architecture-transactions.md).
- World creation for new games runs as a Saga, inserting a starter region instance and seeding initial world state based on the published version. See [World Creation Workflow](world-creation-workflow.md).
- All world tables are keyed by `tenantId`; background jobs and gRPC queries
  include this filter so one game's world data never mixes with another's. See
  [Multi-Tenancy](../../system-architecture-multi-tenancy.md).
- Gameplay gRPC operations do not validate JWTs directly. The Game Session
  Service binds player identity from Redis into `SessionContext`. It may request
  an updated JWT from the Account Service when roles change but does not perform
  token validation during gameplay. All traffic still uses mutual TLS as described in the
  [Security Architecture](../../system-architecture-security.md).
- Utilizes the [Shared Libraries](../../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.
- gRPC endpoints include the shared `LoggingInterceptor`, `MetricsInterceptor`, and
  `TracingInterceptor` so logs, metrics, and traces are emitted consistently.

### Redis Role and Prefixes

- **Coordination Redis participation**
  - Does not own tick or session coordination prefixes; tick queues, locks, timers, and region leases remain owned by the Game Session Service and its Lua registry as described in [Redis Architecture](../../system-architecture-redis.md#redis-coordination-invariants).
  - Interacts with Coordination Redis only indirectly via Game Session and Automation & Scripting APIs; it does not issue coordination writes itself.
- **Cache/Rate-Limit Redis usage**
  - Uses **Cache/Rate-Limit Redis** to cache hot room and topology slices for active sessions under prefixes such as `room:<tenantId>:<roomId>` (or equivalent `world-dynamic:<tenantId>:<aggregateId>` shapes), consistent with [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md#key-naming-and-overwrite-expectations).
  - Cached rooms and world aggregates are derived from PostgreSQL tables (`region`, `zone`, `room`, and related metadata). When version fields are available on the underlying aggregates, these caches are treated as **strongly validated caches** (version-checked before reuse); simpler, read-mostly slices may use best-effort TTL-only caching as long as correctness is preserved by database reads. Updates are propagated via explicit invalidation or TTL-based expiry.
  - When changing Redis usage or adding new prefixes here, follow the [Redis Change Checklist](../../system-architecture-redis.md#redis-change-checklist) to ensure correct role, slotting, and SLO coverage.

> If you change Redis usage for this service, you must read and apply:
>
> - [Redis Architecture](../../system-architecture-redis.md)
> - [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md)
> - [Redis Operations & Migrations](../../system-architecture-redis-operations.md)

## Key Features

- Region and location management with shard support. Each region stores a
  `shard_id` value so the world can span multiple servers.
- Automated region redistribution balances shard load when cluster capacity changes.
- Instance-based zones are treated as regular rooms; this service records which
  characters occupy each instance so private dungeons or housing do not affect
  the shared world map.
- Persistent world state with incremental saves (rooms, regions, instances, and environmental state), excluding live entities, item instances, and inventories which are persisted by the Entity Management Service.
- Procedural generation tools for rooms and terrain.
- Region metadata persists `seed`, `generatorType`, and raw parameters for every generated region so maps can be re-created or inspected later.
- The Movement/Travel subsystem in the Game Logic Service performs Dijkstra-based pathfinding using the `room_exit` table and exposes results via gRPC.
- Event scheduling for world-wide holidays or timed modifiers. A `world_event` table
  stores pending events and a scheduled task processes them, updating regional weather
  or other state. Emitting gRPC notifications keeps other services synchronized.
- Chunk-based world snapshots for backup and recovery. These snapshots cover world topology and ambient world metadata only; live entities, items, and inventories are restored from the Entity Management Service.

### Data Model

- Tables for `region`, `zone`, and `room` define the world hierarchy.
- `terrain` and `object_spawn` tables support procedural generation.
- `instance` table tracks temporary copies of zones for instanced gameplay.
- `expires_at` column defines when instances are cleaned up by a scheduled job.
- `generation_rule` table stores per-tenant procedural generation parameters used
  by the [Procedural Generation Rules API](#procedural-generation-rules-api).
- `character_location` table  records the current room for each character, including which instance they are in; item locations and containment are modeled and stored by the Entity Management Service rather than this table.
- `world_event` table stores timed changes such as weather updates.
- `region.weather` column records the current weather state.
- `region.shard_id` indicates which server shard hosts the region.
- Redis caches hot rooms for active sessions to speed up lookups.
- Cached rooms use keys `room:<tenantId>:<roomId>` and expire after `world.room.cache-ttl-seconds`.

### Multi-Server Shards

Large worlds can span multiple server clusters. Each `region` is assigned a
`shard_id` so the Game Session Service knows which cluster hosts the active
state for that region. When a player crosses into a region on another shard the
session handoff flow described in the Game Session Service design is invoked.
Administrators can reassign regions between shards using the `RegionController`
endpoint `POST /regions/{id}/move`, which updates the `shard_id` column for the
specified region.

### gRPC APIs

- `GetRoom` – retrieves room data including exits and environmental effects.
- `GetRoomSnapshot` – returns a minimal, `LOOK`-focused view (room id, names, descriptions, exit metadata, ambient state) scoped by `tenantId` and `roomId`.
- `UpdateWorldState` – applies pending world updates and notifies other services.

### LOOK snapshot contract

`GetRoomSnapshot` is the canonical endpoint feeding Game Logic’s `ResolveLook`. It should return:

- `roomId`, `tenantId`, and a stable `roomName` (plus optional slug) so the caller can cache or deduplicate snapshots.
- `shortDescription` and `longDescription` text; descriptions longer than the `LOOK_MAX_DESCRIPTION_CHARS` config should be truncated with an ellipsis so clients don’t wrap aggressively.
- `exits`, each annotated with `label` (e.g., `NORTH`), `targetRoomId`, and a human-friendly direction string (e.g., “arched passage toward the cavern mouth”). Game Logic renders this list into the `LOOK` exits line.
- `ambientState` fields such as `lighting`, `weather`, or `hazardLevel` to enrich the narrative without extra queries.
- Optional `roomFlags` (for example `isQuestArea` or `isInstanceEntry`) so `LOOK` can warn players before they step into special zones.

Game Logic caches snapshots for the duration of a tick but refreshes them after movement. World Management publishes change events when rooms mutate so downstream caches remain consistent and `LOOK` clients never read stale text.

Room snapshots deliberately exclude live entities, items, and inventory contents; those are retrieved from the Entity Management Service using room- and instance-scoped queries when composing `LOOK` results.

The `V10__seed_demo_world.sql` migration seeds the demo rooms referenced by this lifecycle (Candle-lit Antechamber and Crafting Hall of Ember) so integration tests and the LOOK transcripts stay stable. Developers can locate and extend that migration when the sample world needs more exits or environmental trivia.

### Implementation status (LOOK slice)

- **Live:** `GetRoomSnapshot` returns the room metadata, descriptions, and exit labels that Game Logic needs to render the canonical `LOOK` transcript, and the telemetry for this pipeline is documented in `../../project-management/look-instrumentation.md`.
- **Stubbed:** The current snapshot data comes from the seeded demo rooms so scripted room events, line-of-sight lighting, and procedural text remain deterministic for regression tests.
- **Deferred:** Future work will enrich snapshots with ambient metadata (weather, hazard warnings) and push updates through `/ws/game/**` so Gateway/TCP Proxy clients can react to world changes as soon as they happen.

### `/ws/game/**` LOOK contract and local overrides

- Telnet and WebSocket clients both route through the `/ws/game/**` WebSocket predicate on the Gateway and TCP Proxy stacks, so `LOOK` commands hit the same `LookCommandHandler` regardless of transport.
- Each authenticated success response follows the canonical contract:
  - The first line is `OK LOOK`.
  - `Room: <name> (ID: <roomId>)`
  - `Short: <short description>`
  - `Long: <long description>`
  - `Exits: <comma-separated direction labels and descriptions>`
  - `Entities:` followed by a bulleted list of visible NPCs/players/items.
  - A blank line separates the response from subsequent commands so the transport remains stateless.
- Game Session aggregates the `LookResult` from Game Logic with cached Redis metadata (session context, last room snapshot) and renders the textual transcript via `LookResultRenderer`, emitting the `gamesession.command.look.*` meters referenced in `../../project-management/look-instrumentation.md` before replying to `/ws/game/**` clients or caching the payload for reconnection.
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

Additional variables configure world data caching and sharding:

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `WORLD_LOCAL_SHARD_ID` | Numeric identifier for this shard instance | `0` |
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

The service creates temporary **instances** of zones for dungeons or housing. Instances expire automatically based on the `world.instance.expiration-hours` property and a scheduled cleanup task removes expired records hourly.

### REST & gRPC Endpoints

#### REST

- `GET /ping` – basic health check returning `"pong"`.
- `GET /regions?tenantId=...` – list regions for a tenant.
- `POST /regions/{id}/move` – change a region's shard assignment.

The service exposes an OpenAPI specification under `/v3/api-docs` with a Swagger UI at `/swagger-ui.html` when running locally.

```bash
curl http://localhost:8080/ping
```

Requests to this service come from other internal services. Player identity is
established by the Game Session Service, so no JWT header is required here. See
the [Security Architecture](../../system-architecture-security.md) for details.

#### gRPC

- `Ping(PingRequest) returns (PingResponse)` – basic connectivity check defined in [`world_management_service.proto`](../../../../protos/world-management/v1/world_management_service.proto).
- `GetRoom(GetRoomRequest) returns (GetRoomResponse)` – fetches a room's JSON representation.
- `UpdateWorldState(UpdateWorldStateRequest) returns (UpdateWorldStateResponse)` – applies pending world updates and notifies other services.

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

### World Events

World events are persisted in the `world_event` table and processed periodically by `WorldEventService`. A weather change event updates the `region.weather` column before notifying other services.

### Saga Participation

World creation for a new tenant runs as a Saga using the helper utilities from `firemud-common`. Each step is described in [world-creation-workflow.md](world-creation-workflow.md) and can be rolled back if a later step fails. This ensures worlds are created consistently even when the workflow spans multiple services.

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
