# Asset Storage Setup

Game assets such as icons or sound files are uploaded through the Game Design
Service at design time. They are stored in the service database while being
edited. When a version is published, the service uploads these assets to
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

The `game_assets` table stores the raw binary data for design-time uploads. Columns include:

- `id` – primary key
- `tenant_id` – identifies the owning game as a GUID string stored in `VARCHAR(36)`
- `file_name` – original file name
- `content_type` – MIME type
- `data` – binary blob
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
  - `artifact_state` (`STAGED`, `PUBLISHED`, `FAILED`, `TOMBSTONED`, `PURGED`)
  - `state_epoch` (monotonic CAS token)
  - `manifest_hash`
  - `last_workflow_id` (publish/repair workflow identity)
  - `updated_at`

`(tenant_id, version_id)` is unique in `version_asset_artifact`. All lifecycle transitions must use compare-and-set on `state_epoch` so concurrent publish/repair/purge workflows cannot race.

The `data` column uses PostgreSQL `BYTEA` type to store the file contents.
When returned by the REST API this byte array is Base64 encoded by default so the JSON response remains text based.

An index named `idx_game_assets_tenant` speeds up queries scoped to a tenant.
Additional indexes may support common design-time queries (for example by
`tenant_id` and upload timestamps) but are not required for runtime because
published assets are served from object storage.

## API

Assets are uploaded via `POST /assets` using a `multipart/form-data` request and the saved record, including the binary `data` field, is returned as a `GameAssetDto`.
See the [OpenAPI specification](../../../../services/game-design-service/src/main/resources/openapi.yaml) for request details.
Endpoints for downloading or deleting assets are available.
gRPC endpoints support asset management operations.
Listing assets for a tenant is supported.

A basic repository (`GameAssetRepository`) and service implementation
(`GameAssetServiceImpl`) persist uploads using Spring Data JPA.

At publish time, assets are exported from the database to object storage and
referenced in the generated `manifest.json`. Runtime clients load branding and
theme resources directly from the CDN using this manifest; the Game Design
Service is not involved. See [Game Design Service Architecture](README.md) for
how these assets fit into published versions.

### Interaction with Script-Only Patches

Script-only patches (see `system-architecture-versioning-runtime.md`) do not change assets or any data stored in `game_assets` / `version_asset`. Because assets are always bound to `(tenantId, versionId)` and exported during full `PublishVersion` flows, any change that requires adding, removing, or updating assets must be shipped as part of a new `versionId`, not as a script-only patch.

### Asset Lifecycle and Publish Workflow

The publish workflow uses a dedicated Saga step to export assets and update
manifest metadata:

Artifact lifecycle states for a `(tenantId, versionId)` prefix are explicit:

- `STAGED` – publish attempt has written candidate bytes but version is not yet Published.
- `PUBLISHED` – publish succeeded and `manifestHash` is committed for the immutable bytes.
- `FAILED` – publish workflow failed for this version.
- `TOMBSTONED` – failed or abandoned artifact is quarantined for diagnostics and excluded from activation paths.

Allowed transitions:

- `STAGED -> PUBLISHED` on successful `ExportAssets` completion and `manifestHash` commit.
- `STAGED -> FAILED` when publish workflow fails before activation eligibility.
- `FAILED -> STAGED` only through an explicit repair/retry workflow.
- `FAILED -> TOMBSTONED` when operators abandon retry and quarantine bytes.
- `TOMBSTONED -> STAGED` only via explicit operator-approved restore workflow.
- `TOMBSTONED -> PURGED` (physical deletion) only after deletion eligibility checks pass; purge is not an implicit publish compensation action.

Transition enforcement contract:

- Every transition is persisted by updating `version_asset_artifact` with CAS on `state_epoch`.
- Failed CAS means another workflow already changed state; callers must reload current state and re-evaluate.
- Publish Saga and operator runbooks must both use this same state record; object-store state is never treated as authoritative by itself.

- For each `(tenantId, versionId)` the Saga runs an `ExportAssets` step that:
  - Selects assets by joining `version_asset` to `game_assets` for the target
    `(tenantId, versionId)`; assets not referenced via `version_asset` are **never**
    exported for that version.
  - Uploads the selected assets from `game_assets` to a deterministic prefix such as
    `<tenantId>/<versionId>/` in object storage.
  - Writes or overwrites the version-scoped `manifest.json` in the same prefix.
  - Updates version metadata with the manifest location.
  - Fails the Saga step if any asset referenced in `version_asset` for the target
    `(tenantId, versionId)` is missing, so partially published versions cannot be
    marked as Published.
- The step is **idempotent**: rerunning `ExportAssets` for the same
  `(tenantId, versionId)` overwrites the same prefix and manifest and leaves the
  version metadata consistent.
- Once a version is in the **Published** or **Active** state, immutability rules apply:
  - `version_asset` rows for `(tenantId, versionId)` must be treated as immutable mappings.
  - Referenced `game_assets` binaries must not be modified in place; replacing bytes requires a new `game_assets` row and (for Draft versions only) an updated mapping.
  - Retrying `ExportAssets` for a Published/Active version must be bit-for-bit identical (the overwrite is a retry mechanism, not a mutation mechanism).
  - Version metadata must record a `manifestHash` (and optionally per-asset `contentHash` values) so operators and CI can detect drift between database mappings and object-store contents.
  - If `manifestHash` verification fails for a Published/Active version, treat it as a data corruption or process bug incident. Do not “fix” the version in place; repair requires republishing a new `versionId` or executing an operator-approved recovery workflow that re-derives the exact bytes from the authoritative database mappings.
- If any downstream publish step fails, the Saga must:
  - mark the version as **Failed** in the Game Design Service so it cannot be activated, and
  - transition the asset artifact to `TOMBSTONED` instead of silently deleting bytes.

  Manual deletion of failed artifact prefixes is not part of normal compensation. Purge is a separate operator workflow after failure triage. Failed versions follow the lifecycle rules in
  [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md)
  and require an explicit repair or retry action before they can transition
  back to Draft or Published.

Deletion-eligibility authority:

- Game Design Service is the sole authority for deletion eligibility checks through `CanDeleteVersionAssets(tenantId, versionId)`.
- The check must validate all of the following before returning deletable:
  - no non-Retired `version_asset` references remain,
  - no reachable `revision_asset` / branch references require retained bytes,
  - no normalized template or launch metadata still references the version prefix.

The database is optimized for design-time editing rather than long-term bulk
storage. Implementations should treat `game_assets` as:

- The canonical store for **draft** and in-progress assets.
- A short- to medium-term cache for recently published assets needed for design
  history and branch workflows.

A background maintenance job (or admin workflow) may mark unused asset rows as
`obsolete` once no open revisions, branches, or published versions reference
them. In practice this means:

- An asset row is eligible for purge only if:
  - it is not referenced by any `version_asset` row where the associated version
    is in the Published, Active, or Retired states, and
  - it is not reachable from any open revision, branch, or Draft version via
    the normalized history reference tables (for example `revision_asset`)
    described in
    [Version Control for Design Assets](./version-control.md).
- Assets referenced by non-Retired versions must never be deleted, and their
  binary contents must not be modified in place.

Once these conditions are met, a maintenance process can purge the asset row to
control database size. The exact retention
policy (for example “keep assets referenced by the last N versions per tenant”)
is configurable but should be documented alongside operational runbooks.

The export location is configured with `ASSET_STORE_ENDPOINT`,
`ASSET_STORE_BUCKET`, `ASSET_STORE_REGION`, `ASSET_STORE_ACCESS_KEY`, and
`ASSET_STORE_SECRET_KEY`. For development, the Docker Compose stack runs a
`minio` container that satisfies these variables.
