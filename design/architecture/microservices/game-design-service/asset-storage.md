# Asset Storage Setup

Game assets such as icons or sound files are uploaded through the Game Design
Service at design time. Draft and published asset bytes live in object storage;
the Game Design database stores only metadata, hashes, references, and
lifecycle state for those objects. When a version is published, the service uploads or promotes these assets to
tenant- and version-scoped object storage (e.g., S3, MinIO, or a CDN) and
generates a `manifest.json` that maps asset keys to public URLs. A manifest is
produced for every published version, even if no assets are present. The manifest is
stored alongside the assets and its URL is recorded in the published version
metadata so runtime clients can retrieve it. Each manifest includes an explicit
`schemaVersion` field so clients and tooling can distinguish between manifest
formats over time. The Game Design Service is not queried during gameplay. Each
record remains tied to a `tenantId` so icons, UI images, and audio files are
isolated per game.

Logical world and entity templates (regions, rooms, items, NPCs, loot tables, scripts, etc.) remain stored in PostgreSQL schemas owned by the corresponding domain services and are not persisted as blobs in the asset store. The asset store is strictly for binary design assets plus version-scoped manifests exported by the Game Design Service.

## Table Structure

The `game_assets` table stores metadata for design-time uploads. Columns include:

- `id` – primary key
- `tenant_id` – identifies the owning game as a GUID string stored in `VARCHAR(36)`
- `file_name` – original file name
- `content_type` – MIME type
- `storage_key` – object-store key for the canonical draft asset bytes
- `content_hash` – immutable content digest of the uploaded bytes
- `size_bytes` – stored object size
- `created_at` – upload timestamp

To associate assets with specific published versions while still allowing reuse across
versions, the Game Design Service maintains a separate mapping table:

- `version_asset`:
  - `tenant_id` – owning game
  - `version_id` – published version identifier
  - `asset_id` – foreign key to `game_assets.id`
  - `usage_type` – optional classifier such as `logo`, `icon`, or `audio`
  - `created_at` – mapping creation timestamp

The combination `(tenant_id, version_id, asset_id)` is unique so the same asset can be
referenced by multiple versions without duplicating the binary row. Once a mapping
exists for a version in the Published or Active state described in
[Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md),
the referenced asset must be treated as immutable; replacing the binary requires
creating a new `game_assets` row and a new `version_asset` mapping.

Artifact lifecycle state for each exported prefix must be persisted in a dedicated state table:

- `version_asset_artifact`:
  - `tenant_id`
  - `version_id`
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

Assets are uploaded via `POST /assets` using a `multipart/form-data` request. The service streams bytes directly to object storage, then persists the metadata row and returns a `GameAssetDto` containing metadata and stable download information rather than echoing raw bytes from PostgreSQL.
See the [OpenAPI specification](../../../../services/game-design-service/src/main/resources/openapi.yaml) for request details.
Endpoints for downloading or deleting assets are available.
gRPC endpoints support asset management operations.
Listing assets for a tenant is supported.
Control-plane purge APIs are required:

- `CanDeleteVersionAssets(tenantId, versionId)` – read-only eligibility oracle.
- `BeginPurgeVersionAssets(tenantId, versionId, expectedArtifactStateEpoch)` – CAS-guarded purge start.
- `FinalizePurgeVersionAssets(tenantId, versionId, purgeWorkflowId, expectedArtifactStateEpoch)` – CAS-guarded purge completion.

A basic repository (`GameAssetRepository`) and service implementation
(`GameAssetServiceImpl`) persist uploads using Spring Data JPA.

At publish time, assets are exported from Game Design metadata plus the
referenced object-store draft keys into version-scoped published prefixes in
object storage and
referenced in the generated `manifest.json`. Runtime clients load branding and
theme resources directly from the CDN using this manifest; the Game Design
Service is not involved. See [Game Design Service Architecture](README.md) for
how these assets fit into published versions.

The `published_release_bundle` attestation must reference the final asset state
for the version by including `manifestHash` (and optionally per-asset
`contentHash` values) exposed through Game Design’s `GetPublishedReleaseBundle`
API. Activation, cutover preflight, and repair tooling must consume the API
instead of reconstructing asset state from `version_asset_artifact` and version
metadata separately.

### Interaction with Script-Only Patches

Script-only patches (see `system-architecture-versioning-runtime.md`) do not change assets or any data stored in `game_assets` / `version_asset`. Because assets are always bound to `(tenantId, versionId)` and exported during full `PublishVersion` flows, any change that requires adding, removing, or updating assets must be shipped as part of a new `versionId`, not as a script-only patch.

### Asset Lifecycle and Publish Workflow

The publish workflow uses a dedicated Saga step to export assets and update
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
- Publish Saga and operator runbooks must both use this same state record; object-store state is never treated as authoritative by itself.
- `PUBLISHED` is the only success state that may be treated as launchable. Object-store bytes in `STAGED` or `EXPORTED_UNATTESTED` are not publish-complete on their own.

- For each `(tenantId, versionId)` the Saga runs an `ExportAssets` step that:
  - Selects assets by joining `version_asset` to `game_assets` for the target
    `(tenantId, versionId)`; assets not referenced via `version_asset` are **never**
    exported for that version.
  - Copies or promotes the selected objects referenced by `game_assets.storage_key` into a deterministic published prefix such as
    `<tenantId>/<versionId>/` in object storage.
  - Writes or overwrites the version-scoped `manifest.json` in the same prefix.
  - Updates version metadata with the manifest location.
  - Transitions `version_asset_artifact` from `STAGED` to `EXPORTED_UNATTESTED`.
  - Fails the Saga step if any asset referenced in `version_asset` for the target
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
- If any downstream publish step fails, the Saga must:
  - mark the version as **Failed** in the Game Design Service so it cannot be activated, and
  - transition the asset artifact to `TOMBSTONED` instead of silently deleting bytes.

  Manual deletion of failed artifact prefixes is not part of normal compensation. Purge is a separate operator workflow after failure triage. Failed versions follow the lifecycle rules in
  [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md)
  and require an explicit repair or retry action before they can transition
  back to Draft or Published.

Exact-bytes repair rule:

- Repair of a Published/Active version must begin by reading `GetPublishedReleaseBundle(tenantId, versionId)`.
- The repair workflow may only regenerate object-store bytes that hash to the existing attested `manifestHash` (and optional per-asset hashes if recorded).
- If regenerated bytes would change the attestation payload, the workflow must fail closed and require a new `versionId` rather than mutating the published release in place.

Deletion-eligibility authority:

- Game Design Service is the sole authority for deletion eligibility checks through `CanDeleteVersionAssets(tenantId, versionId)`.
- The check must validate all of the following before returning deletable:
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
- Finalization transitions `PURGE_IN_PROGRESS -> PURGED` only after object deletion succeeds and must retain lifecycle metadata row for audit.
- On deletion/finalization failure, workflow must transition to `PURGE_FAILED` with structured `last_error_code`/`last_error_message`; operators then use retry/resume APIs instead of manual object-store surgery.

## Asset Upload Guardrails

To prevent persistence and performance failures in asset workflows:

- Maximum single asset size is 25 MiB; oversized uploads must fail with `ASSET_TOO_LARGE`.
- Per-tenant draft asset quota is 2 GiB of referenced draft object bytes; writes beyond quota must fail with `ASSET_QUOTA_EXCEEDED`.
- Upload/download APIs must support streaming/chunked transfer at the transport layer; services must not require buffering full payloads in memory before persistence.
- Publish/export workers must process assets in bounded batches (configurable), with backpressure metrics to avoid starving version publish orchestration.
- Quota and size limits must be configurable per environment but default to the values above when unset.

The database is optimized for design-time metadata rather than bulk binary
storage. Implementations should treat `game_assets` as:

- The canonical metadata store for **draft** and in-progress assets.
- A metadata index for published assets needed for design history and branch workflows.

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

Once these conditions are met, a maintenance process can purge the asset metadata row and corresponding unreferenced draft object bytes. The exact retention
policy (for example “keep assets referenced by the last N versions per tenant”)
is configurable but should be documented alongside operational runbooks.

The export location is configured with `ASSET_STORE_ENDPOINT`,
`ASSET_STORE_BUCKET`, `ASSET_STORE_REGION`, `ASSET_STORE_ACCESS_KEY`, and
`ASSET_STORE_SECRET_KEY`. For development, the Docker Compose stack runs a
`minio` container that satisfies these variables.
