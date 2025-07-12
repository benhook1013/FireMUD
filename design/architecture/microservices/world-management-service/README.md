# World Management Service

## Overview

The World Management Service stores and manages game world data such as rooms, regions, and maps. It persists world state beyond player sessions and handles scheduled world events, notifying other services over gRPC when the environment changes.

### Responsibilities

- Persist region, zone, and room data with tenant isolation
- Execute scheduled world events and procedural generation
- Provide pathfinding and navmesh information
- Notify Game Session and Automation services when the world changes
- Track character locations and instance occupancy

## Architecture / Design Notes

- World data is stored in PostgreSQL. Redis holds only transient active state used during gameplay.
- Changes are persisted incrementally to avoid heavy writes.
- Background tasks trigger scheduled world changes (daily resets or seasonal shifts) and notify relevant services via gRPC.
- Supports procedural generation with options for dynamic world expansion.
- Uses a region → zone → room hierarchy for efficient lookups.
- Publishes world event notifications for NPC scripts and game logic processing.
- During version publishing the service participates in a Saga that copies design
  data into its schema, ensuring world data matches the active version. See
  [Versioning & Runtime Configuration](../system-architecture-versioning-runtime.md)
  and [Transaction Strategies](../system-architecture-transactions.md).
- World creation for new games also runs as a Saga, outlined in
  [World Creation Workflow](world-creation-workflow.md).
- All world tables are keyed by `tenantId`; background jobs and gRPC queries
  include this filter so one game's world data never mixes with another's. See
  [Multi-Tenancy](../system-architecture-multi-tenancy.md).
- Gameplay gRPC operations do not validate JWTs directly. The Game Session
  Service binds player identity from Redis into `SessionContext`. It may request
  an updated JWT from the Account Service when roles change but does not perform
  token validation during gameplay. All traffic still uses mutual TLS as described in the
  [Security Architecture](../system-architecture-security.md).
- Utilizes the [Shared Libraries](../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.

## Key Features

- Region and location management with shard support. Each region stores a
  `shard_id` value so the world can span multiple servers.
- Instance-based zones are treated as regular rooms; this service records which
  characters occupy each instance so private dungeons or housing do not affect
  the shared world map.
- Persistent world state with incremental saves.
- Procedural generation tools for rooms and terrain.
- `TravelService` implements Dijkstra-based pathfinding using the `room_exit` table.
- Event scheduling for world-wide holidays or timed modifiers, communicating changes over gRPC. A `world_event` table stores pending events and a scheduled task processes them, updating regional weather or other state before notifying listeners.
- Chunk-based world snapshots for backup and recovery.

### Data Model

- Tables for `region`, `zone`, and `room` define the world hierarchy.
- `terrain` and `object_spawn` tables support procedural generation.
- `instance` table tracks temporary copies of zones for instanced gameplay.
- `expires_at` column defines when instances are cleaned up by a scheduled job.
- `character_location` table records the current room for each character,
  including which instance they are in.
- `world_event` table stores timed changes such as weather updates.
- `region.weather` column records the current weather state.
- `region.shard_id` indicates which server shard hosts the region.
- Redis caches hot rooms for active sessions to speed up lookups.
- Cached rooms use keys `room:{tenantId}:{roomId}` and expire after `world.room.cache-ttl-seconds`.

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
- `UpdateWorldState` – persists scheduled changes and notifies listeners.

## Dependencies

- **Internal:**
  - Game Design Service supplies generation rules and versioned world data.
  - Game Session Service queries rooms and receives world event updates.
  - Automation & Scripting Service reacts to scheduled world changes.
- **External:** PostgreSQL for world data, Redis for transient active state.

> See [**Gateway Architecture**](../system-architecture-gateway.md),
[**Deployment Environments**](../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../system-architecture-protocol-bridging.md) for
details on shared infrastructure components.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health` probes. See [Deployment Environments](../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.

## Environment Variables

World Management Service uses the configuration scheme defined in
[Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md).
It depends on the [PostgreSQL credentials](../../infrastructure/environment-and-secrets.md#postgresql-credentials)
and [Redis connection](../../infrastructure/environment-and-secrets.md#redis-connection).

See [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md)
for TLS variables `FIREMUD_GRPC_CERT_CHAIN`, `FIREMUD_GRPC_PRIVATE_KEY`, `FIREMUD_GRPC_CA_CERT`
and the `FIREMUD_SERVICES_*` service discovery settings.

## Proto Files

The gRPC contract for world operations is located in
[../../../../protos/world-management/v1](../../../../protos/world-management/v1).
Run `./gradlew generateProto` to regenerate sources after editing these files.

## 📚 Related Documentation

- [System Architecture Overview](../system-architecture-overview.md)
- [Versioning & Runtime Configuration](../system-architecture-versioning-runtime.md)
- [Tick System and Runtime Design](../system-architecture-ticks.md)
- [Redis Architecture](../system-architecture-redis.md)
- [Multi-Tenancy](../system-architecture-multi-tenancy.md)
- [Service Responsibility Matrix](../service-responsibility-matrix.md)
- [User Journeys – World and Entity Design](../user-journeys.md#3-world-and-entity-design)
- [gRPC API Style & Versioning Guidelines](../system-architecture-grpc.md)
- [Shared Libraries Overview](../system-architecture-shared-libraries.md)
- [Database Migrations](../system-architecture-database-migrations.md)
- [Backup & Disaster Recovery](../system-architecture-backup-recovery.md)
- [Logging & Monitoring](../system-architecture-logging-monitoring.md)
- [Authentication & Authorization](../system-architecture-authentication.md)
- [Security Architecture](../system-architecture-security.md)
- [Testing Strategy](../system-architecture-testing.md)
- [CI/CD Pipeline](../system-architecture-cicd.md)

## Additional Details

The service creates temporary **instances** of zones for dungeons or housing. Instances expire automatically based on the `world.instance.expiration-hours` property.

### REST & gRPC Endpoints

#### REST

- `GET /ping` – basic health check returning `"pong"`.

The service exposes an OpenAPI specification under `/v3/api-docs` with a Swagger UI at `/swagger-ui.html` when running locally.

```bash
curl http://localhost:8080/ping
```

Requests to this service come from other internal services. Player identity is
established by the Game Session Service, so no JWT header is required here. See
the [Security Architecture](../system-architecture-security.md) for details.

#### gRPC

- `Ping(PingRequest) returns (PingResponse)` – basic connectivity check defined in [`world_management_service.proto`](../../../protos/world-management/v1/world_management_service.proto).
- `GetRoom(GetRoomRequest) returns (GetRoomResponse)` – fetches a room's JSON representation.
- `UpdateWorldState(UpdateWorldStateRequest) returns (UpdateWorldStateResponse)` – applies pending world updates.

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

- [System Architecture Diagram](../system-architecture-diagram.md)
- [System Context Diagram](../system-context-diagram.md)

## Procedural Generation Rules API

Administrators can tweak procedural generation without redeploying the service.
Rules are stored in the `generation_rule` table and managed over REST:

- `POST /generation/rules` – create or update a rule for a tenant
- `GET /generation/rules?tenantId=...` – list rules for a tenant

These endpoints allow live tuning of parameters such as room density or terrain
variation. Updates are persisted immediately and picked up by the procedural
generation engine on the next run.

## Future Enhancements

- Additional shard balancing strategies.
