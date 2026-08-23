# Entity Management Service

## Overview

Handles player characters, NPCs, runtime actor identity, items, and all inventory/containment. Provides CRUD operations for entities and exposes them to other services. This includes player inventories and equipment, container contents (chests, corpses, banks, bags), and items on the ground in rooms (room/ground inventory) modeled as items inside dedicated room-ground container entities keyed by `(tenantId, gameInstanceId, roomInstanceId)` (a `RoomInstanceRef`) rather than being stored in the World Management Service.

## Implementation Status

Persisted rows and realm-aware listing exist, but synthetic-ID paths, fixed RPG columns, published entry-policy/descriptor/template resolution, policy-specific roster handling, namespace-idempotent auto-provision, and fork-local copy proof remain gaps.

The target-state model is container-first:

- character and NPC inventories are hidden containers owned by that runtime entity;
- room-ground inventory is a room-attached container persisted by Entity Management but identified from the authoritative World room instance;
- equipment is not just another bag position and instead uses first-class equipment bindings;
- slot definitions and body layouts are game-configured rather than hardcoded to a fixed humanoid equipment schema;
- future inventory queries must support structural filtering plus game-defined item types/tags for both gameplay commands and richer GUIs;
- `LOOK` and similar room-view commands should expose visible room-ground items from that room-attached container as a distinct room-view section, but should not automatically expand nested container contents inline.

Item definitions now also expose explicit authored stackability controls. Non-stackable remains the safe default for equipment, containers, and other stateful items. Stackable definitions merge through holder-local stack records keyed by the authored compatibility mode and runtime stack family, so fungible quantities can merge without collapsing ordinary physical item instances.

The [ADR 0127](../../decisions/adr-0127-game-authored-equipment-layouts-with-fail-closed-publication.md) target uses game-authored equipment schema data rather than a platform-global slot enum: publication and runtime validation fail closed when required schema or layout data is missing or unknown, and binding validates slot existence, item slot-group compatibility, body-layout membership, and occupancy before changing equipment. The current implementation is partial: it stores versioned slot definitions, optional slot-group compatibility keys, body-layout slot membership, and each character's `bodyLayoutKey`, but missing-schema and unknown-layout paths still retain permissive bootstrap fallback behavior that is not part of the target contract. At target, version cutover validates surviving equipped state against the exact target schema and blocks unresolved renamed, removed, split, merged, or newly incompatible definitions pending explicit mapping or resolution.

Inventory and equipment mutations are also intended to be auditable through a canonical transfer log so item duplication or invalid movement bugs can be investigated later.

### PLAYER-01 actor and realm-entry ownership

[ADR 0140](../../decisions/adr-0140-realm-authored-controllable-actor-entry.md) is the canonical actor-entry contract; the detailed allocation, persistence, policy, descriptor/template, and copy rules live in [API Contracts](./api-contracts.md) and [Runtime and Data](./runtime-and-data.md). Entity Management owns persisted `characterId` and the `{accountId, tenantId, playableStateNamespaceId, characterId}` association. Account owns identity, membership, grants, and profiles; Game Session owns active attachment/controller fencing. The service must reject synthetic identity and retain game-authored actor components, while playtest copies receive fork-local identity and, when `sourceCharacterId` is retained, its immutable `{sourceTenantId, sourcePlayableStateNamespaceId}` binding as provenance-only non-authoritative context.

### Responsibilities

- Persist characters, NPCs, and items with optimistic locking
- Own the persisted runtime actor core that unifies player and NPC gameplay identity; World Management remains authoritative for location and occupancy
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
- **Target state:** Realm-authored, versioned actor creation descriptors and auto-provision templates supplied by the Game Design/realm catalog contract
- **Target state:** Support for stable tenant-shared and isolated/playtest namespaces, depending on the resolved realm policy
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
- [User Journeys – World and Entity Design](../../../product/user-journeys/creators.md#2-world-and-entity-design)
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
