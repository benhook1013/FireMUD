# 📑 FireMUD System Architecture: Versioning & Runtime Configuration

This document explains how game data is versioned and activated at runtime. It also shows where runtime feature flags live and how they are edited.

> For service ownership, see the [Service Responsibility Matrix](./service-responsibility-matrix.md). Multi-tenant storage details are covered in [Multi-Tenancy](./system-architecture-multi-tenancy.md).

---

## 🎮 Game Version Publishing

The **Game Design Service** stores the authoritative game configuration (world layouts, scripts, item templates, etc.). Designers iterate on this data and periodically **publish** a new version.

1. When a version is ready, creators trigger a **Publish** action in the Game Design Service.
2. The service writes a new `version_id` and associated records to its database.
3. Domain services (World Management, Entity Management, etc.) copy the relevant data into their own schemas using this `version_id`. Once copied, that data becomes read-only for the release so runtime services never pull directly from the design database.
4. A notification or message informs the Game Session Service that a new version exists.

Published versions are immutable; further changes require publishing a new `version_id`.

### Script-Only Patch Versions

Minor fixes to NPC behavior or quest logic often only touch automation scripts.
To avoid a full world restart, the Game Design Service can publish a **script-only patch version**.
These records include a `baseVersionId` pointing to the immutable data version
and a `scriptPatchVersion` value such as `v42-script.3`:

```json
{
  "isScriptOnly": true,
  "baseVersionId": "v42",
  "versionId": "v42-script.3"
}
```

Script-only versions appear in version history and audit logs but do not trigger
a data copy or world restart. Runtime services reload the affected scripts in
memory and continue using the underlying `baseVersionId` for all other assets.
When a patch is published the Game Design Service calls the
[`NotifyScriptVersionUpdate`](./microservices/automation-scripting-service/README.md#notifyscriptversionupdate)
gRPC endpoint in the Automation & Scripting Service so modified scripts are
reloaded in memory. The Game Session Service records the active
`script_patch_version` with each running game instance for recovery.

### Schema Migrations vs Design Data

Game versions contain **only** world data and scripts. Database schema changes
remain the responsibility of each microservice and are applied via Flyway when a
service container restarts during a platform deployment. Publishing a new design
version therefore does not run Flyway migrations—it simply loads new data when a
game instance starts or reloads scripts for patch versions. See
[Database Migrations](./system-architecture-database-migrations.md) for the
Flyway workflow.

## 🚀 Version Activation & Rollback

The **Game Session Service** controls which published version is active for each live game instance. See the [User Journeys](./user-journeys.md#5-publish-and-start-a-game-instance) document for the high level flow.

- When starting a game, it reads the desired `version_id` from a manifest or launch request and stores this value as `runtime_version` in the `game_instances` table.
- The available versions a tenant can launch are listed in the `game_manifest`
  table managed by the Game Session Service.
- Only one version is active per game instance. If an issue occurs, administrators can instruct the service to roll back by selecting a previous `version_id` and restarting the instance.
- All runtime services read their data using the active `runtime_version`, ensuring consistent rules during play.

## 🔧 Runtime Feature Flags

Runtime feature flags allow limited behavior changes without publishing a new design version. They are **defined in the Game Design Service** and copied into the **Game Session Service** (typically in a configuration table keyed by `tenantId`) when a version is published.

- Designers create and maintain the set of flag definitions in the Game Design Service.
- Administrators toggle flag values through the [**Logging & Admin Service**](./microservices/logging-admin-service/README.md) web interface.
- The Logging & Admin Service forwards each change to the Game Session Service using its [`ToggleFeatureFlag`](./microservices/game-session-service/README.md#runtime-feature-flags) gRPC endpoint.
- The Game Session Service persists active flag values in its `feature_flag` table so sessions use consistent configuration even after reconnects. The Logging & Admin Service may store audit entries but is not the source of truth for runtime behavior.
- Flags are separate from design-time configuration but still scoped by `version_id` to avoid mismatched behavior.
- During each tick cycle the active flags are applied before executing game logic; see [Tick System](./system-architecture-ticks.md) for details.

## 🗺️ Flow Summary

```mermaid
flowchart TD
    A[Designers publish version] --> B[Game Design Service stores new version_id]
    B --> C[Domain services copy data using version_id]
    C --> D[Game Session Service notified of new version]
    D --> E[Session starts game using chosen version_id]
    E --> F[Runtime flags loaded and applied]
    F -->|Admin edits| G[Logging & Admin Service calls Game Session Service]
```

By decoupling published versions from runtime flags, FireMUD can rapidly iterate on new content while still allowing safe toggles for experimental features during live gameplay.

For API versioning conventions see [gRPC Protocol Guidelines](./system-architecture-grpc.md).

## 📚 Related Documentation

- [System Architecture Overview](./system-architecture-overview.md)
- [Service Responsibility Matrix](./service-responsibility-matrix.md)
- [Transaction Strategies](./system-architecture-transactions.md)
- [Testing Strategy](./system-architecture-testing.md)
- [Database Migrations](./system-architecture-database-migrations.md)
- [Game Customization Options](./game-customization-options.md)
- [Game Session Service](./microservices/game-session-service/README.md)
