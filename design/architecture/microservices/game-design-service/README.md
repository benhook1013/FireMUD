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
  The workflow is implemented using the Saga utilities from `firemud-common`
  with compensation steps to roll back if downstream copies fail.
- Design assets are stored per `tenantId` so multiple games can coexist in the
  same database schema. Queries and version publishing workflows enforce this
  tenant filter. See [Multi-Tenancy](../system-architecture-multi-tenancy.md).
- All APIs require JWT authentication between services. Tokens are parsed by a
  shared `AuthTokenInterceptor` which stores claims in `SessionContext` for role
  checks. Service-to-service traffic uses mutual TLS certificates managed by cert-manager
  as described in the [Security Architecture](../system-architecture-security.md).
- Utilizes the [Shared Libraries](../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.

## Key Features

- World and room editors.
- [Ability & Action Design Tools](ability-action-tools.md)
- Scripting and event workflow creation.
- Visual editor for building scripts in the same component-based DSL used by the
  Automation & Scripting Service.
- [Game templates](game-templates.md) with predefined rulesets and administrators.
- Version and patch note management for published games.
- Supports script-only patch versions that reference a `baseVersionId` and
  generate a new `scriptPatchVersion` without requiring a full publish.
- [Item & Equipment Balancing Tools](item-equipment-balancing.md)
- Import/export of design assets for sharing between game worlds.

### Data Model

- `game` table defines the project and its owner account.
- `revision` table stores individual asset changes with author metadata.
- `version` table groups revisions into immutable snapshots for publishing.
- `runtime_flag` table holds feature flag definitions copied to the Game Session Service.
- `game_assets` table stores uploaded binary files such as icons or sound effects.

### Design Workflow

1. Creators use the web UI to craft worlds, items, and scripts.
2. Changes are staged as revisions with metadata and author information.
3. Revisions are grouped into versions that can be published to runtime.
4. For quick fixes, designers create a script-only patch version which records a
   `scriptPatchVersion` linked to an existing `baseVersionId` and notifies
   runtime services to reload the modified scripts.

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

> See [**Gateway Architecture**](../system-architecture-gateway.md),
[**Deployment Environments**](../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../system-architecture-protocol-bridging.md) for
details on shared infrastructure components.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health` probes. See [Deployment Environments](../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.

## Environment Variables

Configuration uses the conventions defined in
[Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md).
This service relies on the [PostgreSQL credentials](../../infrastructure/environment-and-secrets.md#postgresql-credentials).
Redis variables are not used.

See [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md)
for TLS variables `FIREMUD_GRPC_CERT_CHAIN`, `FIREMUD_GRPC_PRIVATE_KEY`, `FIREMUD_GRPC_CA_CERT`
and the `FIREMUD_SERVICES_*` service discovery settings.

## Proto Files

The service API contract resides in
[../../../../protos/game-design/v1](../../../../protos/game-design/v1). Generate
stubs with `./gradlew generateProto` whenever these files are updated.

## 📚 Related Documentation

See [Versioning & Runtime Configuration](../system-architecture-versioning-runtime.md) for how published versions are promoted to runtime.

- [Multi-Tenancy](../system-architecture-multi-tenancy.md)
- [System Architecture Overview](../system-architecture-overview.md)
- [Service Responsibility Matrix](../service-responsibility-matrix.md)
- [User Journeys – Game Creation](../user-journeys.md#2-game-creation)
- [User Journeys – World and Entity Design](../user-journeys.md#3-world-and-entity-design)
- [User Journeys – Publish and Start a Game Instance](../user-journeys.md#5-publish-and-start-a-game-instance)
- [User Journeys – Patch and Update a Live Game](../user-journeys.md#9-patch-and-update-a-live-game)
- [Asset Storage Setup](asset-storage.md)
- [World Editing & Customization Tools](world-editing-tools.md)
- [Ability & Action Design Tools](ability-action-tools.md)
- [Item & Equipment Balancing Tools](item-equipment-balancing.md)
- [Web-Based Visual Design Interface](web-visual-interface.md)
- [Version Control for Design Assets](version-control.md)
- [In-Game Modding and Plugin Framework](modding-framework.md)
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

## Additional Details

### REST & gRPC Endpoints

#### REST

- `GET /ping` – basic health check returning `"pong"`.
- `POST /assets` – upload a binary asset for a tenant.

```bash
curl http://localhost:8080/ping
```

#### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`game_design_service.proto`](../../../protos/game-design/v1/game_design_service.proto).
- `SaveRevision(SaveRevisionRequest) returns (SaveRevisionResponse)` – persists a design change.
- `PublishVersion(PublishVersionRequest) returns (PublishVersionResponse)` – publishes a frozen version.
- `ListVersions(ListVersionsRequest) returns (ListVersionsResponse)` – lists available versions.

```bash
grpcurl -plaintext localhost:6565 game_design.v1.GameDesignService/Ping
```

### Saga Participation

Publishing a game version is coordinated using the Saga utilities from `firemud-common`. The `VersionServiceImpl` builds a workflow that first persists the new version and then copies design data to downstream services. If any step fails, previously executed actions are compensated so the database remains consistent. See the [Versioning & Runtime Configuration](../system-architecture-versioning-runtime.md) document for the overall flow.

## Local Development Notes

`TestDataSeeder` populates a demo game, template, revision and version when the `dev` Spring profile is active. A simple smoke-test script verifies both REST and gRPC endpoints. Cross-service integration tests live under `src/test/java/crossservice` and can be executed once dependent services are available.

## Future Enhancements

- [Web-based visual design interface](web-visual-interface.md)
- [Version control integration for design assets](version-control.md)
- [In-game modding and plugin framework](modding-framework.md)
