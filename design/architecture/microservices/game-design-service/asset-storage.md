# Asset Storage Setup

Game assets such as icons or sound files are uploaded through the Game Design
Service at design time. In the current first implementation slice, ordinary uploaded asset bytes are persisted in the Game Design database as `game_assets.data`; that row is the immutable repair source for ordinary binary assets referenced by a Published/Active release. Published asset bytes live in object storage alongside the version manifest. A future metadata-only draft asset model may move draft bytes out of PostgreSQL, but it must first introduce an equivalent immutable repair source, such as a retained object version plus content digest, before removing `game_assets.data` as the exact-bytes repair source. When a version is published, the service uploads or promotes these assets to
tenant- and version-scoped object storage (e.g., S3, MinIO, or a CDN) and
generates a `manifest.json` that maps asset keys to public URLs. A manifest is
produced for every published version, even if no assets are present. The manifest is
stored alongside the assets and its URL is recorded in the published version
metadata so runtime clients can retrieve it. Each manifest includes an explicit
`schemaVersion` field so clients and tooling can distinguish between manifest
formats over time. The Game Design Service is not queried during gameplay. Each
record remains tied to a `tenantId` so icons, UI images, and audio files are
isolated per game.

## Identifier Implementation Status

- **Target (ADR 0020):** `tenantId` is the canonical opaque UUID logical identity. `game_assets.tenant_id` and the version-asset mappings use that tenant scope; numeric database keys remain private implementation details and do not replace it.
- **Current first slice:** `game_assets.tenant_id` is a `VARCHAR(36)` accepted through the REST `tenantId` string field without UUID-shape enforcement, while Account Service still exposes numeric `Long` tenant identifiers across current REST, gRPC, and persistence seams. No authoritative numeric-to-UUID tenant mapping exists. The asset row is separately keyed by `BIGSERIAL`, and `GameAssetDto.id` exposes that numeric asset-row key; neither number is the target tenant identity.
- **Migration status:** Account and downstream public/cross-service tenant contracts must converge together on the UUID logical identity, after which Game Design validates and stores that value. Until then, current caller-supplied tenant strings are implementation drift, not a second canonical tenant identity, and implementations must not invent a reversible numeric-to-UUID encoding.

Logical world and entity templates (regions, rooms, items, NPCs, loot tables, scripts, etc.) remain stored in PostgreSQL schemas owned by the corresponding domain services and are not persisted as blobs in the asset store. The asset store is strictly for binary design assets plus version-scoped manifests exported by the Game Design Service.

Derived runtime-consumed artifacts produced by domain services follow the same writer rule:

- Domain services may own the semantics and generation of derived artifacts such as navmesh/path graph bundles.
- If those artifacts are exported to the shared object store for runtime consumption, Game Design publishes them on behalf of the owning service as part of the version publish workflow.
- Domain services must not write directly to the shared object store used for published version assets. A direct domain-service object-store write would bypass the artifact lifecycle, release attestation, and purge controls defined here.

Canonical producer-to-publisher handoff for derived artifacts:

- For the first implementation slice, a producer service that owns a derived runtime artifact must persist a version-scoped artifact record in its own database keyed by `(tenantId, versionId, artifactKind)`.
- That producer-owned record is the canonical pre-publish handoff surface to Game Design and must include at minimum:
  - a stable fetch handle or byte source controlled by the producer service;
  - `contentHash`;
  - `contentType`;
  - `artifactKind`;
  - `producerService`;
  - a producer-local lifecycle/status field proving the artifact bytes are finalized for publish.
- Game Design must obtain derived artifact bytes and metadata through a typed producer-service API backed by that persisted record. Ad hoc filesystem sharing, direct producer writes into the published asset bucket, and convention-based object-key pickup are not allowed.
- The durable publish workflow order is: producer materializes and freezes the derived artifact in its own ownership boundary, then Game Design exports those exact bytes into the published version prefix, then attestation records the resulting `manifestHash`.
- Exact-bytes repair for a Published/Active version must re-read the same producer-owned artifact contract or an equivalent immutable repair source capable of reproducing the attested bytes. If the producer can no longer supply the attested bytes, recovery requires publishing a new `versionId`.

Illustrative producer API for derived artifacts:

- Producer services should expose a typed API such as `GetPublishableDerivedArtifact(tenantId, versionId, artifactKind)`.
- Minimum response contract:
  - `status` (`READY`, `NOT_READY`, `FAILED`);
  - immutable fetch handle or byte-stream reference controlled by the producer;
  - `contentHash`;
  - `contentType`;
  - `artifactKind`;
  - `producerService`;
  - producer-local finalized timestamp or equivalent evidence that publishable bytes are frozen.
- `READY` means the producer has durably recorded the artifact and guarantees the referenced bytes can be fetched for publish or exact-bytes repair.
- `NOT_READY` means publish must fail or wait before `ExportAssets`; Game Design must not export placeholder bytes.
- `FAILED` means the producer could not materialize a publishable artifact and must return structured failure details suitable for publish-workflow diagnostics.

Illustrative `GetPublishableDerivedArtifact` fragments:

- `NOT_READY`:

```json
{
  "tenantId": "7b3b074e-d597-4e9b-b96f-4f5946d26120",
  "versionId": "v42",
  "artifactKind": "NAVMESH",
  "status": "NOT_READY",
  "error": {
    "code": "DERIVED_ARTIFACT_NOT_FINALIZED",
    "message": "Navmesh generation has not produced finalized bytes for tenantId=7b3b074e-d597-4e9b-b96f-4f5946d26120 versionId=v42."
  }
}
```

- `FAILED`:

```json
{
  "tenantId": "7b3b074e-d597-4e9b-b96f-4f5946d26120",
  "versionId": "v42",
  "artifactKind": "NAVMESH",
  "status": "FAILED",
  "error": {
    "code": "DERIVED_ARTIFACT_BUILD_FAILED",
    "message": "Navmesh generation failed validation and no publishable artifact was recorded.",
    "details": {
      "producerService": "world-management-service",
      "failureId": "navmesh-build-7f3c"
    }
  }
}
```

Initial-slice discovery rule for derived world artifacts:

- For the first implementation slice, exported world navmesh/path graph artifacts must be discoverable through the same attested release surfaces as other version assets.
- For the first implementation slice, Game Design must publish a `manifest.json` entry keyed by a stable usage name for each exported world navmesh/path graph artifact.
- `GetPublishedReleaseBundle(tenantId, versionId)` is the canonical attestation surface. In the initial slice it must expose both `artifactDigests[]` for exported derived-world-artifact bytes and `requiredManifestAssetKeys[]` for launch-required manifest usage keys, together with the attested `manifestHash`.
- The attested release contract must also declare which stable usage keys are required for launch of that specific release. Manifest integrity alone is not sufficient to infer whether an omitted key is valid or a launch-blocking defect.
- Runtime consumers must treat those attested references as canonical and must not construct object-store paths by convention.

Required-artifact attestation contract:

- `GetPublishedReleaseBundle(tenantId, versionId)` must expose an attested field named `requiredManifestAssetKeys[]`.
- For the first implementation slice, the field lists stable manifest usage keys that are required for launch or cutover validation of that release.
- `requiredManifestAssetKeys[]` may be empty for releases that do not require derived runtime artifacts.
- If `requiredManifestAssetKeys[]` contains `world.navmesh` or `world.pathGraph`, runtime launch and cutover tooling must require the corresponding `manifest.json` entry to exist and match the attested release metadata.
- Consumers must not infer requiredness from filename conventions, producer type, or the mere presence or absence of an entry in `manifest.json`.

Illustrative `GetPublishedReleaseBundle` fragment:

```json
{
  "tenantId": "7b3b074e-d597-4e9b-b96f-4f5946d26120",
  "versionId": "v42",
  "manifestHash": "sha256:2d4b2e...",
  "artifactDigests": [
    {
      "artifactType": "WORLD_NAVMESH_BUNDLE",
      "artifactPath": "versions/v42/world/navmesh.bundle",
      "artifactDigest": "sha256:8fd0c4...",
      "artifactSchemaVersion": 1
    },
    {
      "artifactType": "WORLD_PATH_GRAPH_BUNDLE",
      "artifactPath": "versions/v42/world/path-graph.bundle",
      "artifactDigest": "sha256:91baf2...",
      "artifactSchemaVersion": 1
    }
  ],
  "requiredManifestAssetKeys": ["world.navmesh", "world.pathGraph"],
  "assetContentHashes": {
    "world.navmesh": "sha256:8fd0c4...",
    "world.pathGraph": "sha256:91baf2..."
  }
}
```

Initial-slice manifest shape for derived world artifacts:

- The required stable usage keys are `world.navmesh` and `world.pathGraph`.
- For manifest `schemaVersion: 1`, these derived-world-artifact entries must appear under the top-level `assets` object.
- Future manifest schema versions may extend the manifest shape, but they must either preserve these keys under `assets` or publish an explicit schema-version migration note before changing their location.
- If a published version exports only one of those artifacts, the manifest may omit the other key.
- Each exported derived-world-artifact entry must include at least:
  - `url` – runtime fetch location under the published version prefix;
  - `contentHash` – immutable digest of the artifact bytes;
  - `contentType` – media type for the artifact payload;
  - `artifactKind` – one of `NAVMESH` or `PATH_GRAPH`;
  - `producerService` – `world-management-service`;
  - `versionId` – the attested published version owning the artifact.
- Runtime consumers must bind to the artifact by these stable usage keys rather than by filename conventions.
- Runtime consumers for the initial slice must treat `schemaVersion: 1` plus the top-level `assets` object as the canonical discovery contract for these keys.

Illustrative `manifest.json` fragment:

```json
{
  "schemaVersion": 1,
  "assets": {
    "world.navmesh": {
      "artifactKind": "NAVMESH",
      "contentType": "application/octet-stream",
      "contentHash": "sha256:8fd0c4...",
      "producerService": "world-management-service",
      "versionId": "v42",
      "url": "https://cdn.example.invalid/t1/v42/world/navmesh.bin"
    },
    "world.pathGraph": {
      "artifactKind": "PATH_GRAPH",
      "contentType": "application/json",
      "contentHash": "sha256:91baf2...",
      "producerService": "world-management-service",
      "versionId": "v42",
      "url": "https://cdn.example.invalid/t1/v42/world/path-graph.json"
    }
  }
}
```

Negative consumer examples:

- Unsupported manifest schema version:

```json
{
  "error": {
    "code": "UNSUPPORTED_MANIFEST_SCHEMA_VERSION",
    "message": "manifest schemaVersion=2 is not supported by this runtime consumer; launch must fail closed until the consumer understands that schema."
  }
}
```

- Missing required derived-world-artifact entry for a release that expects it:

```json
{
  "error": {
    "code": "REQUIRED_RELEASE_ARTIFACT_MISSING",
    "message": "Expected manifest.assets[\"world.navmesh\"] for this release, but no attested entry was present."
  }
}
```

Fail-closed reader rule:

- If a runtime consumer does not understand the manifest `schemaVersion`, or if a required derived-world-artifact key is missing for the release it is trying to start, launch must fail before gameplay admission rather than guessing fallback paths or object keys.
- Requiredness is determined from the attested release bundle metadata for that release, not by heuristics over manifest contents.

## External Delivery Classification

Published asset delivery uses the canonical external `/assets/**` family:

- `/assets/**` is a read-only release-artifact surface, not a creator/control-plane write path.
- The canonical object-store or CDN URL exported in `manifest.json` represents stable published bytes for that release.
- Runtime consumers and clients must resolve published assets through the attested manifest/release metadata rather than inventing bucket paths or treating Game Design upload routes as runtime-read surfaces.
- Any future authenticated or signed-read variant must still preserve `/assets/**` as a delivery family separate from Game Design creator APIs under `/api/design/**`.

## Table Structure

The `game_assets` table stores ordinary design-time upload records. In the current first implementation slice it also stores the uploaded bytes used as the exact-bytes repair source. Columns include:

- `id` – current `BIGSERIAL` row key; it is not the tenant identity and must not be treated as the target public logical identifier
- `tenant_id` – **target:** identifies the owning game using the canonical UUID `tenantId`; **current:** stores an unconstrained `VARCHAR(36)` string while the UUID migration and validation remain incomplete
- `file_name` – original file name
- `content_type` – MIME type
- `data` – immutable uploaded bytes for the ordinary binary asset in the current first slice; this is the canonical repair source for object-store republish/repair until a future metadata-only storage model introduces an equivalent retained immutable source
- `created_at` – upload timestamp

Future metadata-only storage may replace `data` with fields such as `storage_key`, `content_hash`, and `size_bytes`, but only if the new schema preserves the same repair invariant: Published/Active releases must be exactly reproducible for as long as their assets remain non-Retired or design-history reachable.

To associate assets with specific published versions while still allowing reuse across versions, the target Game Design contract requires a separate mapping table. The current implementation has neither this table nor a Draft-version authoring path for its mappings; the following shape and constraints are target-state until both exist:

- `version_asset`:
  - `tenant_id` – owning game
  - `version_id` – published version identifier
  - `asset_id` – foreign key to `game_assets.id`
  - `usage_type` – optional classifier such as `logo`, `icon`, or `audio`
  - `created_at` – mapping creation timestamp

The target combination `(tenant_id, version_id, asset_id)` is unique so the same asset can be referenced by multiple versions without duplicating the binary row. Once the target authoring path exists and a mapping belongs to a version in the Published or Active state described in [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md), the referenced asset must be treated as immutable; replacing the binary requires creating a new `game_assets` row and a new `version_asset` mapping.

Artifact lifecycle state for each exported prefix must be persisted in a dedicated state table:

- `version_asset_artifact`:
  - `tenant_id`
  - `version_id`
  - `exported_version_number` (the frozen version-number prefix used for object-store export and purge finalization)
  - `artifact_state` (`STAGED`, `EXPORTED_UNATTESTED`, `PUBLISHED`, `FAILED`, `TOMBSTONED`, `PURGE_IN_PROGRESS`, `PURGE_FAILED`, `PURGED`)
  - `state_epoch` (monotonic CAS token)
  - `manifest_hash`
  - `last_workflow_id` (publish/repair workflow identity)
  - `last_error_code` / `last_error_message` (nullable; set on failed transitions)
  - `updated_at`

`(tenant_id, version_id)` is unique in `version_asset_artifact`. This enum list is the canonical schema contract for both persistence and API validation. All lifecycle transitions must use compare-and-set on `state_epoch` so concurrent publish/repair/purge workflows cannot race.

An index named `idx_game_assets_tenant` speeds up queries scoped to a tenant.
Additional indexes may support common design-time queries (for example by
`tenant_id` and upload timestamps) but are not required for runtime because
published assets are served from object storage.

## API

In the current first slice, assets are uploaded via `POST /assets` using a `multipart/form-data` request, persisted with bytes in `game_assets.data`, and returned as a `GameAssetDto` with the numeric asset-row `id` and data fields described by the current OpenAPI schema. The target contract streams bytes to object storage and returns metadata plus stable download information instead; that storage and DTO convergence is not complete.
See the [OpenAPI specification](../../../../services/game-design-service/src/main/resources/openapi.yaml) for request details.
Endpoints for downloading or deleting assets are available.
gRPC endpoints support asset management operations.
Listing assets for a tenant is supported.
Control-plane purge APIs are required:

- `CanDeleteVersionAssets(tenantId, versionId)` – read-only eligibility oracle.
- `BeginPurgeVersionAssets(tenantId, versionId, expectedArtifactStateEpoch)` – CAS-guarded purge start.
- `FinalizePurgeVersionAssets(tenantId, versionId, purgeWorkflowId, expectedArtifactStateEpoch)` – CAS-guarded purge completion.
- `GetVersionAssetArtifactState(tenantId, versionId)` – authoritative lifecycle/proof read for the persisted artifact row.
- `RepairPublishedVersionAssets(tenantId, versionId, expectedArtifactStateEpoch, repairWorkflowId)` – exact-bytes repair start for attested releases.
- `GetVersionAssetPurgeStatus(tenantId, versionId, purgeWorkflowId)` – operator-visible workflow status for in-flight or failed purge attempts.

Logging & Admin, CI, and runbooks must consume these control-plane APIs instead of reconstructing state from `version_asset_artifact` table reads plus bucket inspection.

Implementation notes:

- `GetVersionAssetArtifactState`, `RepairPublishedVersionAssets`, `TombstoneVersionAssets`, `CanDeleteVersionAssets`, `BeginPurgeVersionAssets`, `FinalizePurgeVersionAssets`, and `GetVersionAssetPurgeStatus` are now live in `game-design-service`.
- `version_asset_artifact` is now a persisted control-plane row and full-version publish updates it through `EXPORTED_UNATTESTED` and `PUBLISHED`.
- the persisted artifact row now stores the exact exported version number plus manifest asset keys so cleanup, repair, and purge use exported proof rather than current draft-asset listings or mutable version rows.
- `CanDeleteVersionAssets` now also fails closed on live launch-descriptor references and on approved template remap sets that still name the source or target version, so purge cannot silently remove bytes still needed by current launch and replacement-cutover control-plane truth.
- `version_asset_purge_workflow` is now the retained workflow-status surface for purge start/finalization outcomes.

A basic repository (`GameAssetRepository`) and service implementation
(`GameAssetServiceImpl`) persist uploads using Spring Data JPA.

At publish time, the current implementation reads asset bytes from `game_assets.data`, exports them into version-scoped published prefixes in object storage, and references them in the generated `manifest.json`. Runtime clients load branding and theme resources directly from the CDN using this manifest; the Game Design Service is not involved. A future metadata-only storage model may use retained immutable object-store draft keys, but those keys are target-only and are not the current upload or repair source. See [Game Design Service Architecture](README.md) for how these assets fit into published versions.

The `published_release_bundle` attestation must reference the final asset state
for the version by including `manifestHash` (and optionally per-asset
`contentHash` values) exposed through Game Design’s `GetPublishedReleaseBundle`
API. Activation, cutover preflight, and repair tooling must consume the API
instead of reconstructing asset state from `version_asset_artifact` and version
metadata separately.

`published_release_bundle` is persisted in the Game Design Service schema. Game
Design owns the table shape, Flyway migrations, and attestation writes for that
record; other services consume the attestation only through
`GetPublishedReleaseBundle(tenantId, versionId)` and must not treat it as a
shared-schema artifact.

### Interaction with Script-Only Patches

Script-only patches (see `system-architecture-versioning-runtime.md`) do not change assets or any data stored in `game_assets` / `version_asset`. In the target contract, published asset selection is bound to `(tenantId, versionId)` and exported during full `PublishVersion` flows. The current first slice does not yet enforce that binding: `AssetExportServiceImpl` exports every tenant asset and the `version_asset` authoring path is absent. Asset changes therefore belong in a new `versionId` in the target state, while the current tenant-wide export remains an implementation gap to be corrected before version-bound export can be treated as proven.

### Asset Lifecycle and Publish Workflow

The publish workflow uses a dedicated workflow step to export assets and update
manifest metadata:

Artifact lifecycle states for a `(tenantId, versionId)` prefix are explicit:

- `STAGED` – publish attempt has written candidate bytes but version is not yet Published.
- `EXPORTED_UNATTESTED` – candidate bytes and `manifest.json` have been exported and `manifestHash` is known, but the immutable `published_release_bundle` attestation has not yet been committed.
- `PUBLISHED` – publish succeeded, `manifestHash` is attested in `published_release_bundle`, and the immutable bytes for the version are launchable.
- `FAILED` – publish workflow failed for this version.
- `TOMBSTONED` – failed or abandoned artifact is quarantined for diagnostics and excluded from activation paths.
- `PURGE_IN_PROGRESS` – purge workflow has atomically locked this prefix for deletion and is removing object-store bytes.
- `PURGE_FAILED` – purge workflow encountered a deletion/finalization failure; bytes may be partially deleted and require explicit operator retry/resume workflow.

Allowed transitions:

- `STAGED -> EXPORTED_UNATTESTED` on successful `ExportAssets` completion and `manifestHash` computation.
- `EXPORTED_UNATTESTED -> PUBLISHED` only after `published_release_bundle` is written successfully for the same `(tenantId, versionId)` and records the same `manifestHash`.
- `EXPORTED_UNATTESTED -> FAILED` when attestation or later publish completion fails after asset export.
- `STAGED -> FAILED` when publish workflow fails before activation eligibility.
- `FAILED -> STAGED` only through an explicit repair/retry workflow.
- `FAILED -> TOMBSTONED` when operators abandon retry and quarantine bytes.
- `TOMBSTONED -> STAGED` only via explicit operator-approved restore workflow.
- `TOMBSTONED -> PURGE_IN_PROGRESS` only through CAS-guarded `BeginPurgeVersionAssets`.
- `PURGE_IN_PROGRESS -> PURGED` (physical deletion complete) only after deletion workflow success; purge is not an implicit publish compensation action.
- `PURGE_IN_PROGRESS -> PURGE_FAILED` when byte deletion or finalization CAS fails.
- `PURGE_FAILED -> PURGE_IN_PROGRESS` only through explicit retry/resume workflow using a new workflow idempotency key.
- `PURGE_FAILED -> TOMBSTONED` when retry is abandoned and operators choose to keep diagnostic state.

`PURGED` semantics:

- `PURGED` is a retained terminal metadata state in `version_asset_artifact`; the row is not deleted during purge.
- Physical object-store bytes may be deleted, but lifecycle/audit metadata (`artifact_state`, `state_epoch`, `manifest_hash`, `last_workflow_id`, `updated_at`) remains queryable for forensics and race-safe runbook checks.

Transition enforcement contract:

- Every transition is persisted by updating `version_asset_artifact` with CAS on `state_epoch`.
- Failed CAS means another workflow already changed state; callers must reload current state and re-evaluate.
- The durable publish workflow and operator runbooks must both use this same state record; object-store state is never treated as authoritative by itself.
- `PUBLISHED` is the only success state that may be treated as launchable. Object-store bytes in `STAGED` or `EXPORTED_UNATTESTED` are not publish-complete on their own.

- For each `(tenantId, versionId)` the durable publish workflow runs an `ExportAssets` step that:
  - **Current implementation:** selects every `game_assets` row for `tenantId`
    through `GameAssetRepository.findByTenantId`, including assets with no
    `version_asset` mapping, and reads the export bytes from `game_assets.data`.
    The `version_asset` table and a Draft-version authoring action that creates
    or removes those mappings are not implemented yet.
  - **Target convergence:** selects only assets obtained by joining
    `version_asset` to `game_assets` for the target `(tenantId, versionId)`;
    unmapped tenant assets are excluded from that version's export.
  - Copies the selected ordinary asset bytes from `game_assets.data` into a deterministic published prefix such as
    `<tenantId>/<versionId>/` in object storage. Future metadata-only storage may instead copy/promote from an immutable source referenced by asset metadata, but not from mutable draft keys.
  - Writes or overwrites the version-scoped `manifest.json` in the same prefix.
  - Updates version metadata with the manifest location.
  - Transitions `version_asset_artifact` from `STAGED` to `EXPORTED_UNATTESTED`.
  - Fails the workflow step if any asset referenced in `version_asset` for the target
    `(tenantId, versionId)` is missing, so partially published versions cannot be
    marked as Published.
- The step is **idempotent**: rerunning `ExportAssets` for the same
  `(tenantId, versionId)` overwrites the same prefix and manifest and leaves the
  version metadata consistent.
- A later `FinalizePublishedRelease` step must read the computed `manifestHash`,
  write `published_release_bundle`, and only then transition
  `version_asset_artifact` from `EXPORTED_UNATTESTED` to `PUBLISHED`. If the
  attestation write fails, the artifact must remain `EXPORTED_UNATTESTED` or
  move to `FAILED`; implementations must not expose launchable `PUBLISHED`
  assets without a matching release attestation.
- Once a version is in the **Published** or **Active** state, immutability rules apply:
  - `version_asset` rows for `(tenantId, versionId)` must be treated as immutable mappings.
  - Referenced `game_assets` binaries must not be modified in place; replacing bytes requires a new `game_assets` row and (for Draft versions only) an updated mapping.
  - Retrying `ExportAssets` for a Published/Active version must be bit-for-bit identical (the overwrite is a retry mechanism, not a mutation mechanism).
  - Version metadata and/or the immutable `published_release_bundle` attestation must record `manifestHash` (and optionally per-asset `contentHash` values) so operators and CI can detect drift between metadata mappings and object-store contents.
  - If `manifestHash` verification fails for a Published/Active version, treat it as a data corruption or process bug incident. Do not “fix” the version in place by changing attested content; the only allowed repair is an exact-bytes rebuild that reproduces the existing `published_release_bundle` attestation. If that is impossible, recovery requires publishing a new `versionId`.
- If any downstream publish step fails, the durable workflow must:
  - mark the version as **Failed** in the Game Design Service so it cannot be activated, and
  - transition the asset artifact to `FAILED` instead of silently deleting bytes.

  Manual deletion of failed artifact prefixes is not part of normal compensation. Purge is a separate operator workflow after failure triage. Failed versions follow the lifecycle rules in
  [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md)
  and require an explicit repair or retry action before they can transition
  back to Draft or Published. Moving a failed artifact to `TOMBSTONED` is an explicit operator abandonment decision, not an automatic publish-failure transition.

Exact-bytes repair rule:

- Repair of a Published/Active version must begin by reading `GetPublishedReleaseBundle(tenantId, versionId)`.
- Repair must also read `GetVersionAssetArtifactState(tenantId, versionId)` and prove the expected `artifactState`, `stateEpoch`, and `manifestHash` before any bytes are rewritten.
- For ordinary binary assets in the current first slice, the repair workflow regenerates object-store bytes from the immutable `game_assets.data` rows selected for the attested version export. Those rows must not be modified in place after they are referenced by a Published/Active release.
- If a future storage model replaces `game_assets.data` with metadata plus object-store handles, the replacement repair source must be immutable and retained for every non-Retired or design-history-reachable release. A mutable draft object key by itself is not a valid repair source.
- The repair workflow may only regenerate object-store bytes that hash to the existing attested `manifestHash` (and optional per-asset hashes if recorded).
- If regenerated bytes would change the attestation payload, the workflow must fail closed and require a new `versionId` rather than mutating the published release in place.

Required deterministic repair/purge failure vocabulary:

- `VERSION_ASSET_NOT_DELETABLE` when `CanDeleteVersionAssets` rejects eligibility.
- `ASSET_ARTIFACT_STATE_CONFLICT` when `state_epoch` CAS fails or the lifecycle row no longer matches the caller's proof.
- `REPAIR_ATTESTATION_MISMATCH` when repair cannot reproduce the attested `manifestHash`.
- `PURGE_WORKFLOW_NOT_FOUND` when status or finalization reads reference an unknown `purgeWorkflowId`.
- `PURGE_FINALIZATION_CONFLICT` when byte deletion completed but lifecycle finalization failed and the retained workflow must be resumed.

These are control-plane application outcomes, not operator-inferred storage symptoms. Implementations must return them in normal responses rather than requiring humans to infer intent from raw object-store errors.

Manifest evolution rule for attested releases:

- Published/Active releases are immutable with respect to manifest bytes and `manifestHash`; they must not be migrated in place to a different manifest schema version by rerunning export.
- Runtime consumers, activation, and repair tooling must continue to understand older attested manifest schema versions for all non-Retired releases they may encounter.
- Rerunning `ExportAssets` to change manifest schema is allowed only before attestation completes or as part of a future explicit re-attestation workflow that defines how release immutability is preserved.

Deletion-eligibility authority:

- Game Design Service is the sole authority for deletion eligibility checks through `CanDeleteVersionAssets(tenantId, versionId)`.
- The check must validate all of the following before returning deletable:
  - if the target version row still exists, it is already in `RETIRED` state,
  - there is no dangling `published_release_bundle` attestation with no corresponding version-state row,
  - no launch descriptor still resolves to the version,
  - no approved template remap set still names the version as its source or target,
  - no non-Retired `version_asset` references remain,
  - no reachable `revision_asset` / branch references require retained bytes,
  - no normalized template or launch metadata still references the version prefix.

Race-safe purge workflow:

- Eligibility checks and purge start must not run as a loose "check then delete" pair.
- Purge must begin through a single CAS-guarded control-plane API, for example:
  - `BeginPurgeVersionAssets(tenantId, versionId, expectedArtifactStateEpoch)`
- `BeginPurgeVersionAssets` must atomically:
  - re-evaluate deletion eligibility (same rules as `CanDeleteVersionAssets`),
  - transition `version_asset_artifact` from `TOMBSTONED` to `PURGE_IN_PROGRESS` (or equivalent) using `state_epoch` CAS, and
  - return a `purgeWorkflowId` for the object-store deletion phase.
- If CAS fails or eligibility no longer holds, the API must fail without deleting objects.
- Finalization deletes object-store bytes using the frozen `exported_version_number` and exported manifest asset-key proof from `version_asset_artifact`, not by re-reading current draft assets or mutable version state. It transitions `PURGE_IN_PROGRESS -> PURGED` only after object deletion succeeds and must retain the lifecycle metadata row for audit.
- On deletion/finalization failure, workflow must transition to `PURGE_FAILED` with structured `last_error_code`/`last_error_message`; operators then use retry/resume APIs instead of manual object-store surgery.

## Asset Upload Guardrails

To prevent persistence and performance failures in asset workflows:

- Maximum single asset size is 25 MiB; oversized uploads must fail with `ASSET_TOO_LARGE`.
- Per-tenant draft asset quota is 2 GiB of stored `game_assets.data` bytes in the current first slice; writes beyond quota must fail with `ASSET_QUOTA_EXCEEDED`. A future metadata-only storage model may measure referenced draft object bytes instead.
- Upload/download APIs must support streaming/chunked transfer at the transport layer; services must not require buffering full payloads in memory before persistence.
- Publish/export workers must process assets in bounded batches (configurable), with backpressure metrics to avoid starving version publish orchestration.
- Quota and size limits must be configurable per environment but default to the values above when unset.

In the current first slice, `game_assets` is the canonical design-time store for asset metadata and bytes, and its immutable `data` values remain the repair source for Published/Active releases. A future metadata-only storage model may treat the table as metadata and use retained immutable object-store draft keys, but that is target-only.

Published assets still retain their `game_assets` rows for design history and exact-bytes repair.

A background maintenance job (or admin workflow) may mark unused asset rows as
`obsolete` once no open revisions, branches, or published versions reference
them. In practice this means:

- An asset metadata row is eligible for purge only if:
  - it is not referenced by any `version_asset` row where the associated version
    is in a non-Retired state (Draft, Published, Active, or Failed), and
  - it is not reachable from any open revision, branch, or Draft version via
    the normalized history reference tables (for example `revision_asset`)
    described in
    [Version Control for Design Assets](./version-control.md).
- Assets referenced by non-Retired versions must never be deleted, and their
  binary contents must not be modified in place.

Once these conditions are met, a maintenance process can purge the asset row and its corresponding unreferenced `game_assets.data` bytes. A future metadata-only storage model may also purge unreferenced draft object bytes. The exact retention policy (for example “keep assets referenced by the last N versions per tenant”) is configurable but should be documented alongside operational runbooks.

The export location is configured with `ASSET_STORE_ENDPOINT`,
`ASSET_STORE_BUCKET`, `ASSET_STORE_REGION`, `ASSET_STORE_ACCESS_KEY`, and
`ASSET_STORE_SECRET_KEY`. For development, the Docker Compose stack runs a
`minio` container that satisfies these variables.
