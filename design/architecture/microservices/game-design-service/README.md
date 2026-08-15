# Game Design Service

## Overview

Offers tools for building worlds, items, actions, and events that make up each game. Used by creators to design content without touching the underlying code. It maintains version metadata, configuration manifests, and templates so new game instances can be created with predefined rules. Default administrator setup is available.

This service is used only at design time. Runtime clients never request logos,
favicons, or themes from it; published assets are served from object storage via
manifest files.

Authoritative read surfaces worth wiring into creator/operator tooling:

- script patch tenant-readiness and rollout visibility defined in [Scripting Control Plane API](../../system-architecture-scripting-control-plane-api.md)
- plugin version publication status, signer-policy convergence, and status-change events defined in [modding-framework.md](./modding-framework.md)
- template resolution and launch-default semantics defined in [game-templates.md](./game-templates.md)

### Responsibilities

- Provide REST/gRPC tools and a web UI for editing game assets.
- Manage version metadata and publish immutable game configurations
- Track revision history for rollback
- Orchestrate cross-service publish workflows and notify downstream services when new versions are available.
- Upload branding assets to version-scoped object storage and generate a
  `manifest.json` so runtime clients can load themes and logos without calling
  this service.
- Act as the sole owner of game asset publishing to the S3-compatible object store; downstream services and clients read published assets via configured URLs and do not write directly to the asset store. Logical world and entity templates remain in PostgreSQL schemas owned by World Management, Entity Management, and related domain services and are not stored as blobs in the asset store.
- When publish workflows need to expose derived domain artifacts outside their owning service storage (for example world navmesh/path graph bundles), Game Design remains the sole object-store writer. Owning domain services hand those artifacts to the publish workflow as explicit inputs; they do not bypass the publish workflow with direct object-store writes.
- In the initial slice, runtime discovery of those derived artifacts must use the version `manifest.json` attested by `published_release_bundle`. The canonical attestation shape for that bundle includes `artifactDigests[]`, `requiredManifestAssetKeys[]`, and `manifestHash`; consumers must not depend on undocumented bucket key conventions or on any separate ad hoc artifact-path field outside that bundle.
- Own version lifecycle state and CAS epoch metadata (`versionState`, `versionStateEpoch`) and expose control-plane APIs for activation/retirement-safe transitions.
- Expose control-plane integrity APIs such as `GetDesignControlPlaneDigest` and `CanDeleteVersionAssets` used by publish gating and asset-retention workflows.
- Expose CAS-guarded asset purge APIs (`BeginPurgeVersionAssets`, `FinalizePurgeVersionAssets`) so purge eligibility re-check and artifact-state transitions are race-safe.
- Expose a deterministic launch-resolution API or equivalent control-plane workflow that produces an immutable resolved launch descriptor before instance creation begins. Launch resolution must be keyed by `controlPlaneRequestId` so retries of the same launch attempt return the same descriptor values, and caller-supplied runtime overrides may only fill fields the template leaves unset. If the template already supplies a default, any caller-supplied value for that field must fail deterministically.

## Architecture / Design Notes

- Provides REST/gRPC APIs for editing game data and managing version metadata.
- Coordinates with World Management, Entity Management, Game Logic, and Automation & Scripting Service to apply changes.
- Stores version descriptors and manifests so new game instances can be generated from templates.
- Maintains history of revisions so designers can roll back to prior versions.
- Publishing a new game version now starts the durable Temporal `publish` workflow described in
  [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md)
  and [Transaction Strategies](../../system-architecture-transactions.md).
  The durable orchestration persists version metadata, coordinates participant
  finalization/attestation, and exposes workflow runtime metadata through the
  canonical Game Design control-plane read surfaces.
- Plugin publication is narrower than a full game-version publish workflow. `UploadPluginBundle` and `PublishPluginVersion` are design-time Game Design workflows that validate and persist immutable plugin-version metadata; they do not repin running games and do not require the cross-service runtime cutover/orchestration used by `PublishVersion`. Cross-service runtime effects begin later, when Logging & Admin invokes instance-scoped activation against Automation & Scripting.
- Design assets are stored per `tenantId` so multiple games can coexist in the
  same database schema. Queries and version publishing workflows enforce this
  tenant filter. See [Multi-Tenancy](../../system-architecture-multi-tenancy.md).
- All control-plane (design-time) gRPC APIs require JWT authentication. REST authentication is supported. Runtime gameplay services do not call the Game Design Service during ticks; they load published templates and manifests from the owning domain services and object storage and authenticate internal runtime traffic using mutual TLS plus propagated `SessionContext` rather than per-request JWT parsing.
  Tokens are parsed by a shared `AuthTokenInterceptor` configured in `GrpcConfig`, which stores claims in `SessionContext` for role checks. Service-to-service traffic uses mutual TLS certificates managed by cert-manager as described in the [Security Architecture](../../system-architecture-security.md).
- Utilizes the [Shared Libraries](../../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.

### Script Patch Lifecycle and Runtime Coordination

The Game Design Service owns the **authoring** view of script patches, while the Automation & Scripting Service owns the **runtime** lifecycle of those patches per tenant:

- When `PublishScriptPatchVersion` is called, the Game Design Service:
  - Validates and persists the new script graphs, bindings, and metadata, including cross-asset compatibility checks against the pinned `baseVersionId` (for example, ensuring referenced ability identifiers exist and match the ability schema for that base version).
  - Compiles stable runtime identities needed for reconciliation, including a `scheduleDefinitionId` for each logical timer/interval definition so Automation & Scripting can preserve or tombstone schedules deterministically across patch changes.
  - Computes or reads an immutable `abilitySchemaDigest` for the pinned `baseVersionId` and records it with the patch metadata used for runtime validation.
  - Notifies Automation & Scripting of the published patch so it can ingest the compiled definitions and bindings for the target `<tenantId, scriptPatchVersion>` and start or reuse the durable Temporal `script-patch-readiness` workflow.
  - Treats the publish as **asynchronous** from a runtime perspective: the version is recorded as published in design-time tables, but its readiness for execution is determined by the Automation & Scripting Service.
- For each `<tenantId, scriptPatchVersion>`, the Automation & Scripting Service tracks a tenant readiness lifecycle (`PENDING_VALIDATION`, `ONLOAD_RUNNING`, `READY`, `FAILED`, and terminal `SUPERSEDED`) as described in `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md#script-patch-lifecycle`. `SUPERSEDED` applies only to a displaced, unpinned record in `PENDING_VALIDATION` or `ONLOAD_RUNNING`; an already-pinned `READY` record is not relabeled `SUPERSEDED` merely because a newer publish arrives. Already-admitted work remains governed by its captured Game Session `(scriptPatchVersion, scriptPinEpoch)` tuple and normal persistence, handoff, and execution fences until explicit repin or rollback, or fence rejection.
- The Game Design Service queries readiness via a read-only API such as `GetScriptPatchStatus(tenantId, scriptPatchVersion)` and consumes the owner-provided rollout read for creator visibility so that UIs can show:
  - That a patch is published but still **pending runtime validation**.
  - The patch's `baseVersionId` and `abilitySchemaDigest` used for runtime compatibility gates and pinning checks.
  - Whether `onLoad` initialization has succeeded or failed for each tenant.
  - When Game Session has committed a patch pin, rollback, or repin for a specific game instance. Game Session owns the exact pin epoch and append-only rollout history; Game Design does not reconstruct that history from readiness or notification arrival order.
  - Event-family responsibilities are explicit:
    - `ScriptPatchTenantStatusChanged` drives readiness gates and publish validation status.
    - `ScriptPatchPinChanged` may update creator-facing observed convergence visibility; direct Game Session current-pin and history reads remain authoritative, and no consumer derives rollout history locally.

In the design UI:

- `PublishScriptPatchVersion` should surface that “published” means “accepted into design-time history”, not “active at runtime”.
  - The UI must treat runtime readiness as a separate phase and should show an explicit “runtime validation pending” state until Automation & Scripting reports `READY` (or `FAILED`) for `<tenantId, scriptPatchVersion>`.
  - Any control-plane workflow that would pin/promote a patch for a running game instance must be blocked until `READY` is observed via `GetScriptPatchStatus` and/or `ScriptPatchTenantStatusChanged`.
- Failed `onLoad` runs that result in `FAILED` patch status should be visible to designers, with links back to `script_event_audit` entries and automation metrics for debugging.
- Design-time publish-workflow failures (for example, invalid ability references) are tracked in Game Design’s own versioning state (for example, a `PUBLISH_FAILED_DESIGN` status) and do **not** create or update patch lifecycle rows in the Automation & Scripting Service. UIs should clearly distinguish these design-time failures from runtime `FAILED` states reported by the Automation & Scripting Service so creators know whether a patch failed before or after reaching the runtime.

Compatibility contract requirement:

- `PublishScriptPatchVersion` and plugin enable/publish paths must validate compatibility against the immutable `abilitySchemaDigest` bound to `baseVersionId`, not against mutable live lookups.
- The validated digest must be propagated to runtime-facing metadata/audit surfaces so operators can prove which schema snapshot a patch/plugin was validated against.
- Current plugin publication must persist a canonical signed manifest contract that includes at least `pluginId`, `pluginVersionId`, exact `baseVersionId`, exact `abilitySchemaDigest`, declared entrypoints, and declared bindings. Runtime services must consume those immutable fields as the activation source of truth; the ADR 0111 target unsigned-provenance variant still requires the exact manifest, digest, validation, approval, and platform-attestation evidence when author-signature evidence is absent.
- Plugin versions use a separate Game Design lifecycle from runtime activation. Publication status answers whether a bundle is accepted into immutable authoring history; instance activation status answers whether a published plugin version is active for a given running game instance.

### Script-transition ownership consequence

Game Design owns authored script-patch revisions, immutable publication metadata, base-version compatibility, and plugin bundle publication. It does not select the active patch for a running instance, issue a script pin epoch, or author rollout/rollback history. A published patch is only a candidate for Automation tenant readiness; creators see runtime readiness separately from the Game Session-owned instance pin. Plugin publication similarly remains distinct from instance activation: linked plugins use the shared DSL and sandbox, but retain their independent `pluginId`/`pluginVersionId` publication and activation identity. See [ADR 0109](../../decisions/adr-0109-game-session-owned-script-rollout-history.md), [ADR 0110](../../decisions/adr-0110-explicit-opt-in-schedule-continuity-across-script-transitions.md), [ADR 0111](../../decisions/adr-0111-unified-dsl-with-distinct-embedded-script-and-plugin-lifecycles.md), and the canonical scripting control-plane contracts.

### Canonical Authoring Boundary

The initial supported authoring package for first-party game content is the Game Design Service's own revision and commit model, not a filesystem project format. Designers edit through the web UI and service-owned APIs; Game Design persists revisions, applies them to domain-owned Draft templates, and publishes immutable versions or script-only patches from that canonical history.

Implementations must not introduce ad hoc import/export, Git checkout, or local package semantics for first-party content. Any future external authoring package must be specified as a separate contract before it is exposed, including stable ID preservation, cross-service reference validation, asset inclusion, plugin inclusion, conflict handling, and mapping back into Game Design revisions and commits. Until that contract exists, "version control integration" means Game Design's database-backed branches, commits, provenance, and optional synchronization hooks described in [Version Control for Design Assets](version-control.md), not a second source of truth.

Plugin bundles are the only supported file-based content package in the initial slice. The current implementation and initial hosted policy require allowlisted Ed25519-signed immutable artifacts governed by [modding-framework.md](./modding-framework.md), and they do not replace or extend the first-party revision package format. [ADR 0111](../../decisions/adr-0111-unified-dsl-with-distinct-embedded-script-and-plugin-lifecycles.md) records target provenance behavior that may permit operator-approved unsigned packages only after exact digest, complete validation, explicit scoped approval, and platform acceptance attestation; hosted policy may prohibit unsigned intake, and this target path is not implemented.

## Key Features

- Web-based visual interface for worlds, items, actions, and scripts.
- World and room editors.
- [Ability & Action Design Tools](ability-action-tools.md)
- Scripting and event workflow creation.
- Visual editor for building scripts in the same component-based DSL used by the
  Automation & Scripting Service.
- [Game templates](game-templates.md) with predefined rulesets. Admin account configuration is available.
- Patch note management for published games.
- Supports script-only patch versions that reference a `baseVersionId` and
  generate a new `scriptPatchVersion` without requiring a full publish.
- Supports plugin bundle publication as immutable design-time artifacts keyed by `pluginId` and `pluginVersionId`, with exact `baseVersionId` and `abilitySchemaDigest` pinning for later instance-scoped activation.
- Does not track individual script definitions at runtime; only the patch
  version metadata is recorded. Runtime services manage the active script
  registry and are notified when a patch version is published.
- [Item & Equipment Balancing Tools](item-equipment-balancing.md)
- Import/export of design assets is deferred until a canonical contract exists for ID remapping, cross-service reference validation, asset/plugin inclusion, and conflict handling.
- Version control integration for design assets.
- In-game modding and plugin framework for runtime customization.

### Data Model

- `game` table defines the project. An `owner_id` reference associates the game with an account.
- `revision` table stores individual asset changes with author metadata.
- `version` table groups revisions into immutable snapshots for publishing. It includes `version_number`, `base_version_id`, `script_patch_version`, `is_script_only` and `notes` columns.
- `game_templates` table stores predefined configuration templates for new games.
- [`runtime_flag` table](feature-flags.md) manages feature flag definitions and
  corresponding APIs expose these records.
- `game_assets` table stores asset metadata for uploaded binary files such as icons or sound effects; canonical bytes live in object storage referenced by this metadata.
- Plugin bundle metadata must be persisted as indexed design-time records keyed by `(tenantId, pluginId, pluginVersionId)` and include manifest fields, signer identity and verification status for every current signed bundle, publication status, validation outcomes, `bundleDigest`, and plugin asset distribution manifest fields when `assetRefs[]` are present. Signer fields may be absent only for the accepted ADR 0111 target unsigned-provenance path, which remains unimplemented and requires explicit scoped approval, platform acceptance attestation, and hosted-policy permission. The bundle bytes remain in object storage, but plugin activation metadata must be queryable without unpacking archives on routine reads.

Design-time tables (such as `revision`, `version`, `game_templates`,
`runtime_flag`, asset metadata tables, and release-attestation tables) are the
source of truth for world and entity history. Domain services (World Management,
Entity Management, etc.) store the versioned templates they consume at runtime,
but commit and revision history remains anchored in the Game Design Service.

The Game Design Service must also persist an immutable `published_release_bundle`
record per `(tenantId, versionId)` after publish gates and asset export succeed.
This record is the canonical release attestation consumed by activation,
rollback-preflight, and repair tooling. It contains the publish workflow
identity, target `commitId`, required participant digests, `manifestHash`, and
`generationConfigRevision` for that release.

For releases that export derived artifacts outside participant-owned databases
in the initial slice, `published_release_bundle` must also include
`artifactDigests[]` entries for each exported artifact family. For world bundles
such as navmesh/path graph payloads, these entries are mandatory rather than
optional metadata.

### Design Workflow

1. Creators use the web UI to craft worlds, items, and scripts.
2. Changes are staged as revisions with metadata and author information.
3. Revisions are grouped into versions that can be published to runtime.
4. For quick fixes, designers create a script-only patch version which records a
   `scriptPatchVersion` linked to an existing `baseVersionId` and notifies
   Automation & Scripting to ingest the patch for tenant readiness validation.
   Running game instances reload only after a later control-plane pin change to
   that tenant-`READY` patch.

## Document Map

- [API Contracts](api-contracts.md) owns the REST/gRPC surface, endpoint index, and proto/OpenAPI pointers.
- [Configuration](configuration.md) owns environment variables, Redis-role statements, and asset-store configuration.
- [Operations](operations.md) owns readiness/liveness, saga-operation notes, and local-dev operational guidance.
- There is intentionally no `runtime-and-data.md` sibling for this service. Runtime/data ownership is split across the narrower canonical docs below because this service is design-time only and its durable concerns are not one coherent runtime surface.
- [Asset Storage Setup](asset-storage.md) owns asset publishing, manifest, and object-store lifecycle rules.
- [Game Templates and Configuration Tools](game-templates.md) owns template structure, launch-resolution rules, and launch orchestration boundaries.
- [Version Control for Design Assets](version-control.md) owns publish history, digests, provenance, and change-vehicle selection.
- [World Editing & Customization Tools](world-editing-tools.md) owns draft/world authoring workflows and reference-validation rules.
- [In-Game Modding and Plugin Framework](modding-framework.md) owns plugin bundle, signing, publication, and runtime activation contracts.
- [Ability & Action Design Tools](ability-action-tools.md), [Item & Equipment Balancing Tools](item-equipment-balancing.md), and [Web-Based Visual Design Interface](web-visual-interface.md) own narrower authoring surfaces.

## Dependencies

- **Internal:**
  - World Management Service for map data.
  - Automation & Scripting Service for scripts.
  - Logging & Admin Service records publishing audits.
- **External:** PostgreSQL for design metadata and object storage for asset bytes.

> See [**Gateway Architecture**](../../system-architecture-gateway.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../system-architecture-protocol-bridging.md) for
details on shared infrastructure components.

## Related Documentation

See [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md) for how published versions are promoted to runtime.

- [LLM-Assisted Content Authoring](../../system-architecture-llm-content-tools.md)
- [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- [System Architecture Overview](../../system-architecture-overview.md)
- [Service Responsibility Matrix](../../service-responsibility-matrix.md)
- [User Journeys – Game Creation](../../../product/user-journeys/creators.md#1-game-creation)
- [User Journeys – World and Entity Design](../../../product/user-journeys/creators.md#2-world-and-entity-design)
- [User Journeys – Publish and Start a Game Instance](../../../product/user-journeys/creators.md#4-publish-and-start-a-game-instance)
- [User Journeys – Patch and Update a Live Game](../../../product/user-journeys/creators.md#5-patch-and-update-a-live-game)
- [Asset Storage Setup](asset-storage.md)
- [World Editing & Customization Tools](world-editing-tools.md)
- [Ability & Action Design Tools](ability-action-tools.md)
- [Item & Equipment Balancing Tools](item-equipment-balancing.md)
- [Web-Based Visual Design Interface](web-visual-interface.md)
- [Version Control for Design Assets](version-control.md)
- [In-Game Modding and Plugin Framework](modding-framework.md)
- [API Contracts](api-contracts.md)
- [Configuration](configuration.md)
- [Operations](operations.md)
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
