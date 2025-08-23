# Entity Management Service

## Overview

Handles player characters, NPCs, items, and inventory. Provides CRUD operations for entities and exposes them to other services.

### Responsibilities

- Persist characters, NPCs, and items with optimistic locking
- Provide CRUD and query APIs for other services
- Manage inventories; location and instance data live in the World Management Service
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
- Many-to-many tables define inventory and equipment relationships.
- Character location and instance membership are stored by the World Management
  Service rather than this service.
- Entity graphs cache inventory relationships for fast lookups.

### gRPC APIs

- `CreateCharacter` – builds a new player character from a template.
- `UpdateEntity` – updates stats or equipment for a character or NPC.
- `QueryInventory` – lists items for an entity with pagination.
- `ListCharactersByAccount` – returns all characters owned by an account across tenants.

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

## 📚 Related Documentation

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

This service participates in tick processing by acquiring Redis locks before mutating entity state. The `TickLockService` uses the `tick:lock:{tenantId}:{entityId}` key described in the [Redis Architecture](../../../design/architecture/system-architecture-redis.md) document. Locks expire after `game.tick-duration-ms` (default 1000 ms) to ensure stalled ticks can be retried.

- [System Architecture Diagram](../../system-architecture-diagram.md)
- [System Context Diagram](../../system-context-diagram.md)
