# World Management Service

## Overview

World Management stores and manages game world topology and content such as rooms, regions, and maps. It persists world state beyond player sessions, handles scheduled world events, and notifies other services over gRPC when the environment changes.

Live entities, items, and inventories are always owned by Entity Management. World Management never stores room inventory or item instances.

This doc set is the authoritative source for:

- version-scoped world-template ownership and runtime instance ownership;
- canonical room/location identifiers and `RoomTemplateRef` / `RoomInstanceRef` usage;
- World Management's runtime data, cache, and read-fence contracts;
- world-facing gRPC, REST, and design-time digest interfaces; and
- procedural-generation control surfaces and ownership boundaries.

## Responsibilities

- Persist region, zone, and room data with tenant isolation.
- Execute scheduled world events.
- Provide procedural-generation support.
- Expose geometry and region metadata. Pathfinding is handled by the Movement/Travel subsystem in Game Logic, while World Management stores and publishes versioned navmesh/path graph artifacts.
- Notify Game Session and Automation services when the world changes.
- Track character locations and instance occupancy.
- Avoid storing or managing live item or inventory state. Room inventory and ground items are derived from Entity Management queries scoped by room and instance identifiers.

## Identifier Model

World Management distinguishes these primary identifiers:

- `tenantId` – identifies the game tenant.
- `versionId` – identifies a published world/template configuration.
- `gameInstanceId` – identifies a running game instance managed by Game Session.

World data uses two distinct identifier families:

- Template identifiers:
  - `regionTemplateId`, `zoneTemplateId`, `roomTemplateId`
  - `RoomTemplateRef = (tenantId, versionId, roomTemplateId)`
- Runtime instance identifiers:
  - `regionInstanceId`, `zoneInstanceId`, `roomInstanceId`
  - `RoomInstanceRef = (tenantId, gameInstanceId, roomInstanceId)`

Template/topology data is keyed by `(tenantId, versionId)`, while runtime world instances are keyed by `(tenantId, gameInstanceId)` with references back to the active `versionId`.

See [Identifier Glossary](../../system-architecture-identifier-glossary.md) for naming and scoping rules.

## Documentation Map

- [`api-contracts.md`](./api-contracts.md)
  - gRPC and REST endpoints, design-time digest APIs, LOOK/read-fence contract, lifecycle APIs, and cross-service workflow contracts.
- [`runtime-and-data.md`](./runtime-and-data.md)
  - template/runtime data ownership, location ownership, world events, cache usage, instance classification, and runtime invariants.
- [`operations.md`](./operations.md)
  - readiness/liveness, deployment, operational notes, and runtime cleanup behavior.
- [`configuration.md`](./configuration.md)
  - environment variables, secrets, service discovery, and local override notes.
- [`procedural-generation-control.md`](./procedural-generation-control.md)
  - generation-input ownership, runtime-default APIs, artifact publication path, and draft-digest implications.
- [`world-creation-workflow.md`](./world-creation-workflow.md)
  - per-instance world-lifecycle workflow stages and rollback boundaries.

## Quick Canonical Links

- [`api-contracts.md#grpc-apis`](./api-contracts.md#grpc-apis)
- [`api-contracts.md#look-snapshot-contract`](./api-contracts.md#look-snapshot-contract)
- [`runtime-and-data.md#redis-role-and-cache-usage`](./runtime-and-data.md#redis-role-and-cache-usage)
- [`runtime-and-data.md#character-location-ownership`](./runtime-and-data.md#character-location-ownership)
- [`procedural-generation-control.md#procedural-generation-control-apis`](./procedural-generation-control.md#procedural-generation-control-apis)
- [`world-creation-workflow.md`](./world-creation-workflow.md)
- [`configuration.md#environment-variables`](./configuration.md#environment-variables)

## Dependencies

- **Internal:**
  - Game Design Service supplies versioned world design inputs and orchestrates Draft template writes and publish flows.
  - Game Session queries room/world state and receives world event updates.
  - Automation & Scripting reacts to scheduled world changes.
- **External:** PostgreSQL for world data and Redis for transient active-state caches.

> See [Gateway Architecture](../../system-architecture-gateway.md), [Deployment Environments](../../infrastructure/deployment-environments.md), and [Protocol Bridging](../../system-architecture-protocol-bridging.md) for shared infrastructure context.

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
- [System Architecture Diagram](../../system-architecture-diagram.md)
- [System Context Diagram](../../system-context-diagram.md)
