# Entity Management Service

## Overview

Handles player characters, NPCs, items, and inventory. Provides CRUD operations for entities and exposes them to other services.

### Responsibilities

- Persist characters, NPCs, and items with optimistic locking
- Provide CRUD and query APIs for other services
- Manage inventories and instanced zones
- Coordinate deferred writes through Game Session Service

## Architecture / Design Notes

- Uses JPA for persistence of entity data.
- Exposes gRPC endpoints for other microservices.
- Caches frequently accessed character data in Redis for quick lookups.
- Applies **optimistic locking** to avoid conflicting updates on the same entity.
- **Database writes are deferred and batched**, not triggered on every gameplay action. The Game Session Service coordinates real-time updates using Redis; the database is only updated during safe persistence boundaries (e.g. logout, autosave).
- This design reduces write frequency and contention, making optimistic locking a natural fit — most entities are updated by only one process at a time, and conflicts are rare.
- Item transfers and other gameplay actions span services but execute within ticks
  using Redis scripts for rollback. Sagas are reserved for non-gameplay
  workflows. See [Transaction Strategies](../system-architecture-transactions.md).
- All entity tables include a `tenantId` column. Service methods always filter on
  this value so character data for different games remains isolated; Redis keys
  mirror this prefix. Details are in the [Multi-Tenancy](../system-architecture-multi-tenancy.md)
  document.
- gRPC endpoints are secured with JWT tokens validated via the Account Service's
  JWKS endpoint, and all traffic between services uses mutual TLS certificates as
  outlined in the [Security Architecture](../system-architecture-security.md).

- Utilizes the [Shared Libraries](../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.

## Key Features

- Character and NPC management.
- Item storage and inventory handling.
- Experience and level tracking.
- Character creation templates pulled from the Game Design Service.
- Supports instance-based spaces so characters can enter private dungeons or
  personalized housing without affecting the main world state.

### Data Model

- `character` and `npc` tables share a base entity for stats and inventory slots.
- `item` table stores equipment, consumables, and quest objects.
- Many-to-many tables define inventory and equipment relationships.
- `instance_member` table tracks which characters are present in optional
  instance-based spaces.

### gRPC APIs

- `CreateCharacter` – builds a new player character from a template.
- `UpdateEntity` – updates stats or equipment for a character or NPC.
- `QueryInventory` – lists items for an entity with pagination.

## Dependencies

- **Internal:**
  - Game Design Service supplies character templates and item definitions.
  - Game Session Service coordinates runtime updates via Redis queues.
- **External:** PostgreSQL for entity data.

> See [**Gateway Architecture**](../../infrastructure/gateway-architecture.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../infrastructure/protocol-bridging.md) for
details on shared infrastructure components.

## Operational Notes

- Runs as a scalable Deployment in Kubernetes, exposing `/actuator/health` for
  readiness and liveness checks.
- Prometheus scrapes service metrics while Fluent Bit ships logs to
  Elasticsearch; tracing integrates with OpenTelemetry.
- Local Docker Compose uses the same Spring profiles to mimic production, as
  documented in
  [Deployment Environments](../../infrastructure/deployment-environments.md).

## Proto Files

Service interface definitions are stored in
[../../../../protos/entity-management/v1](../../../../protos/entity-management/v1). After editing the
proto files, run `./gradlew generateProto` to update generated sources.

## 📚 Related Documentation

- [System Architecture Overview](../system-architecture-overview.md)
- [Tick System and Runtime Design](../system-architecture-ticks.md)
- [Redis Architecture](../system-architecture-redis.md)
- [Multi-Tenancy](../system-architecture-multi-tenancy.md)
- [Service Responsibility Matrix](../service-responsibility-matrix.md)
- [User Journeys – World and Entity Design](../user-journeys.md#2-world-and-entity-design)
- [gRPC API Style & Versioning Guidelines](../system-architecture-grpc.md)
- [Shared Libraries Overview](../system-architecture-shared-libraries.md)
- [Database Migrations](../system-architecture-database-migrations.md)
- [Backup & Disaster Recovery](../system-architecture-backup-recovery.md)
- [Logging & Monitoring](../system-architecture-logging-monitoring.md)
- [Authentication & Authorization](../system-architecture-authentication.md)
- [Security Architecture](../system-architecture-security.md)
- [Testing Strategy](../system-architecture-testing.md)
- [CI/CD Pipeline](../system-architecture-cicd.md)

- [System Architecture Diagram](../system-architecture-diagram.md)
- [System Context Diagram](../system-context-diagram.md)

## Future Enhancements

- Entity graph caching for faster lookups.
- Support for complex crafting recipes.
