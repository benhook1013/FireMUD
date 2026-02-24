# FireMUD System Architecture: Versioning & Runtime Configuration

This document explains how game data is versioned and activated at runtime. It also shows where runtime feature flags live and how they are edited.

> For service ownership, see the [Service Responsibility Matrix](./service-responsibility-matrix.md). Multi-tenant storage details are covered in [Multi-Tenancy](./system-architecture-multi-tenancy.md).

---

## Game Version Publishing

The **Game Design Service** manages version metadata and publish workflows for game configuration (world layouts, scripts, item templates, etc.). Domain services (World Management, Entity Management, Game Logic, and others) store the actual versioned domain data for each `tenantId`.

1. When a version is ready, creators trigger a **Publish** action in the Game Design Service using the `PublishVersion` gRPC method.
2. The service writes a new `version_id` and associated records to its database, linking the version to each tenant and recording notes and base versions.
3. During authoring, the Game Design Service applies revisions incrementally to **Draft** template rows hosted by the owning domain services via idempotent design APIs keyed by `(tenantId, versionId)`. At publish time, a Saga coordinates all domain services so they validate and finalize their existing Draft data for the given `tenantId` and `version_id`, marking that data as Published and ready for runtime use. No separate design database is copied into the domain services; they already host the versioned graphs for their domains.
   - Publish-time validation must be based on durable, domain-owned digests: every participating domain service must report `GetDraftDesignDigest(tenantId, versionId)` matching the commit being published (`appliedCommitId`, `contentDigest`, and `digestSchemaVersion`). If any digest is missing or mismatched, publish must fail fast and the version must remain Draft/OUT_OF_SYNC until reconciliation succeeds. See `design/architecture/microservices/game-design-service/world-editing-tools.md`.
   - Participant selection is fixed by publish type (full publish vs script-only patch) using the matrix in `design/architecture/microservices/game-design-service/version-control.md#digest-participants-by-publish-type`; publish workflows must not change digest participants implicitly at runtime.
4. As part of the Saga, the Game Design Service runs an **asset export** step for each `(tenantId, versionId)` that uploads design-time assets to object storage, generates a deterministic `manifest.json` for the version, and updates version metadata with the manifest location. This step is implemented as an idempotent Saga step with compensation as described in [Asset Storage Setup](./microservices/game-design-service/asset-storage.md). If this step or another publish step fails irrecoverably, the Saga can mark the version as **Failed** so it is not eligible for activation.
   - The asset export step must persist a `manifestHash` in version metadata and treat any mismatch between recorded hash and served bytes as drift/corruption, not a legitimate “update” to a Published/Active version.
5. A notification or message informs the Game Session Service that a new version exists so game instances can be started or patched against it.

Published versions are immutable; further changes require publishing a new `version_id`. Services may keep additional draft or experimental versions internally, but only Published versions are eligible to be activated for live game instances.

### Ownership

Ownership is split between the Game Design Service and domain services:

- The Game Design Service is the canonical store for:
  - Version metadata and lifecycle (`Draft`, `Published`, `Active`, `Retired`, `Failed`).
  - Branches, commits, revisions, and their relationships to domain objects.
  - References from revisions/versions to assets, scripts, and templates via stable identifiers.
- Domain services such as World Management, Entity Management, Game Logic, and others are the canonical stores for:
  - Versioned template graphs keyed by `(tenantId, versionId)` (world topology, entity templates, balance records, etc.).
  - Runtime/instance state keyed by `(tenantId, gameInstanceId)` or equivalent.

Domain services must not persist their own commit histories; they expose only the current and historical template snapshots keyed by `(tenantId, versionId)`. Game Design Service must not maintain a second, divergent copy of world or entity template graphs; it references domain templates via stable IDs and version metadata.

### Version Lifecycle

Game versions go through a simple lifecycle:

- **Draft** – revisions are still being edited; the version cannot be activated.
- **Published** – the Saga has completed successfully (including asset export) and the version is available for use by game instances.
- **Active** – a specific Published version is recorded as the `runtime_version` for one or more entries in the `game_instances` table.
- **Failed** – a version whose publish Saga has failed in a way that leaves data incomplete or unusable. Failed versions are never eligible for activation until a repair/retry step transitions them back to Draft or Published.
- **Retired** (also referred to as “Archived” in some UIs) – the version is no longer eligible to be activated for new instances, and no `game_instances` reference it as `runtime_version`. Only **Retired** versions may have their object-store prefixes or other external assets deleted.

Administrative tooling (for example via the Game Design Service or Logging & Admin Service) should:

- Prevent retiring a version while any `game_instances` still reference it.
- Prevent retiring a version while any **game templates** still reference it as their underlying world/entity/script version; designers must migrate those templates to a successor version before retirement.
- Ensure the `game_manifest` table and any launch manifests are updated when a version is retired so operators cannot accidentally start new instances against it.

Runbooks that remove published assets from the object store must validate that the corresponding version has already reached the **retired** state.

### Template Mutability Rules

Published versions are immutable from the perspective of domain templates:

- The Game Design Service is the source of truth for version state (`Draft`, `Published`, `Active`, `Failed`, `Retired`).
- Domain services such as World Management and Entity Management expose **design APIs** that create or update template rows only for Draft versions keyed by `(tenantId, versionId)`. Authoring tools call these APIs incrementally as revisions are saved so Draft template graphs in domain services always reflect the latest committed design state for that version.
- Once a version reaches the Published state, template tables in domain services must treat rows for that `(tenantId, versionId)` as read-only. Any attempt to modify templates for a Published, Active, or Failed version should fail fast at the design API boundary and be surfaced as a validation error in the Game Design UI.
- Runtime gameplay flows (ticks, Sagas for world creation, etc.) never mutate template tables. They only read templates for the active `runtime_version` and write to runtime/instance tables keyed by `(tenantId, gameInstanceId)` or equivalent.

At a high level, each `(tenantId, versionId)` template graph in a domain service follows this lifecycle:

- **Absent** – no rows exist for the version.
- **Draft** – design APIs have created or updated rows keyed by `(tenantId, versionId)`; additional revisions may continue to modify these templates.
- **Published** – the publish Saga has validated the Draft data and marked the version Published. Template rows are now immutable.
- **Active** – some game instances reference the version as `runtime_version`, but templates remain immutable.
- **Failed** – publish attempted but left the version in an unusable state; templates, if present, must not be used for new instances until the version is repaired.
- **Retired** – no `game_instances` reference the version and it is no longer eligible for activation; templates may be kept for historical inspection until migrations retire them.

This contract ensures that Published template graphs remain stable inputs for rollback and historical inspection. Structural changes to templates must always occur by creating a new Draft version, applying revisions, and publishing a new `version_id`.

### Script-Only Patch Versions

Minor fixes to NPC behavior or quest logic often only touch automation scripts.
To avoid a full world restart, the Game Design Service can publish a **script-only patch version** using the `PublishScriptPatchVersion` gRPC call.
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
a data copy or world restart.
Runtime services reload the affected scripts in
memory and continue using the underlying `baseVersionId` for all other assets and templates. Script-only patches are **strictly limited to script definitions and related Automation & Scripting metadata**; any change that needs new or modified assets, world layouts, entity templates, or other non-script configuration must be delivered via a full `PublishVersion` flow that produces a new `versionId`.
When a patch is published the Game Design Service calls the
[`NotifyScriptVersionUpdate`](./microservices/automation-scripting-service/README.md#notifyscriptversionupdate)
gRPC endpoint in the Automation & Scripting Service so modified scripts are
reloaded in memory. The Game Session Service records the active
`script_patch_version` with each running game instance for recovery. See
[Scripting & Automation](./system-architecture-scripting.md) for more details.

Patch selection must be explicit and pinned:

- Runtime must never implicitly select “latest READY patch” for an instance. The pinned `scriptPatchVersion` for a running `(tenantId, gameInstanceId)` is the only script patch that may be referenced by gameplay triggers.
- Plugin enablement and active `pluginVersionId` selection must also be explicit per `(tenantId, gameInstanceId)`; automation must not implicitly activate “latest” plugin versions for a running instance.
- Pin/rollback APIs and their required events are specified in `design/architecture/system-architecture-scripting-control-plane-api.md`.
- Trigger Identity required fields (including `gameInstanceId` and when `regionEpoch` is required) are specified in `design/architecture/system-architecture-scripting-normative-contract-tables.md`.

### Cross-Asset Version Cohesion

Several kinds of design-time assets participate in a published game version:

- **Core templates and world data** owned by domain services such as World Management, Entity Management, and Game Logic.
- **Abilities and actions** owned by the Game Logic Service and authored via the Ability & Action tools.
- **Automation scripts and plugins** owned by the Automation & Scripting Service and authored via the scripting DSL and modding framework.

The versioning model treats a published `version_id` as the **cohesive bundle** of these assets for a tenant:

- For a given `(tenantId, versionId)`:
  - Template and ability schemas and identifiers are stable for the lifetime of that version.
  - Scripts and plugins may evolve through **script-only and plugin-only patch versions**, expressed as `scriptPatchVersion` and `pluginVersionId` values, as long as they remain compatible with the underlying templates and ability schemas.
- Script-only and plugin-only patches:
  - Are tied to a `baseVersionId` and do not change core world or ability data.
  - May be hot-reloaded for running game instances without changing the `runtime_version`, following the semantics in the scripting architecture and Automation & Scripting Service docs.
  - Must not introduce dependencies on ability or template changes that require a new `version_id`; such changes should be delivered via a new game version publish.

Rollback behavior follows the same cohesion rules:

- Rolling back `runtime_version` for a game instance reverts templates and abilities to those for the selected `version_id`, and script/plugin patch selection follows the version/pinning rules in the scripting docs.
- Rolling back a script-only or plugin-only patch affects only automation behavior for the current `version_id`; it does not change templates or abilities.

Tooling in the Game Design and Logging & Admin services should surface these relationships so creators and operators can see, for a given game instance, which `version_id`, `scriptPatchVersion`, and plugin versions are in effect. Admin APIs in the Game Design or Logging & Admin services should also support:

- Listing game templates that reference a given `versionId`, so that designers can migrate or delete them before retiring that version.
- Bulk migration operations that rewrite `GameTemplateDto.config` references from an old `versionId` to a successor `versionId` in a controlled, auditable way.
  - These operations must be driven by normalized dependency tables (for example `game_template_version_ref` and related reference rows), not by best-effort parsing of arbitrary JSON blobs.
  - When templates pin defaults such as `scriptPatchVersion`, migration tooling must update both the JSON payload and the normalized `game_template_script_patch_ref` rows atomically so instance creation does not observe mixed dependencies.

### Schema Migrations vs Design Data

Published game versions are **design-data bundles** (world templates, entity templates, abilities/actions, scripts/plugins, and asset manifests) keyed by `versionId` and scoped to a `tenantId`. Database schema changes remain the responsibility of each microservice and are applied via Flyway when a service container restarts during a platform deployment. Publishing a new design version therefore does not run Flyway migrations—it finalizes versioned data already stored in domain services, exports version-scoped manifests/assets, and makes the new `versionId` eligible for activation. Runtime instances load data by `runtime_version` and may hot-reload only script/plugin patch layers where explicitly supported. See
[Database Migrations](./system-architecture-database-migrations.md) for the
Flyway workflow.

### Non-Script Content Updates

Non-script content such as world layouts, item templates, and balance curves follow
a stricter lifecycle than scripts:

- Changes to these templates are always delivered via new versions (`version_id`)
  published by the Game Design Service. Domain services never apply in-place edits
  to template rows for Published or Active versions.
- Switching the `runtime_version` for a game instance is a controlled **restart**
  operation managed by the Game Session Service. Instances do not hot-swap
  non-script templates mid-session; operators select a new version and restart the
  instance so all services reload consistent data for the new `runtime_version`.
- Template identifiers and their semantics are stable within each version; a given
  template ID must not be repurposed to point at a different conceptual entity
  while any non-Retired version references it.
- Destructive changes to template schemas or semantics (for example removing an
  item archetype or reusing a template ID for a different purpose) are only
  allowed after all versions that depend on the previous behavior have entered
  the Retired state and migrations have followed the guidelines in
  [Database Migrations](./system-architecture-database-migrations.md).

For non-script content, there is no cross-version reuse of instance data. A given `gameInstanceId` is always tied to a single `runtime_version`, and all `*_instance` rows for that instance must be derivable from that version’s templates. Migrating a game to a different version is modeled as starting a new game instance with its own `gameInstanceId` (and fresh world creation workflow) rather than reusing existing world instance rows across versions.

## Version Activation & Rollback

The **Game Session Service** controls which published version is active for each live game instance. See [User Journeys – Publish and Start a Game Instance](./user-journeys-creators.md#4-publish-and-start-a-game-instance) for the high level flow.

- When starting a game, it reads the desired `version_id` from a manifest or launch request and stores this value as `runtime_version` in the `game_instances` table.
- The available versions a tenant can launch are listed in the `game_manifest`
  table managed by the Game Session Service.
- Only one version is active per game instance. If an issue occurs, administrators can instruct the service to roll back by selecting a previous `version_id` and restarting the instance.
- All runtime services read their data using the active `runtime_version`, ensuring consistent rules during play.

Before any operation that changes whether a tenant is actively serving gameplay for a given instance (for example, starting a new instance, restarting an instance with a different `runtime_version`, or rolling back to a previous version), the Game Session Service must consult the runtime entitlement contract:

- Call `GetTenantEntitlements(tenantId)` in the Account Service and enforce that:
  - The tenant is currently **available for gameplay** under its subscription and billing state (for example, not `suspended` or `canceled`).  
  - The requested instance count and configuration remain within plan-derived quotas (for example, maximum concurrent instances for the tenant).
- If entitlements indicate that the tenant is unavailable for gameplay or that quotas would be exceeded, the operation fails with a clear, tenant-scoped error and no instance-level changes are applied.

When entitlements transition to hard-cutoff states (`suspended` or `canceled`) after an instance is already running, runtime behavior is deterministic:

- New admissions are blocked immediately.
- Existing player gameplay sessions are revoked immediately.
- Running instances enter a bounded non-admissible drain phase (target: 5 minutes maximum) for cleanup, then stop.

Activation, rollback, and restart operations remain blocked until `GetTenantEntitlements(tenantId)` returns gameplay-available status again.

Because rollback relies on being able to reactivate previously published `version_id` values, schema migrations must be coordinated with versioned data. See the **Version-Aware Migration Guidelines** in [Database Migrations](./system-architecture-database-migrations.md) for constraints on dropping or reshaping columns that are still used by any published version.

Rollback class boundary for versioning flows:

- Publish/finalization and activation-prep run under **Class A** Saga rollback semantics (compensation allowed before activation).
- Once a version is active and runtime effects are being applied, mutations run under **Class B** runtime semantics (retry/reconcile with stable `EffectId`; no destructive cross-service rollback). See [Transaction Strategies](./system-architecture-transactions.md#rollback-boundaries-by-operation-class).

## Runtime Feature Flags

Runtime feature flags allow limited behavior changes without publishing a new design version.
They are **defined in the Game Design Service** and copied into the **Game Session Service**
when a version is published. The definitions table and copy steps manage this workflow.

- Designers create and maintain the set of flag definitions in the Game Design Service UI.
  Definitions are stored in a `runtime_flag` table for each tenant.
- Administrators toggle flag values through the
  [**Logging & Admin Service**](./microservices/logging-admin-service/README.md) web interface.
- The Logging & Admin Service forwards each change to the Game Session Service,
  calling `ToggleFeatureFlag` via gRPC so running instances update immediately.
- The Game Session Service persists active flag values in its `feature_flag` table.
  Sessions use consistent configuration even after reconnects.
  The Logging & Admin Service may store audit entries.
  It is not the source of truth for runtime behavior.
- During each tick cycle the active flags are applied before executing game logic.
  See [Tick System](./system-architecture-ticks.md) for details.

## Flow Summary

```mermaid
flowchart TD
    A[Designers publish version] --> B[Game Design Service stores new version_id]
    B --> C[Domain services finalize draft data for version_id]
    C --> D[Game Session Service notified of new version]
    D --> E[Session starts game using chosen version_id]
    E --> F[Runtime flags loaded and applied]
    F -->|Admin edits| G[Logging & Admin Service calls Game Session Service]
```

By decoupling published versions from runtime flags, FireMUD can rapidly iterate on new content while still allowing safe toggles for experimental features during live gameplay.

For API versioning conventions see [gRPC Protocol Guidelines](./system-architecture-grpc.md).

## Related Documentation

- [Database Migrations](./system-architecture-database-migrations.md)
- [Game Customization Options](./game-customization-options.md)
- [Game Session Service](./microservices/game-session-service/README.md)
- [Service Responsibility Matrix](./service-responsibility-matrix.md)
- [System Architecture Overview](./system-architecture-overview.md)
- [Testing Strategy](./system-architecture-testing.md)
- [Transaction Strategies](./system-architecture-transactions.md)
