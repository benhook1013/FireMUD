# World Management Service

## Overview

The World Management Service stores and manages game world data such as rooms, regions, and maps. It persists world state beyond player sessions and handles scheduled world events, notifying other services over gRPC when the environment changes.

### Responsibilities

- Persist region, zone, and room data with tenant isolation
- Execute scheduled world events and procedural generation
- Provide pathfinding and navmesh information
- Notify Game Session and Automation services when the world changes

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
- All world tables are keyed by `tenantId`; background jobs and gRPC queries
  include this filter so one game's world data never mixes with another's. See
  [Multi-Tenancy](../system-architecture-multi-tenancy.md).
- All gRPC operations require JWT authentication validated via the Account
  Service's JWKS endpoint and use mutual TLS between services, per the
  [Security Architecture](../system-architecture-security.md).
- Utilizes the [Shared Libraries](../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.

## Key Features

- Region and location management with shard support.
- Instance-based zones allow temporary dungeons or housing separate from the
  shared world map.
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
- `world_event` table stores timed changes such as weather updates.
- `region.weather` column records the current weather state.
- Redis caches hot rooms for active sessions to speed up lookups.

### gRPC APIs

- `GetRoom` – retrieves room data including exits and environmental effects.
- `UpdateWorldState` – persists scheduled changes and notifies listeners.

## Dependencies

- **Internal:**
  - Game Design Service supplies generation rules and versioned world data.
  - Game Session Service queries rooms and receives world event updates.
  - Automation & Scripting Service reacts to scheduled world changes.
- **External:** PostgreSQL for world data, Redis for transient active state.

> See [**Gateway Architecture**](../../infrastructure/gateway-architecture.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../infrastructure/protocol-bridging.md) for
details on shared infrastructure components.

## Operational Notes

- Runs as a Kubernetes Deployment with horizontal scaling to serve large world
  datasets.
- Health endpoints (`/actuator/health`) are used for readiness and liveness
  checks.
- Metrics and traces are collected by Prometheus and OpenTelemetry, and logs are
  shipped via Fluent Bit to Elasticsearch.
- Environment-specific configuration values are described in
  [Deployment Environments](../../infrastructure/deployment-environments.md).

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

- Tools for fine-tuning procedural generation rules.
- Support for multi-server world shards.
