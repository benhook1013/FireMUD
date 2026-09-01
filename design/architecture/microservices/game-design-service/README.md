# Game Design Service

## Overview

Offers tools for building worlds, items, actions, and events that make up each game. Used by creators to design content without touching the underlying code. It maintains version metadata, configuration manifests, and templates so new game instances can be created with predefined rules. Template creation does not provision administrator accounts; any future bootstrap flow must use Account-owned account, membership, and grant operations.

This service is used only at design time. Runtime clients never request logos, favicons, or themes from it. **Target state:** published assets are served from object storage via manifest files; the current private object-store URLs are not public client delivery, so approved public delivery remains deferred.

Authoritative read surfaces worth wiring into creator/operator tooling:

- script patch tenant-readiness and rollout visibility defined in [Scripting Control Plane API](../../system-architecture-scripting-control-plane-api.md)
- plugin version publication status, signer-policy convergence, and status-change events defined in [modding-framework.md](./modding-framework.md)
- template resolution and launch-default semantics defined in [game-templates.md](./game-templates.md)

## Implementation Status

Current capability and proof gaps remain: Game Session's exact pin/epoch persistence and convergence, plus its append-only rollout-history append/readback, are partial and unproved; Game Design plugin publication currently uses the signed-only intake path and exposes one allowlisted `signerKeyId` rather than the target complete `verifiedSignatures[]` set. The ADR 0111 operator-permitted unsigned provenance path remains unimplemented. Draft commit coordination, owner-local compare-and-swap, and the synchronized read fence in [ADR 0129](../../decisions/adr-0129-durable-fenced-multi-owner-draft-commits.md) are also target-state and unproved. The official-hosted creator mutation gate in [ADR 0180](../../decisions/adr-0180-account-owned-hosted-terms-acceptance-gate.md) and changed-term continuity in [ADR 0181](../../decisions/adr-0181-changed-hosted-terms-decline-and-existing-content-continuity.md) are also target-only: no Account evidence check, stale-write classification, or durable tenant/evidence binding is implemented or proved. The target 25 MiB file limit, 2 GiB tenant quota, and streaming/chunked transfer for asset uploads are not implemented or proved, because `GameAssetServiceImpl` uses `MultipartFile.getBytes()` without matching limits in `AssetStoreProperties`; upload admission and resource-safety readiness remain blocked. The service exposes only the creator `POST /assets` path, with no public asset `GET`/download route. Publish/export later writes version-prefix objects and a manifest through the private object-store endpoint; target private-candidate/content-addressed publication and approved public `/assets/**` delivery remain unimplemented. Current asset repair, lifecycle CAS, and release-attestation gaps are recorded in [Asset Storage](./asset-storage.md), and the following target-state script-patch and release-bundle descriptions are not current implementation proof. See the [Game Session runtime and tick coordination tracker](../../../project-management/implementation-tracking/game-session-runtime-and-tick-coordination.md#capability-status), [modding-framework.md](./modding-framework.md), [ADR 0111](../../decisions/adr-0111-unified-dsl-with-distinct-embedded-script-and-plugin-lifecycles.md), and [ADR 0128](../../decisions/adr-0128-game-design-plugin-trust-provenance.md).

## Responsibilities

- Provide REST/gRPC tools and a web UI for editing game assets.
- Manage version metadata and publish immutable game configurations
- Track revision history for rollback
- Orchestrate cross-service publish workflows and notify downstream services when new versions are available.
- Accept branding uploads into the current Game Design persistence boundary and, during publish, export a `manifest.json` and version-scoped objects. No public asset `GET`/download route exists; the target private-candidate/content-addressed export and approved public `/assets/**` delivery let runtime clients load themes and logos without calling this service.
- **Current hosted-route consequence:** the Gateway's coarse `/api/design/**` route currently forwards `/api/design/assets` through `StripPrefix=2` to the live `POST /assets` controller. Because Game Design has no Account hosted-terms/currentness gate, official-hosted asset-upload readiness remains blocked until Gateway denies the route or the exact Account-owned gate is implemented and proved.
- Act as the sole owner of game asset publishing to the S3-compatible object store; **target state:** downstream services and clients read published assets via approved configured delivery URLs and do not write directly to the asset store. In the current first slice, private MinIO/object-store URLs are not public client delivery. Logical world and entity templates remain in PostgreSQL schemas owned by World Management, Entity Management, and related domain services and are not stored as blobs in the asset store.
- When publish workflows need to expose derived domain artifacts outside their owning service storage (for example world navmesh/path graph bundles), Game Design remains the sole object-store writer. Owning domain services hand those artifacts to the publish workflow as explicit inputs; they do not bypass the publish workflow with direct object-store writes.
- **Target state:** Runtime discovery of derived artifacts uses the version `manifest.json` attested by `published_release_bundle`. The target canonical attestation shape includes `artifactDigests[]`, `requiredManifestAssetKeys[]`, and `manifestHash`; the current persisted bundle is narrower and lacks those complete target fields, so consumers must not treat current output as complete release proof or depend on undocumented bucket key conventions.
- Own version lifecycle state and CAS epoch metadata (`versionState`, `versionStateEpoch`) and expose control-plane APIs; target activation/retirement-safe transition guards remain unimplemented, as recorded in the implementation status.
- Expose control-plane integrity APIs such as `GetDesignControlPlaneDigest` and `CanDeleteVersionAssets` used by publish gating and asset-retention workflows.
- **Target state:** Expose CAS-guarded asset purge APIs (`BeginPurgeVersionAssets`, `FinalizePurgeVersionAssets`) so purge eligibility re-check and artifact-state transitions are race-safe. The current repositories update by row ID after service-memory epoch checks without database epoch predicates or affected-row proof; current lifecycle APIs are not race-safe.
- Expose a deterministic launch-resolution API or equivalent control-plane workflow that produces an immutable resolved launch descriptor before instance creation begins. Launch resolution must be keyed by `controlPlaneRequestId` so retries of the same launch attempt return the same descriptor values, and caller-supplied runtime overrides may only fill fields the template leaves unset. If the template already supplies a default, any caller-supplied value for that field must fail deterministically.

## Architecture / Design Notes

- Provides REST/gRPC APIs for editing game data and managing version metadata.
- Coordinates with World Management, Entity Management, Game Logic, and Automation & Scripting Service to apply changes.
- Stores version descriptors and manifests so new game instances can be generated from templates.
- Maintains history of revisions so designers can roll back to prior versions.
- **Target state:** Every full-version publish starts the durable Temporal `publish` workflow described in [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md) and [Transaction Strategies](../../system-architecture-transactions.md). The durable orchestration persists version metadata, coordinates participant finalization/attestation, and exposes workflow runtime metadata through the canonical Game Design control-plane read surfaces. **Current implementation:** when Temporal is disabled, the synchronous fallback remains the active publish path and does not establish the required durability contract; when Temporal is enabled, the Automation & Scripting digest participant currently returns `PERMISSION_DENIED`, so that path is blocked and is not proof of a successful publish or runtime readiness.
- Plugin publication is narrower than a full game-version publish workflow. `UploadPluginBundle` and `PublishPluginVersion` are design-time Game Design workflows that validate and persist immutable plugin-version metadata; they do not repin running games and do not require the cross-service runtime cutover/orchestration used by `PublishVersion`. Cross-service runtime effects begin later, when Logging & Admin invokes instance-scoped activation against Automation & Scripting.
- Design assets are stored per `tenantId` so multiple games can coexist in the
  same database schema. Queries and version publishing workflows enforce this
  tenant filter. See [Multi-Tenancy](../../system-architecture-multi-tenancy.md).
- All control-plane (design-time) gRPC APIs require JWT authentication. REST authentication is supported. Runtime gameplay services do not call the Game Design Service during ticks; they load published templates and manifests from the owning domain services and object storage and authenticate internal runtime traffic using mutual TLS plus propagated `SessionContext` rather than per-request JWT parsing.
  Tokens are parsed by a shared `AuthTokenInterceptor` configured in `GrpcConfig`, which stores claims in `SessionContext` for role checks. Service-to-service traffic uses mutual TLS certificates managed by cert-manager as described in the [Security Architecture](../../system-architecture-security.md).
- Utilizes the [Shared Libraries](../../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.

### Script Patch Lifecycle and Runtime Coordination

The Game Design Service owns the **authoring** view of script patches, while the Automation & Scripting Service owns the **runtime** lifecycle of those patches per tenant. The detailed validation, schedule-identity compilation, and ability-schema-digest steps below are target state; the current `PublishScriptPatchVersion` path creates the patch version, runs the available participant digest gates, notifies Automation, and publishes without proving those script-graph, schedule, or immutable ability-schema computations. The current notification sends an empty `affectedScripts` list; Automation returns before creating readiness state or starting Temporal tracking for that input, so ordinary patch publication does not currently establish a readiness candidate.

- When `PublishScriptPatchVersion` is called, the Game Design Service:
  - **Target state:** Validates and persists the new script graphs, bindings, and metadata, including cross-asset compatibility checks against the pinned `baseVersionId` (for example, ensuring referenced ability identifiers exist and match the ability schema for that base version).
  - **Target state:** Compiles stable runtime identities needed for reconciliation, including a `scheduleDefinitionId` for each logical timer/interval definition so Automation & Scripting can preserve or tombstone schedules deterministically across patch changes.
  - **Target state:** Computes or reads an immutable `abilitySchemaDigest` for the pinned `baseVersionId` and records it with the patch metadata used for runtime validation.
  - **Target state:** Notifies Automation & Scripting of the published patch so it can ingest the compiled definitions and bindings for the target `<tenantId, scriptPatchVersion>` and, for an eligible non-empty handler notification, start or reuse the durable Temporal `script-patch-readiness` workflow. Target state permits a verifiable immutable manifest with zero `onLoad` handlers to instead create the terminal `READY` pin-readiness record with its pinned manifest reference/digest and zero declared and terminal handler counts, without a Temporal workflow or synthetic handler work. The current publication notification sends only `affectedScripts`, and current status responses have no manifest-proof or declared/terminal-count fields, so the current wire/runtime cannot supply or expose this proof; an empty current notification does not create readiness.
  - Treats the publish as **asynchronous** from a runtime perspective: the version is recorded as published in design-time tables, but its readiness for execution is determined by the Automation & Scripting Service.
- **Target state:** For each `<tenantId, scriptPatchVersion>` with an eligible `NotifyScriptVersionUpdate` notification, including a valid affected-script set (or a manifest-proven zero-handler set), the Automation & Scripting Service tracks a tenant readiness lifecycle (`PENDING_VALIDATION`, `ONLOAD_RUNNING`, `READY`, `FAILED`, and terminal `SUPERSEDED`) as described in `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md#script-patch-lifecycle`. An empty `affectedScripts` list without immutable manifest proof cannot establish `READY`: for an identifiable tenant/patch notification, Automation records durable `FAILED` readiness with bounded reason `zero_handler_manifest_unverifiable` and status/audit evidence; an unidentifiable malformed notification may fail transport validation without fabricating a readiness identity. `SUPERSEDED` applies only to a displaced, unpinned record in `PENDING_VALIDATION` or `ONLOAD_RUNNING`; an already-pinned `READY` record is not relabeled `SUPERSEDED` merely because a newer publish arrives. Already-admitted work remains governed by its captured Game Session `(scriptPatchVersion, scriptPinEpoch)` tuple and normal persistence, handoff, and execution fences until explicit repin or rollback, or fence rejection.
- **Current implementation status:** `PublishScriptPatchVersion` accepts the caller-supplied `baseVersionId` without proving same-tenant ownership, an eligible published state, or release evidence. `ResolveLaunchDescriptor` and Game Session `StartSession` also do not currently prove that a selected patch is published for and targets the resolved base version, or that Automation reports `READY`; a full-version template/request can therefore persist an arbitrary patch identity on the descriptor and instance. `GetPublishedScriptPatchVersion` and its patch digest lookup select the latest script-only row without requiring `PUBLISHED`, so these reads are not yet fail-closed publication authorities. These are implementation gaps, not target behavior.
- **Target state:** The Game Design Service queries readiness via a read-only API such as `GetScriptPatchStatus(tenantId, scriptPatchVersion)` and consumes the owner-provided rollout read for creator visibility so that UIs can show:
  - That a patch is published but still **pending runtime validation**.
  - The patch's `baseVersionId` and `abilitySchemaDigest` used for runtime compatibility gates and pinning checks.
  - Whether `onLoad` initialization has succeeded or failed for each tenant.
  - **Target state:** When Game Session has committed a patch pin, rollback, or repin for a specific game instance, Game Session owns the exact pin epoch and append-only rollout history. Game Design consumes the owner-provided read and does not reconstruct that history from readiness or notification arrival order.
  - **Target state:** Event-family responsibilities are explicit:
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
- **Target state:** Plugin publication persists a canonical manifest contract that includes at least `pluginId`, `pluginVersionId`, exact `baseVersionId`, exact `abilitySchemaDigest`, declared entrypoints, and declared bindings. Runtime services consume those immutable fields as the activation source of truth; provenance-specific publication requirements remain owned by [modding-framework.md](./modding-framework.md).
- Plugin versions use a separate Game Design lifecycle from runtime activation. Publication status answers whether a bundle is accepted into immutable authoring history; instance activation status answers whether a published plugin version is active for a given running game instance.

### Script-transition ownership consequence

Game Design owns authored script-patch revisions, immutable publication metadata, base-version compatibility, and plugin bundle publication. It does not select the active patch for a running instance, issue a script pin epoch, or author rollout/rollback history. A published patch is only a candidate for Automation tenant readiness; creators see runtime readiness separately from the Game Session-owned instance pin. Plugin publication similarly remains distinct from instance activation: linked plugins use the shared DSL and sandbox, but retain their independent `pluginId`/`pluginVersionId` publication and activation identity. See [ADR 0109](../../decisions/adr-0109-game-session-owned-script-rollout-history.md), [ADR 0110](../../decisions/adr-0110-explicit-opt-in-schedule-continuity-across-script-transitions.md), [ADR 0111](../../decisions/adr-0111-unified-dsl-with-distinct-embedded-script-and-plugin-lifecycles.md), and the canonical scripting control-plane contracts.

### Canonical Authoring Boundary

**Target state:** First-party game authoring uses the Game Design Service's revision and version model, not a filesystem project format. Designers edit through the web UI and service-owned typed APIs; Game Design persists revisions, applies them to domain-owned Draft templates, and publishes immutable versions or script-only patches from that canonical history. AI-assisted and other external tools use explicitly public creator APIs or purpose-specific batch APIs and receive the same proposal, concurrency, owner-validation, and review semantics as ordinary creator tools; they do not write databases, object storage, or runtime state directly. See [ADR 0126](../../decisions/adr-0126-untrusted-models-and-scoped-authoring-tools.md) and [ADR 0129](../../decisions/adr-0129-durable-fenced-multi-owner-draft-commits.md).

Current `SaveRevision` does not yet provide ADR 0129 durable coordination, owner-local CAS, or a synchronized read fence; see [Game Design API implementation status](api-contracts.md#implementation-status) for the verified current ordering and gap.

Implementations must not introduce ad hoc import/export, Git checkout, or local package semantics for first-party content. Any future external authoring package must be specified as a separate contract before it is exposed, including stable ID preservation, cross-service reference validation, asset inclusion, plugin inclusion, conflict handling, and mapping back into Game Design revisions and commits. Until that contract exists, current first-party version-control integration is limited to Game Design's revision/version rows and publish metadata; durable branches, commits, and their audit surfaces remain target-state. A future external repository or tool integration, if approved as a separate contract, is one-way ingestion through the public revision APIs; it must not become a synchronization hook, write-back path, or second content authority. See [Version Control for Design Assets](version-control.md).

Plugin bundles are the only supported file-based content package in the initial slice. They do not replace or extend the first-party revision package format; provenance and publication requirements are owned by [modding-framework.md](./modding-framework.md) and the service-local provenance record [ADR 0128](../../decisions/adr-0128-game-design-plugin-trust-provenance.md), under canonical [ADR 0111](../../decisions/adr-0111-unified-dsl-with-distinct-embedded-script-and-plugin-lifecycles.md).

### Official Hosted Creator Mutation Gate

For official hosted deployments, Game Design owns creator content and is the fail-closed mutation boundary. Before the first persisted creator content, including Drafts, and before each later creator-intent content-bearing write or publish, it must consume the current Account authority described in [Account-Owned Hosted Terms and Creator Party](../account-service/api-contracts.md#account-owned-hosted-terms-and-creator-party) under [ADR 0180](../../decisions/adr-0180-account-owned-hosted-terms-acceptance-gate.md). The acting account's tenant authorization remains a separate check; Gateway/UI checks cannot authorize the write.

Game Design records only the local committed evidence-binding tuple `{tenantId, creatorPartyId, acceptanceEvidenceId, generation}` for a covered operation, where `generation` is Account's material-acceptance generation. That exact Account authority must remain valid through Game Design's commit linearization point; a preflight read alone is insufficient, and commit-bound authority/fence or equivalent validation is required without selecting its mechanism. Missing, stale, mismatched, ambiguous, unavailable, or uncertain authority fails closed by aborting the local transaction before any creator mutation or staged artifact becomes authoritative or externally reachable; staged bytes are cleanup/quarantine state, not success. Exact durably committed retries replay their stored result and local evidence. An uncertain or not-durably-committed attempt first reconciles the original stable identity and obtains durable no-commit proof; it is not blindly redispatched or mutated while indeterminate. Only a permitted fresh attempt performs a fresh commit-bound currentness check. Reusing the same identity with changed normalized payload returns the target application outcome `IDEMPOTENCY_CONFLICT` before mutation. Game Design does not own a terms/party catalog, infer signer or materiality, or accept on behalf of a party.

Changed-term stale acceptance, continuity, reads/downloads, and narrowly bounded lifecycle-reducing operations remain separate target-only classifications under [ADR 0181](../../decisions/adr-0181-changed-hosted-terms-decline-and-existing-content-continuity.md). No current route, schema, storage binding, UI/status surface, or focused proof implements these gate semantics, and this document selects no future route or schema.

## Key Features

- Web-based visual interface for worlds, items, actions, and scripts.
- World and room editors.
- [Ability & Action Design Tools](ability-action-tools.md)
- Scripting and event workflow creation.
- Visual editor for building scripts in the same component-based DSL used by the
  Automation & Scripting Service.
- [Game templates](game-templates.md) with predefined rulesets. Administrator provisioning is not a Game Design template capability; any future bootstrap flow must use Account-owned account, membership, and grant operations.
- Patch note management for published games.
- Supports script-only patch versions that reference a `baseVersionId` and
  generate a new `scriptPatchVersion` without requiring a full publish.
- Supports plugin bundle publication as immutable design-time artifacts keyed by `pluginId` and `pluginVersionId`. **Target state:** publication pins the exact `baseVersionId` and dedicated `abilitySchemaDigest` for later instance-scoped activation; the current compatibility path uses the Automation & Scripting aggregate digest instead, so that ability-schema proof is not established.
- Does not track individual script definitions at runtime; only the patch
  version metadata is recorded. Runtime services manage the active script
  registry and are notified when a patch version is published.
- [Item & Equipment Balancing Tools](item-equipment-balancing.md)
- Database-backed revision and version metadata/history for design assets; branch/commit history is target-only and no current branch/commit APIs are exposed.
- No whole-game import/export, round-trip filesystem project, or external Git-synchronization surface.
- In-game modding and plugin framework for runtime customization.

### Data Model

- `game` table defines the project and scopes it by `tenant_id`; the current schema has no `owner_id` account foreign key.
- `revision` table stores individual asset changes with author metadata and a required `version_id` scope after V17.
- `version` table stores lifecycle and publish metadata (`version_number`, `base_version_id`, `script_patch_version`, `is_script_only`, and `notes`). The legacy `revision_ids` array was removed by V5; V17 backfilled existing revisions to each tenant's latest version, so immutable revision/version lineage is not currently proven. Target immutable snapshot and branch/commit lineage remains owned by [Version Control](version-control.md) and the [versioning implementation-status record](../../system-architecture-versioning-runtime.md).
- `game_templates` table stores predefined configuration templates for new games.
- [`runtime_flag` table](feature-flags.md) manages feature flag definitions and
  corresponding APIs expose these records.
- PostgreSQL `game_assets.data` is the current canonical byte source of truth for uploaded assets such as icons or sound effects. Object storage is a later version-scoped export/publication destination, not the current authoring source; the target metadata-only model retains an equivalent immutable repair source before removing those bytes from PostgreSQL.
- **Target state:** Plugin bundle metadata must be persisted as indexed design-time records keyed by `(tenantId, pluginId, pluginVersionId)` and include manifest fields, complete verified signature-set metadata as defined by [Signing and Key Lifecycle](modding-framework.md#signing-and-key-lifecycle-required), publication status, validation outcomes, `bundleDigest`, and plugin asset distribution manifest fields when `assetRefs[]` are present. The bundle bytes remain in object storage, but plugin activation metadata must be queryable without unpacking archives on routine reads.

Design-time tables (such as `revision`, `version`, `game_templates`,
`runtime_flag`, asset metadata tables, and release-attestation tables) are the
source of truth for world and entity history. Domain services (World Management,
Entity Management, etc.) store the versioned templates they consume at runtime,
but commit and revision history remains anchored in the Game Design Service.

The Game Design Service must also persist an immutable `published_release_bundle` record per `(tenantId, versionId)` after publish gates and asset export succeed. This record is the canonical release attestation consumed by activation, rollback-preflight, and repair tooling. **Target state:** It contains the publish workflow identity, target `commitId`, required participant digests, `manifestHash`, and `generationConfigRevision` for that release. **Current status:** The persisted row and response do not yet include target `commitId`, `manifestSchemaVersion`, or complete `artifactDigests[]`; current schema/validator status is recorded in the [API contract implementation status](api-contracts.md#implementation-status) and [version-control digest contract](version-control.md#digest-participants-by-publish-type). **Target state (currently unproved):** The record also contains the dedicated Game Logic-owned `abilitySchemaDigest`.

**Target state:** For releases that export derived artifacts outside participant-owned databases, `published_release_bundle` must also include `artifactDigests[]` entries for each exported artifact family. For world bundles such as navmesh/path graph payloads, these entries are mandatory rather than optional metadata. The current release bundle does not persist this complete field.

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
  - Logging & Admin Service may receive a non-authoritative operator-audit projection for actions entering its ingress; Game Design retains the authoritative publishing and domain-audit records.
- **External:** PostgreSQL for design metadata and current asset bytes; object storage for later version-scoped asset export/publication.

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
