# Game Design Service

## Overview

Offers tools for building worlds, items, actions, and events that make up each game. Used by creators to design content without touching the underlying code. It also maintains versioned game configurations and templates so new game instances can be created with predefined rules and administrators.

## Architecture / Design Notes

- Provides REST/gRPC APIs for editing game data.
- Works closely with World Management and Automation & Scripting Service to apply changes.
- Stores versioned configuration data so new game instances can be generated from templates.
- Maintains history of revisions so designers can roll back to prior versions.
- Publishing a new game version triggers a Saga that copies data to other
  services as outlined in
  [Versioning & Runtime Configuration](../system-architecture-versioning-runtime.md).
- Design assets are stored per `tenantId` so multiple games can coexist in the
  same database schema. Queries and version publishing workflows enforce this
  tenant filter. See [Multi-Tenancy](../system-architecture-multi-tenancy.md).

## Key Features

- World and room editors.
- Ability and action design tools.
- Scripting and event workflow creation.
- Game templates with predefined rulesets and administrators.
- Version and patch note management for published games.
- Import/export of design assets for sharing between game worlds.

### Data Model

- `game` table defines the project and its owner account.
- `revision` table stores individual asset changes with author metadata.
- `version` table groups revisions into immutable snapshots for publishing.
- `runtime_flag` table holds feature flag definitions copied to the Game Session Service.

### Design Workflow

1. Creators use the web UI to craft worlds, items, and scripts.
2. Changes are staged as revisions with metadata and author information.
3. Revisions are grouped into versions that can be published to runtime.

### gRPC APIs

- `SaveRevision` – persists a new or updated design asset.
- `PublishVersion` – freezes a set of revisions and notifies downstream services.
- `ListVersions` – enumerates published versions for selection when creating a
  game instance.

## Dependencies

- **Internal:**
  - World Management Service for map data.
  - Automation & Scripting Service for scripts.
  - Logging & Admin Service records publishing audits.
- **External:** PostgreSQL for storing design assets.

> See [**Gateway Architecture**](../../infrastructure/gateway-architecture.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../infrastructure/protocol-bridging.md) for
details on shared infrastructure components.

## Operational Notes

- Runs as a Kubernetes Deployment with optional horizontal scaling controlled by
  HPA.
- Health and readiness are exposed via `/actuator/health` and monitored by the
  cluster.
- Metrics and traces integrate with Prometheus and OpenTelemetry, while logs are
  forwarded through Fluent Bit to Elasticsearch.
- Local Docker Compose uses the same Spring profiles; see
  [Deployment Environments](../../infrastructure/deployment-environments.md) for
  details.

## Proto Files

The service API contract resides in
[../../../../protos/game-design/v1](../../../../protos/game-design/v1). Generate
stubs with `./gradlew generateProto` whenever these files are updated.

## 📚 Related Documentation

See [Versioning & Runtime Configuration](../system-architecture-versioning-runtime.md) for how published versions are promoted to runtime.
- [Multi-Tenancy](../system-architecture-multi-tenancy.md)
- [System Architecture Overview](../system-architecture-overview.md)
- [Service Responsibility Matrix](../service-responsibility-matrix.md)
- [gRPC API Style & Versioning Guidelines](../system-architecture-grpc.md)
- [Shared Libraries Overview](../system-architecture-shared-libraries.md)
- [Database Migrations](../system-architecture-database-migrations.md)
- [Backup & Disaster Recovery](../system-architecture-backup-recovery.md)
- [Logging & Monitoring](../system-architecture-logging-monitoring.md)
- [Testing Strategy](../system-architecture-testing.md)
- [CI/CD Pipeline](../system-architecture-cicd.md)

## Future Enhancements

- Web-based visual design interface.
- Version control integration for design assets.
