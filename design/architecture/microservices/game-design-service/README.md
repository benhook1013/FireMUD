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
- Own version lifecycle state and CAS epoch metadata (`versionState`, `versionStateEpoch`) and expose control-plane APIs for activation/retirement-safe transitions.
- Expose control-plane integrity APIs such as `GetDesignControlPlaneDigest` and `CanDeleteVersionAssets` used by publish gating and asset-retention workflows.
- Expose CAS-guarded asset purge APIs (`BeginPurgeVersionAssets`, `FinalizePurgeVersionAssets`) so purge eligibility re-check and artifact-state transitions are race-safe.
- Expose a deterministic launch-resolution API or equivalent control-plane workflow that produces an immutable resolved launch descriptor before instance creation begins.

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
- Does not track individual script definitions at runtime; only the patch
  version metadata is recorded. Runtime services manage the active script
  registry and are notified when a patch version is published.
- [Item & Equipment Balancing Tools](item-equipment-balancing.md)
- Import/export of design assets for sharing between game worlds.
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
- `PublishScriptPatchVersion` – creates a script-only patch version referencing a base version.
- `ListVersions` – enumerates published versions for selection when creating a
  game instance.
- `GetVersionState` / `CompareAndSetVersionState` – authoritative control-plane version lifecycle reads and CAS transitions.
- `GetDesignControlPlaneDigest` – digest surface for publish gating over normalized metadata.
- `GetPublishedReleaseBundle` – authoritative read surface for immutable release attestation used by activation, cutover preflight, and repair workflows.
- `CanDeleteVersionAssets` – deletion-eligibility oracle for version-scoped asset prefixes.

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

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
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
- `GetPublishedReleaseBundle(GetPublishedReleaseBundleRequest) returns (GetPublishedReleaseBundleResponse)` – returns the immutable `(tenantId, versionId)` release attestation including participant digests, `manifestHash`, and `generationConfigRevision`.
- `CanDeleteVersionAssets(CanDeleteVersionAssetsRequest) returns (CanDeleteVersionAssetsResponse)` – validates whether version-scoped assets are purge-eligible.
- `BeginPurgeVersionAssets(BeginPurgeVersionAssetsRequest) returns (BeginPurgeVersionAssetsResponse)` – CAS-guarded purge start that atomically re-checks deletion eligibility and transitions `version_asset_artifact` into purge-in-progress state.
- `FinalizePurgeVersionAssets(FinalizePurgeVersionAssetsRequest) returns (FinalizePurgeVersionAssetsResponse)` – CAS-guarded purge completion that transitions purge-in-progress artifacts to `PURGED` after byte-deletion confirmation.

```bash
grpcurl -plaintext localhost:6565 game_design.v1.GameDesignService/Ping
```

### Saga Participation

Publishing a game version is coordinated using the Saga utilities from `firemud-common`. The `VersionServiceImpl` builds a workflow that first persists the new version metadata and then asks downstream services to finalize their versioned data for that `version_id`. If any step fails, previously executed actions are compensated so the database remains consistent. See the [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md) document for the overall flow.

## Local Development Notes

`TestDataSeeder` populates a demo game, template, revision and version when the `dev` Spring profile is active. Run `services/game-design-service/smoke-test.sh` to verify both REST and gRPC endpoints. Cross-service integration tests live under `src/test/java/crossservice` and can be executed once dependent services are available.
