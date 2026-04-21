# Entity Management Service

## Overview

Handles player characters, NPCs, items, and all inventory/containment. Provides CRUD operations for entities and exposes them to other services. This includes player inventories and equipment, container contents (chests, corpses, banks, bags), and items on the ground in rooms (room/ground inventory) modeled as items inside dedicated room-ground container entities keyed by `(tenantId, gameInstanceId, roomInstanceId)` (a `RoomInstanceRef`) rather than being stored in the World Management Service.

The target-state model is container-first:

- character and NPC inventories are hidden containers owned by that runtime entity;
- room-ground inventory is a room-attached container persisted by Entity Management but identified from the authoritative World room instance;
- equipment is not just another bag position and instead uses first-class equipment bindings;
- slot definitions and body layouts are game-configured rather than hardcoded to a fixed humanoid equipment schema;
- future inventory queries must support structural filtering plus game-defined item types/tags for both gameplay commands and richer GUIs;
- `LOOK` and similar room-view commands should expose visible room-ground items from that room-attached container as a distinct room-view section, but should not automatically expand nested container contents inline.

Item definitions now also expose explicit authored stackability controls. Non-stackable remains the safe default for equipment, containers, and other stateful items. Stackable definitions merge through holder-local stack records keyed by the authored compatibility mode and runtime stack family, so fungible quantities can merge without collapsing ordinary physical item instances.

Inventory and equipment mutations are also intended to be auditable through a canonical transfer log so item duplication or invalid movement bugs can be investigated later.

Character ownership is tenant-scoped, but Entity Management must support both tenant-shared and instance-local playable state depending on the resolved realm policy. In practice this means a character may remain owned by the same `{accountId, tenantId}` while some associated gameplay state, such as copied fork-local progression, seeded/sample-state inventory, or fresh standalone realm-local records, is isolated to a specific `gameInstanceId`.

Character discovery/creation contract consequence:

- For a resolved `{tenantId, gameInstanceId}` target, Entity Management must surface one realm-local roster plus explicit creation policy to admission/discovery callers.
- It must not require callers to infer whether fresh creation is allowed by comparing isolated-state rows against the tenant's shared roster.

### Responsibilities

- Persist characters, NPCs, and items with optimistic locking
- Provide CRUD and query APIs for other services
- Own and manage all inventories and item containment; character location and instance metadata live in the World Management Service
- Own room-attached ground containers, hidden inventory containers, equipment bindings, and the audit trail for item/container movement
- Coordinate deferred writes through Game Session Service

World Management is therefore the sole owner of authoritative character and NPC location tables (`character_location`, `npc_location`) for each game instance. Entity Management reads location via World Management gRPC APIs or shared projections but must not persist its own competing location fields or treat cached location as authoritative.

For canonical naming and scoping rules, see [Identifier Glossary](../../system-architecture-identifier-glossary.md).

## Key Features

- Character and NPC management
- Item storage and inventory handling
- Experience and level tracking
- NPC respawn scheduling with configurable delays
- Character creation templates pulled from the Game Design Service
- Support for both tenant-shared character state and isolated realm-local character state, depending on the resolved realm/runtime contract
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
