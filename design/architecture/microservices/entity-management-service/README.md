# Entity Management Service

## Overview

Handles player characters, NPCs, items, and inventory. Provides CRUD operations for entities and exposes them to other services.

## Architecture / Design Notes

- Uses JPA for persistence of entity data.
- Exposes gRPC endpoints for other microservices.
- Caches frequently accessed character data in Redis for quick lookups.
- Applies **optimistic locking** to avoid conflicting updates on the same entity.
- **Database writes are deferred and batched**, not triggered on every gameplay action. The Game Session Service coordinates real-time updates using Redis; the database is only updated during safe persistence boundaries (e.g. logout, autosave).
- This design reduces write frequency and contention, making optimistic locking a natural fit — most entities are updated by only one process at a time, and conflicts are rare.
- Cross-service operations such as item transfers use Saga orchestration so that
  partial failures can be rolled back. See [Transaction Strategies](../system-architecture-transactions.md).

## Key Features

- Character and NPC management.
- Item storage and inventory handling.
- Experience and level tracking.
- Character creation templates pulled from the Game Design Service.

### Data Model

- `character` and `npc` tables share a base entity for stats and inventory slots.
- `item` table stores equipment, consumables, and quest objects.
- Many-to-many tables define inventory and equipment relationships.

### gRPC APIs

- `CreateCharacter` – builds a new player character from a template.
- `UpdateEntity` – updates stats or equipment for a character or NPC.
- `QueryInventory` – lists items for an entity with pagination.

## Dependencies

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
- [gRPC API Style & Versioning Guidelines](../system-architecture-grpc.md)
- [Shared Libraries Overview](../system-architecture-shared-libraries.md)
- [Database Migrations](../system-architecture-database-migrations.md)
- [Backup & Disaster Recovery](../system-architecture-backup-recovery.md)
- [Logging & Monitoring](../system-architecture-logging-monitoring.md)
- [Testing Strategy](../system-architecture-testing.md)
- [CI/CD Pipeline](../system-architecture-cicd.md)

## Future Enhancements

- Entity graph caching for faster lookups.
- Support for complex crafting recipes.
