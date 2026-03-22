# Game Design Service

## Overview

Offers tools for building worlds, items, actions, and events that make up each game. Used by creators to design content without touching the underlying code. It maintains version metadata, configuration manifests, and templates so new game instances can be created with predefined rules. Default administrator setup is available.

This service is used only at design time. Runtime clients never request logos,
favicons, or themes from it; published assets are served from object storage via
manifest files.

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
- Publishing a new game version triggers a Saga that coordinates domain services as outlined in
  [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md)
  and [Transaction Strategies](../../system-architecture-transactions.md).
  The workflow is implemented using the Saga utilities from `firemud-common`
  with compensation steps to roll back if downstream steps fail. The workflow
  persists the new version metadata and instructs domain services to finalize their versioned data for that `version_id`.
- Plugin publication is narrower than a full game-version publish Saga. `UploadPluginBundle` and `PublishPluginVersion` are design-time Game Design workflows that validate and persist immutable plugin-version metadata; they do not repin running games and do not require the cross-service runtime cutover/orchestration used by `PublishVersion`. Cross-service runtime effects begin later, when Logging & Admin invokes instance-scoped activation against Automation & Scripting.
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
  - Starts a Saga that upserts compiled definitions and bindings into the Automation & Scripting Service schema for the target `<tenantId, scriptPatchVersion>`.
  - Treats the publish as **asynchronous** from a runtime perspective: the version is recorded as published in design-time tables, but its readiness for execution is determined by the Automation & Scripting Service.
- For each `<tenantId, scriptPatchVersion>`, the Automation & Scripting Service tracks a tenant readiness lifecycle (`PENDING_VALIDATION`, `ONLOAD_RUNNING`, `READY`, `FAILED`) as described in `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md#script-patch-lifecycle`.
- The Game Design Service queries readiness via a read-only API such as `GetScriptPatchStatus(tenantId, scriptPatchVersion)` and subscribes to tenant + instance rollout events (`ScriptPatchTenantStatusChanged`, `ScriptPatchInstanceRolloutChanged`) so that UIs can show:
  - That a patch is published but still **pending runtime validation**.
  - The patch's `baseVersionId` and `abilitySchemaDigest` used for runtime compatibility gates and pinning checks.
  - Whether `onLoad` initialization has succeeded or failed for each tenant.
  - When a patch has been rolled back or repinned for a specific game instance.
  - Event-family responsibilities are explicit:
    - `ScriptPatchTenantStatusChanged` drives readiness gates and publish validation status.
    - `ScriptPatchInstanceRolloutChanged` drives instance rollout history and rollback audit timeline.

In the design UI:

- `PublishScriptPatchVersion` should surface that “published” means “accepted into design-time history”, not “active at runtime”.
  - The UI must treat runtime readiness as a separate phase and should show an explicit “runtime validation pending” state until Automation & Scripting reports `READY` (or `FAILED`) for `<tenantId, scriptPatchVersion>`.
  - Any control-plane workflow that would pin/promote a patch for a running game instance must be blocked until `READY` is observed via `GetScriptPatchStatus` and/or `ScriptPatchTenantStatusChanged`.
- Failed `onLoad` runs that result in `FAILED` patch status should be visible to designers, with links back to `script_event_audit` entries and automation metrics for debugging.
- Design-time publish Saga failures (for example, invalid ability references) are tracked in Game Design’s own versioning state (for example, a `PUBLISH_FAILED_DESIGN` status) and do **not** create or update patch lifecycle rows in the Automation & Scripting Service. UIs should clearly distinguish these design-time failures from runtime `FAILED` states reported by the Automation & Scripting Service so creators know whether a patch failed before or after reaching the runtime.

Compatibility contract requirement:

- `PublishScriptPatchVersion` and plugin enable/publish paths must validate compatibility against the immutable `abilitySchemaDigest` bound to `baseVersionId`, not against mutable live lookups.
- The validated digest must be propagated to runtime-facing metadata/audit surfaces so operators can prove which schema snapshot a patch/plugin was validated against.
- Plugin publication must persist a canonical signed manifest contract that includes at least `pluginId`, `pluginVersionId`, exact `baseVersionId`, exact `abilitySchemaDigest`, declared entrypoints, and declared bindings. Runtime services must consume those signed fields as the activation source of truth.
- Plugin versions use a separate Game Design lifecycle from runtime activation. Publication status answers whether a bundle is accepted into immutable authoring history; instance activation status answers whether a published plugin version is active for a given running game instance.

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
- Plugin bundle metadata must be persisted as indexed design-time records keyed by `(tenantId, pluginId, pluginVersionId)` and include signed-manifest fields, signer verification status, publication status, and validation outcomes. The bundle bytes remain in object storage, but plugin activation metadata must be queryable without unpacking archives on routine reads.

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

### gRPC APIs

- `SaveRevision` – persists a new or updated design asset.
- `PublishVersion` – freezes a set of revisions and notifies downstream services.
- `PublishScriptPatchVersion` – creates a script-only patch version referencing a base version.
- `UploadPluginBundle` – stores a signed plugin bundle, verifies archive safety and signatures, extracts indexed manifest metadata, and records the pre-publication design-time status.
- `PublishPluginVersion` – runs design-time validation for an uploaded plugin bundle version and transitions it into immutable publication history when validation succeeds.
- `GetPluginVersionStatus` / `ListPluginVersionStatuses` – authoritative design-time read APIs for plugin publication lifecycle, signer verification status, and validation outcomes.
- `ListVersions` – enumerates published versions for selection when creating a
  game instance.
- `GetVersionState` / `CompareAndSetVersionState` – authoritative control-plane version lifecycle reads and CAS transitions.
- `GetDesignControlPlaneDigest` – digest surface for publish gating over normalized metadata.
- `GetPublishedReleaseBundle` – authoritative read surface for immutable release attestation used by activation, cutover preflight, and repair workflows.
- `CanDeleteVersionAssets` – deletion-eligibility oracle for version-scoped asset prefixes.

The protobuf service definitions under
[../../../../protos/game-design/v1](../../../../protos/game-design/v1) are the
authoritative wire-contract source for these gRPC APIs. Architecture-doc JSON
examples are normative for semantics and invariants, but field names and enums
must ultimately converge on the proto definitions.

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

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health/readiness` and `/actuator/health/liveness` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.

## Environment Variables

Configuration uses the conventions defined in
[Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md).
This service relies on the [PostgreSQL credentials](../../infrastructure/environment-and-secrets.md#postgresql-credentials).

### Redis Role and Prefixes

- The Game Design Service does **not** use Redis at runtime. It neither reads nor writes Coordination Redis or Cache/Rate-Limit Redis; all state lives in PostgreSQL and external asset storage as described above.

TLS certificates are supplied via [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates). Peer services can be discovered using variables prefixed `FIREMUD_SERVICES_`.
For example, set `FIREMUD_SERVICES_AUTOMATION_SCRIPTING_SERVICE` to override the default gRPC endpoint used by `ServiceEndpointsProperties`.
The OpenTelemetry collector endpoint can be overridden via `OTEL_ENDPOINT` (see [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md)).

Additional variables specific to this service:

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `FIREMUD_SERVICES_AUTOMATION_SCRIPTING_SERVICE` | gRPC endpoint for the Automation & Scripting Service | *(none)* |

### Asset Store

Published assets are uploaded to an S3-compatible bucket. Configure the client with:

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `ASSET_STORE_ENDPOINT` | URL of the S3-compatible service | *(none)* |
| `ASSET_STORE_BUCKET` | Bucket used for published assets | *(none)* |
| `ASSET_STORE_REGION` | Region name for the S3 client | `ap-southeast-2` |
| `ASSET_STORE_ACCESS_KEY` | Access key for the bucket | *(none)* |
| `ASSET_STORE_SECRET_KEY` | Secret key for the bucket | *(none)* |

## Proto Files

The service API contract resides in
[../../../../protos/game-design/v1](../../../../protos/game-design/v1). Generate
stubs with `./gradlew generateProto` whenever these files are updated.

For REST endpoints, the authoritative request/response schema source is
[openapi.yaml](../../../../services/game-design-service/src/main/resources/openapi.yaml).
Design-doc examples should be updated to match when those schemas evolve.

## Related Documentation

See [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md) for how published versions are promoted to runtime.

- [LLM-Assisted Content Authoring](../../system-architecture-llm-content-tools.md)
- [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- [System Architecture Overview](../../system-architecture-overview.md)
- [Service Responsibility Matrix](../../service-responsibility-matrix.md)
- [User Journeys – Game Creation](../../user-journeys-creators.md#1-game-creation)
- [User Journeys – World and Entity Design](../../user-journeys-creators.md#2-world-and-entity-design)
- [User Journeys – Publish and Start a Game Instance](../../user-journeys-creators.md#4-publish-and-start-a-game-instance)
- [User Journeys – Patch and Update a Live Game](../../user-journeys-creators.md#5-patch-and-update-a-live-game)
- [Asset Storage Setup](asset-storage.md)
- [World Editing & Customization Tools](world-editing-tools.md)
- [Ability & Action Design Tools](ability-action-tools.md)
- [Item & Equipment Balancing Tools](item-equipment-balancing.md)
- [Web-Based Visual Design Interface](web-visual-interface.md)
- [Version Control for Design Assets](version-control.md)
- [In-Game Modding and Plugin Framework](modding-framework.md)
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

## Additional Details

Plugin publication and activation are intentionally documented in
[In-Game Modding and Plugin Framework](modding-framework.md) as a separate
contract surface. The world/content-authoring docs in this folder define only
the shared versioning, publish-attestation, and launch-resolution contracts that
plugin workflows consume; they are not the canonical home for end-to-end plugin
lifecycle rules.

### REST & gRPC Endpoints

Default ports: REST on `8080`, gRPC on `6565`.

#### REST

- `GET /ping` – basic health check returning `"pong"`.
- `POST /assets` – upload a binary asset for a tenant; the service streams bytes to object storage and persists asset metadata in PostgreSQL.
- `POST /templates` – create a new game template.
- `GET /templates` – list templates for a tenant.

```bash
curl http://localhost:8080/ping
```

Detailed request and response schemas are defined in the
[OpenAPI specification](../../../../services/game-design-service/src/main/resources/openapi.yaml).

#### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`game_design_service.proto`](../../../../protos/game-design/v1/game_design_service.proto).
- `SaveRevision(SaveRevisionRequest) returns (SaveRevisionResponse)` – persists a design change.
- `PublishVersion(PublishVersionRequest) returns (PublishVersionResponse)` – publishes a frozen version.
- `PublishScriptPatchVersion(PublishScriptPatchVersionRequest) returns (PublishScriptPatchVersionResponse)` – publishes a script-only patch version.
- `ListVersions(ListVersionsRequest) returns (ListVersionsResponse)` – lists available versions.
- `GetVersionState(GetVersionStateRequest) returns (GetVersionStateResponse)` – reads authoritative version lifecycle state and CAS epoch.
- `CompareAndSetVersionState(CompareAndSetVersionStateRequest) returns (CompareAndSetVersionStateResponse)` – performs CAS-guarded lifecycle transitions.
- `GetDesignControlPlaneDigest(GetDesignControlPlaneDigestRequest) returns (GetDesignControlPlaneDigestResponse)` – returns normalized metadata digest used by publish gates.
- `GetTemplateReferencePhase(GetTemplateReferencePhaseRequest) returns (GetTemplateReferencePhaseResponse)` – returns the persisted normalized-reference enforcement phase (`BACKFILLING`, `VALIDATED`, `ENFORCED`) used by instance-creation and retirement workflows.
- `GetPublishedReleaseBundle(GetPublishedReleaseBundleRequest) returns (GetPublishedReleaseBundleResponse)` – returns the immutable `(tenantId, versionId)` release attestation including participant digests, any required `artifactDigests[]` and `requiredManifestAssetKeys[]` for exported derived assets, `manifestHash`, and `generationConfigRevision`.
- `ResolveLaunchDescriptor(ResolveLaunchDescriptorRequest) returns (ResolveLaunchDescriptorResponse)` – resolves template metadata and control-plane inputs into one immutable launch descriptor for a game-instance creation attempt. The request must include `controlPlaneRequestId`, and repeated calls for the same launch attempt must return the same descriptor values without re-resolving to newer attestation or patch state.
- `CanDeleteVersionAssets(CanDeleteVersionAssetsRequest) returns (CanDeleteVersionAssetsResponse)` – validates whether version-scoped assets are purge-eligible.
- `BeginPurgeVersionAssets(BeginPurgeVersionAssetsRequest) returns (BeginPurgeVersionAssetsResponse)` – CAS-guarded purge start that atomically re-checks deletion eligibility and transitions `version_asset_artifact` into purge-in-progress state.
- `FinalizePurgeVersionAssets(FinalizePurgeVersionAssetsRequest) returns (FinalizePurgeVersionAssetsResponse)` – CAS-guarded purge completion that transitions purge-in-progress artifacts to `PURGED` after byte-deletion confirmation.

These gRPC entries are the discoverability index for the control-plane contracts described in [Game Templates and Configuration Tools](game-templates.md) and related architecture docs. When request/response schemas evolve, the proto contract and those architecture sections must be updated in the same change so launch-resolution and template-phase semantics do not drift.

```bash
grpcurl -plaintext localhost:6565 game_design.v1.GameDesignService/Ping
```

### Saga Participation

Publishing a game version is coordinated using the Saga utilities from `firemud-common`. The `VersionServiceImpl` builds a workflow that first persists the new version metadata and then asks downstream services to finalize their versioned data for that `version_id`. If any step fails, previously executed actions are compensated so the database remains consistent. See the [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md) document for the overall flow.

## Local Development Notes

`TestDataSeeder` populates a demo game, template, revision and version when the `dev` Spring profile is active. Run `services/game-design-service/smoke-test.sh` to verify both REST and gRPC endpoints. Cross-service integration tests live under `src/test/java/crossservice` and can be executed once dependent services are available.
