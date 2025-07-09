# Game Design Service

## Overview

Offers tools for building worlds, items, actions, and events that make up each game. Used by creators to design content without touching the underlying code. It also maintains versioned game configurations and templates so new game instances can be created with predefined rules and administrators.

### Responsibilities

- Provide web and gRPC tools for editing game assets
- Version and publish immutable game configurations
- Track revision history for rollback
- Notify downstream services when new versions are available

## Architecture / Design Notes

- Provides REST/gRPC APIs for editing game data.
- Works closely with World Management and Automation & Scripting Service to apply changes.
- Stores versioned configuration data so new game instances can be generated from templates.
- Maintains history of revisions so designers can roll back to prior versions.
- Publishing a new game version triggers a Saga that copies data to other
  services as outlined in
  [Versioning & Runtime Configuration](../system-architecture-versioning-runtime.md)
  and [Transaction Strategies](../system-architecture-transactions.md).
- Design assets are stored per `tenantId` so multiple games can coexist in the
  same database schema. Queries and version publishing workflows enforce this
  tenant filter. See [Multi-Tenancy](../system-architecture-multi-tenancy.md).
- All APIs require JWT authentication and are validated using the Account
  Service's JWKS endpoint. Service-to-service traffic is protected with mutual
  TLS certificates managed by cert-manager as described in the
  [Security Architecture](../system-architecture-security.md).
- Utilizes the [Shared Libraries](../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.

## Key Features

- World and room editors.
- Ability and action design tools.
- Scripting and event workflow creation.
- Visual editor for building scripts in the same component-based DSL used by the
  Automation & Scripting Service.
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
- [User Journeys – Sign Up and Game Creation](../user-journeys.md#1-sign-up-and-game-creation)
- [User Journeys – World and Entity Design](../user-journeys.md#2-world-and-entity-design)
- [User Journeys – Publish and Start a Game Instance](../user-journeys.md#4-publish-and-start-a-game-instance)
- [User Journeys – Patch and Update a Live Game](../user-journeys.md#8-patch-and-update-a-live-game)
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

- Web-based visual design interface.
- Version control integration for design assets.
