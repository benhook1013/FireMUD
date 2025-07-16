# 🏢 FireMUD System Architecture: Multi-Tenancy

This document explains how FireMUD hosts many independent games on shared infrastructure.
It complements the [System Architecture Overview](./system-architecture-overview.md) and
the multi-tenant requirements in the
[Core Requirements](../project-management/core-requirements.md).

---

## 🔗 Account-to-Game Relationships

- Players have a **single platform account** managed by the **Account Service**.
- The same account can join multiple games. Each game is identified by a `tenantId`.
- `tenantId` values are string GUIDs and appear in every API.
- They are standardized as strings across gRPC and REST.
- Database tables store this column as `VARCHAR(36)`.
- Character data and progress are scoped per `tenantId`.
- A player may have different characters in different games.
- Authentication is global, but services always check the requested `tenantId`.
- They enforce it when retrieving or updating game data.
- Friend lists and guilds are maintained by the Social & Groups Service.
- Per-game friendships store `tenantId` and player IDs.
- Account-to-account friendships reference global account IDs.

## 🗂️ Data Separation per Service

- All microservices connect to a single PostgreSQL instance and store data in
  service-specific schemas.
  Current migrations place tables in the `public` schema but will move to
  dedicated schemas. (TODO: Not yet implemented)
- Databases are **shared across tenants**, with a `tenantId` column on each table to isolate data.
- Services enforce the `tenantId` filter on all queries to prevent cross-game
  access. (TODO: Not yet implemented)
- Redis keys prefix the `tenantId` as described in the
  [Redis Architecture](./system-architecture-redis.md#key-format-examples) so
  cached session state and runtime data remain isolated.
- The React frontend loads per-tenant themes and branding files.
- See (TODO: Not yet implemented)
  [Game Customization Options](./game-customization-options.md) and the
  [Frontend Architecture](./system-architecture-frontend.md) for details.

## ⚙️ Tenant Configuration & Scaling

- Game-specific settings—such as world size and tick intervals—are stored in
  configuration tables keyed by `tenantId`.
  Runtime flag behavior is described in
  [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md).
- Creating a new game world triggers a Saga across services. The steps are
  outlined in
  [World Creation Workflow](./microservices/world-management-service/world-creation-workflow.md).
- All microservices run as shared deployments; there is
  **no tenant-specific infrastructure** or dedicated clusters.
- Game Session Service instances scale horizontally based on overall load.
- Per-game resource quotas are planned so one tenant cannot exhaust cluster capacity.
- This feature is not yet implemented. (TODO: Not yet implemented)
  Quota thresholds will be configured per tenant and metrics will expose current usage so
  operators can track `active_sessions` and quota denials.

---

> 🔗 For service roles and interactions, see the
> [System Architecture Overview](./system-architecture-overview.md) and the
> [Service Responsibility Matrix](./service-responsibility-matrix.md).
