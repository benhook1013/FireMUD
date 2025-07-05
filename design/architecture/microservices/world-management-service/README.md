# World Management Service

## Overview

The World Management Service stores and manages game world data such as rooms, regions, and maps. It persists world state beyond player sessions and handles scheduled world events, notifying other services over gRPC when the environment changes.

## Architecture / Design Notes

- World data is stored in PostgreSQL. Redis holds only transient active state used during gameplay.
- Changes are persisted incrementally to avoid heavy writes.
- Background tasks trigger scheduled world changes (daily resets or seasonal shifts) and notify relevant services via gRPC.
- Supports procedural generation with options for dynamic world expansion.
- Uses a region → zone → room hierarchy for efficient lookups.
- Publishes world event notifications for NPC scripts and game logic processing.

## Key Features

- Region and location management with shard support.
- Persistent world state with incremental saves.
- Procedural generation tools for rooms and terrain.
- Pathfinding algorithms and navmesh data for movement calculations.
- Event scheduling for world-wide holidays or timed modifiers, communicating changes over gRPC.
- Chunk-based world snapshots for backup and recovery.

### Data Model

- Tables for `region`, `zone`, and `room` define the world hierarchy.
- `terrain` and `object_spawn` tables support procedural generation.
- Redis caches hot rooms for active sessions to speed up lookups.

### gRPC APIs

- `GetRoom` – retrieves room data including exits and environmental effects.
- `UpdateWorldState` – persists scheduled changes and notifies listeners.

## Dependencies

- **Internal:** Game Design Service for generation rules.
- **External:** PostgreSQL for world data, Redis for transient active state.

> See [**Gateway Architecture**](../../infrastructure/gateway-architecture.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../infrastructure/protocol-bridging.md) for
details on shared infrastructure components.

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

## Future Enhancements

- Tools for fine-tuning procedural generation rules.
- Support for multi-server world shards.
