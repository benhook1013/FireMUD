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

## Key Features

- Character and NPC management
- Item storage and inventory handling
- Experience and level tracking
- NPC respawn scheduling with configurable delays
- Character creation templates pulled from the Game Design Service
- Supports instance-based spaces in conjunction with the World Management Service so characters can enter private dungeons or personalized housing without affecting the shared world state
- Crafting recipe management with validation
- Cross-game character listing via account linkage

## Document Map

- [API Contracts](./api-contracts.md)
  - CRUD/query surfaces, gameplay-facing entity contracts, and proto/OpenAPI ownership.
- [Runtime and Data](./runtime-and-data.md)
  - PostgreSQL ownership, containment/inventory rules, and runtime invariants shared with World and Game Session.
- [Operations](./operations.md)
  - readiness/liveness, operational notes, and local verification guidance.
- [Configuration](./configuration.md)
  - environment variables, service discovery, TLS, and configuration source locations.

## Dependencies

- **Internal:**
  - Game Design Service supplies character templates and item definitions
  - Game Session Service coordinates runtime updates via Redis queues
- **External:** PostgreSQL for entity data

See [Gateway Architecture](../../system-architecture-gateway.md), [Deployment Environments](../../infrastructure/deployment-environments.md), and [Protocol Bridging](../../system-architecture-protocol-bridging.md) for details on shared infrastructure components.

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
- [System Architecture Diagram](../../system-architecture-diagram.md)
- [System Context Diagram](../../system-context-diagram.md)
