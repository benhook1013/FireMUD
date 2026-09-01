# Asset Storage Setup

Game assets are published through a Game Design-owned lifecycle. The target contract builds and verifies a private candidate, then publishes immutable content-addressed manifest entries that bind stable asset roles to mandatory actual-byte digests, content type/schema, and delivery locations. A manifest is produced for every published version, even if no assets are present, and its recorded delivery location is only a runtime retrieval surface, not release authority. The Game Design Service remains the control-plane authority and is not queried during gameplay; each record is scoped to its `tenantId`. For official hosted deployments, asset uploads and asset-bearing publication are creator-intent mutations covered by the Account-owned gate in [Account Service API Contracts](../account-service/api-contracts.md#account-owned-hosted-terms-and-creator-party) under [ADR 0180](../../decisions/adr-0180-account-owned-hosted-terms-acceptance-gate.md); stale acceptance and frozen-release continuity remain target-only under [ADR 0181](../../decisions/adr-0181-changed-hosted-terms-decline-and-existing-content-continuity.md).

Target runtime architecture uses an approved public `/assets/**` origin as the branding/theme byte data plane, including delivery of the published manifest and asset bytes; a CDN may back that origin but is not required. Game Design is the control-plane and attestation authority: `GetPublishedReleaseBundle(tenantId, versionId)` and its attested `manifestHash` establish which release the origin bytes belong to. Runtime clients fetch bytes from that approved origin using the attested release data and must not treat origin availability or object paths as release authority.

## Implementation Status

- **Current first slice:** Ordinary uploaded bytes are persisted in `game_assets.data`, which is the retained repair source selected by the process-local snapshot for Published/Active binary assets. This source is not database-enforced immutable: the repository can update `data` for an existing row, even though no current REST update route is exposed. Publication currently exports the bytes to version-scoped object storage and exposes the numeric `GameAssetDto.id`; the target private-candidate/content-addressed publication and opaque logical asset identifier are not live.
- **Current upload admission gap:** The target upload guardrails (25 MiB maximum file size, 2 GiB per-tenant draft quota, and streaming/chunked transfer) are requirements, not current proof. `AssetController` accepts multipart uploads and `GameAssetServiceImpl` calls `MultipartFile.getBytes()` without size or quota enforcement; `AssetStoreProperties` has no matching limits. Upload admission and resource-safety readiness therefore remain incomplete and the route must not be treated as ready for external enablement.
- **Hosted-content gate gap:** Current asset upload and publish paths do not evaluate Account currentness or persist a tenant/evidence binding. The target gate also applies to Draft asset writes and changed-term stale acceptance; no current route, schema, storage, UI/status, or focused proof implements it. The current Gateway's coarse `/api/design/**` route nevertheless forwards `/api/design/assets` (after `StripPrefix=2`) to the live `POST /assets` controller, whose Game Design boundary checks only privileged JWT/tenant access. Official-hosted asset-upload readiness is blocked until Gateway denies that route or the exact Account-owned gate is implemented and proved.
- **Current lifecycle proof:** `version_asset_artifact` records the exported version-number prefix and manifest asset keys, but it does not yet freeze the target exact immutable object-key/digest set or implement the target global publication/purge fence. Repair writes those live prefix objects and the manifest before its exact release-bundle comparison, so pre-write verification is target-only.
- **Current lifecycle concurrency gap:** `stateEpoch` comparisons and increments currently occur in service memory, while both `VersionAssetArtifactRepository` and `VersionRepository` update rows by `id` without an epoch predicate or affected-row check. `VersionService.compareAndSetVersionState` therefore has the same gap as artifact lifecycle writes: the live path lacks a database CAS fence for both artifact and Version lifecycle state. The target `expectedVersionStateEpoch` proof for abandonment, retirement, and reachability invalidation is blocked until both saves use a durable predicate/lock and prove the affected row.
- **Current destructive-operation idempotency gap:** The target destructive lifecycle operations do not yet share an ADR 0048 operation envelope or owner-local durable result ledger. Tombstone/finalize/repair accept workflow identifiers without a canonical request digest and exact replay/conflict contract, while live `BeginPurgeVersionAssets` generates a random workflow ID server-side. A lost response can therefore not reliably replay the same result; this remains target-only.
- **Current purge finalization:** Live `FinalizePurgeVersionAssets` selects and deletes the frozen version-scoped prefix from `exported_version_number`. This current-only behavior cannot be used as the target content-addressed/shared-object finalization path, which instead operates on frozen object-key/digest proofs under the publication/refcount fence below.
- **Current purge failure durability gap:** `FinalizePurgeVersionAssets` catches a runtime deletion/finalization failure, saves `PURGE_FAILED`/failed-workflow state, and rethrows from its `@Transactional` method. Those status writes can roll back with the transaction, while object-store deletion may already have succeeded, leaving durable `PURGE_IN_PROGRESS` with no resumable failure evidence. Target recovery requires an independent durable failure/reconciliation boundary (or equivalent non-rollback record) and focused proof for deletion and post-delete database-save failures.
- **Version-level purge boundary:** Target `FinalizePurgeVersionAssets` deletes only the frozen version-artifact objects authorized by the purge proof; it never removes `game_assets` source rows or their `game_assets.data` bytes.
- **Current tombstone gap:** Live `VersionAssetArtifactServiceImpl.tombstoneVersionAssets` accepts only `FAILED` and `PURGE_FAILED`; it does not yet support the target `PUBLISHED -> TOMBSTONED` transition for an eligible retired release. Current runbooks must expose that gap and fail closed rather than claim the retired-release path is live.
- **Current failed-candidate abandonment gap:** The target durable abandonment proof record and owner-local abandonment operation/read are not implemented. The live tombstone path checks only artifact state and epoch, so it does not enforce the required abandonment, caller-tenant `FAILED` authority, or no-release-reachability proof; no current implementation or focused proof claim is made for this path.
- **Current purge eligibility gap:** `CanDeleteVersionAssets` rejects a non-retired version when a tenant-matching version row exists, but its `findById(versionId).filter(tenant match)` lookup treats a row owned by another tenant as absent. When no caller-tenant release bundle remains, either no row or only a wrong-tenant row can therefore fall through to `deletable=true`; this is fail-open drift for dangling artifact rows. Target eligibility must require an existing caller-tenant version authority in `RETIRED` state and fail closed for both absence and tenant mismatch.
- **Current release attestation:** The current `GetPublishedReleaseBundle` response and `published_release_bundle` row preserve `manifestHash` and narrower current attestation fields, but do not provide the complete target mandatory actual-byte `artifactDigests[]`; consumers must not synthesize absent target fields, and target-dependent admission remains unproved and blocked.
- **Current publish-failure gap:** `VersionPublishCommandServiceImpl` catches downstream exceptions after `published_release_bundle` may already have been written, then unconditionally calls `markFailed`, performs best-effort object cleanup, and deletes the version without an exact attestation readback/reconciliation branch. Because `published_release_bundle.version_id` is a non-cascading foreign key, that delete can itself fail after bundle commit; the current path therefore has neither a truthful `FAILED` result nor guaranteed reconciliation to `PUBLISHED`/nonterminal state. Target recovery must read back committed bundle state and never delete Version as compensation. This is implementation/proof drift against the transition rules below and must not be advertised as target terminality.
- **Current delivery gap:** `AssetExportServiceImpl` currently constructs generated manifest URLs from the private `ASSET_STORE_ENDPOINT` plus bucket/key, while `AssetController` exposes only `POST /assets`. No client GET/download route, gateway rewrite, signed-fetch, or `ASSET_STORE_PUBLIC_BASE_URL` path is implemented; these generated URLs are private storage references and must not be exposed as usable client manifests. Canonical `/assets/**` delivery remains target-only pending a separate approved public origin/provisioner; the current Gateway has no `/assets/**` route and private MinIO is not public delivery.
- **Identifier drift:** `game_assets.tenant_id` accepts a REST `tenantId` string without UUID-shape enforcement while Account Service still exposes numeric `Long` tenant identifiers. No authoritative numeric-to-UUID mapping exists, and the public asset row key is a `BIGSERIAL`; none of these numeric values is a canonical logical identity.
- **Target convergence:** Account and downstream contracts migrate together to the opaque UUID tenant identity, and the public asset contract and `GameAssetDto.id` converge directly on an opaque UUID logical asset identifier while any numeric database key remains private. The numeric public field is removed rather than retained through compatibility translation; implementations must not invent a reversible numeric-to-UUID encoding. Draft bytes may move out of PostgreSQL only after an equivalent immutable repair source exists.

Logical world and entity templates (regions, rooms, items, NPCs, loot tables, scripts, etc.) remain stored in PostgreSQL schemas owned by the corresponding domain services and are not persisted as blobs in the asset store. The asset store is strictly for binary design assets plus version-scoped manifests exported by the Game Design Service.

Derived runtime-consumed artifacts produced by domain services follow the same writer rule:

- Domain services may own the semantics and generation of derived artifacts such as navmesh/path graph bundles.
- If those artifacts are exported for runtime consumption, Game Design publishes them on behalf of the owning service through the canonical [asset lifecycle and publish workflow](#asset-lifecycle-and-publish-workflow), not through a direct producer write or direct version-prefix export.
- Domain services must not write directly to the shared object store used for published version assets. A direct domain-service object-store write would bypass the artifact lifecycle, release attestation, and purge controls defined here.

Canonical producer-to-publisher handoff for derived artifacts:

- For the target producer handoff, a producer service that owns a derived runtime artifact must persist a version-scoped artifact record in its own database keyed by `(tenantId, versionId, usageKey)`. `usageKey` is stable and unique within that release scope; a collision or two records claiming the same usage key must fail closed rather than produce ambiguous manifest entries. `artifactKind` remains required metadata and does not replace the usage key.
- That producer-owned record is the canonical pre-publish handoff surface to Game Design and must include at minimum:
  - a stable fetch handle or byte source controlled by the producer service;
  - `usageKey` identifying the manifest and release-attestation entry;
  - `contentDigest` computed from the actual artifact bytes;
  - `contentType`;
  - `artifactKind`;
  - `producerService`;
  - a producer-local lifecycle/status field proving the artifact bytes are finalized for publish.
- Game Design must obtain derived artifact bytes and metadata through a typed producer-service API backed by that persisted record. Ad hoc filesystem sharing, direct producer writes into the published asset bucket, and convention-based object-key pickup are not allowed.
- The durable publish workflow order is: the producer materializes and freezes the derived artifact in its own ownership boundary; Game Design obtains those exact bytes, builds and verifies a private candidate with mandatory SHA-256 content digests, and exposes only immutable content-addressed objects after the release attestation succeeds. The detailed state and CAS rules remain in the [asset lifecycle and publish workflow](#asset-lifecycle-and-publish-workflow).
- Exact-bytes repair for a Published/Active version must re-read the same producer-owned artifact contract or an equivalent immutable repair source capable of reproducing the attested bytes. If the producer can no longer supply the attested bytes, recovery requires publishing a new `versionId`.

Illustrative producer API for derived artifacts:

- Producer services should expose a typed API such as `GetPublishableDerivedArtifact(tenantId, versionId, usageKey)`.
- Minimum response contract:
  - `status` (`READY`, `NOT_READY`, `FAILED`);
  - immutable fetch handle or byte-stream reference controlled by the producer;
  - `contentDigest` computed from the actual artifact bytes;
  - `contentType`;
  - `artifactKind`;
  - `producerService`;
  - producer-local finalized timestamp or equivalent evidence that publishable bytes are frozen.
- `READY` means the producer has durably recorded the artifact and guarantees the referenced bytes can be fetched for publish or exact-bytes repair.
- `NOT_READY` means publish must fail or wait before `ExportAssets`; Game Design must not export placeholder bytes.
- `FAILED` means the producer could not materialize a publishable artifact and must return structured failure details suitable for publish-workflow diagnostics.

Illustrative `GetPublishableDerivedArtifact` fragments:

The UUID-shaped `tenantId` and `versionId` values in these illustrative artifact responses are target-state identifiers. Current transport examples must use the numeric `int64` `versionId` contract until the related APIs are migrated together.

- `NOT_READY`:

```json
{
  "tenantId": "7b3b074e-d597-4e9b-b96f-4f5946d26120",
  "versionId": "4f035f76-4b87-4a5e-8b9f-ea6c9e66e620",
  "artifactKind": "NAVMESH",
  "status": "NOT_READY",
  "error": {
    "code": "DERIVED_ARTIFACT_NOT_FINALIZED",
    "message": "Navmesh generation has not produced finalized bytes for tenantId=7b3b074e-d597-4e9b-b96f-4f5946d26120 versionId=4f035f76-4b87-4a5e-8b9f-ea6c9e66e620."
  }
}
```

- `FAILED`:

```json
{
  "tenantId": "7b3b074e-d597-4e9b-b96f-4f5946d26120",
  "versionId": "4f035f76-4b87-4a5e-8b9f-ea6c9e66e620",
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

Target initial-slice discovery rule for derived world artifacts:

- In the target first implementation slice, exported world navmesh/path graph artifacts must be discoverable through the same attested release surfaces as other version assets.
- In the target first implementation slice, Game Design must publish a `manifest.json` entry keyed by a stable usage name for each exported world navmesh/path graph artifact.
- `GetPublishedReleaseBundle(tenantId, versionId)` is the canonical attestation surface. **Target contract:** it exposes both `artifactDigests[]` for exported derived-world-artifact bytes and `requiredManifestAssetKeys[]` for launch-required manifest usage keys, together with the attested `manifestHash`. The current row/response lacks the complete target fields, so this is not current implementation proof.
- The attested release contract must also declare which stable usage keys are required for launch of that specific release. Manifest integrity alone is not sufficient to infer whether an omitted key is valid or a launch-blocking defect.
- Runtime consumers must treat those attested references as canonical and must not construct object-store paths by convention.

Required-artifact attestation contract:

- `GetPublishedReleaseBundle(tenantId, versionId)` must expose an attested field named `requiredManifestAssetKeys[]`.
- For the first implementation slice, the field lists stable manifest usage keys that are required for launch or cutover validation of that release.
- `requiredManifestAssetKeys[]` may be empty for releases that do not require derived runtime artifacts.
- If `requiredManifestAssetKeys[]` contains `world.navmesh` or `world.pathGraph`, runtime launch and cutover tooling must require the corresponding `manifest.json` entry to exist and match the attested release metadata.
- Consumers must not infer requiredness from filename conventions, producer type, or the mere presence or absence of an entry in `manifest.json`.

Illustrative `GetPublishedReleaseBundle` fragment:

This target-state attestation example uses a UUID-shaped `versionId`. Current Game Design transport examples must use numeric `int64` `versionId` values until the related protobuf fields are migrated together.

```json
{
  "id": 7,
  "tenantId": "7b3b074e-d597-4e9b-b96f-4f5946d26120",
  "versionId": "4f035f76-4b87-4a5e-8b9f-ea6c9e66e620",
  "manifestHash": "sha256:2d4b2e...",
  "artifactDigests": [
    {
      "usageKey": "world.navmesh",
      "artifactKind": "NAVMESH",
      "immutableObjectKey": "artifacts/sha256/8fd0c4...",
      "contentDigest": "sha256:8fd0c4...",
      "contentType": "application/octet-stream",
      "artifactSchemaVersion": 1
    },
    {
      "usageKey": "world.pathGraph",
      "artifactKind": "PATH_GRAPH",
      "immutableObjectKey": "artifacts/sha256/91baf2...",
      "contentDigest": "sha256:91baf2...",
      "contentType": "application/json",
      "artifactSchemaVersion": 1
    }
  ],
  "requiredManifestAssetKeys": ["world.navmesh", "world.pathGraph"]
}
```

Initial-slice manifest shape for derived world artifacts:

- The required stable usage keys are `world.navmesh` and `world.pathGraph`.
- For manifest `schemaVersion: 1`, these derived-world-artifact entries must appear under the top-level `assets` object.
- Future manifest schema versions may extend the manifest shape, but they must either preserve these keys under `assets` or publish an explicit schema-version migration note before changing their location.
- If a published version exports only one of those artifacts, the manifest may omit the other key.
- Each exported derived-world-artifact entry must include at least:
  - `url` – runtime fetch location for the published immutable object;
  - `contentDigest` – mandatory SHA-256 digest of the actual artifact bytes;
  - `immutableObjectKey` – content-addressed object identity;
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
      "usageKey": "world.navmesh",
      "artifactKind": "NAVMESH",
      "immutableObjectKey": "artifacts/sha256/8fd0c4...",
      "contentType": "application/octet-stream",
      "contentDigest": "sha256:8fd0c4...",
      "artifactSchemaVersion": 1,
      "producerService": "world-management-service",
      "versionId": "4f035f76-4b87-4a5e-8b9f-ea6c9e66e620",
      "url": "https://cdn.example.invalid/assets/artifacts/sha256/8fd0c4..."
    },
    "world.pathGraph": {
      "usageKey": "world.pathGraph",
      "artifactKind": "PATH_GRAPH",
      "immutableObjectKey": "artifacts/sha256/91baf2...",
      "contentType": "application/json",
      "contentDigest": "sha256:91baf2...",
      "artifactSchemaVersion": 1,
      "producerService": "world-management-service",
      "versionId": "4f035f76-4b87-4a5e-8b9f-ea6c9e66e620",
      "url": "https://cdn.example.invalid/assets/artifacts/sha256/91baf2..."
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

## Target External Delivery Classification

Target published asset delivery uses the canonical external `/assets/**` family. This remains target-only pending a separate approved public origin/provisioner; the current Gateway has no `/assets/**` route, and private MinIO is not public delivery:

- `/assets/**` is the read-only branding/theme byte data plane for published release artifacts, not a creator/control-plane write path.
- The canonical object-store or CDN URL exported in `manifest.json` represents stable published bytes for that release, but the CDN is not the release authority.
- `GetPublishedReleaseBundle(tenantId, versionId)` and its attested `manifestHash` are the Game Design control-plane authority; runtime consumers and clients must resolve published assets through that release metadata rather than inventing bucket paths or treating Game Design upload routes as runtime-read surfaces.
- Any future authenticated or signed-read variant must still preserve `/assets/**` as a delivery family separate from Game Design creator APIs under `/api/design/**`.

## Table Structure

The `game_assets` table stores ordinary design-time upload records. In the current first implementation slice it also stores the uploaded bytes used as the exact-bytes repair source. Columns include:

- `id` – current `BIGSERIAL` row key; it is not the tenant identity and must not be treated as the target public logical identifier
- `tenant_id` – **target:** identifies the owning game using the canonical UUID `tenantId`; **current:** stores an unconstrained `VARCHAR(36)` string while the UUID migration and validation remain incomplete
- `file_name` – original file name
- `content_type` – MIME type
- `data` – uploaded bytes for the ordinary binary asset in the current first slice; they are retained as the repair source for object-store republish/repair by current convention, not protected by a database immutability constraint (the repository can update an existing row). A future metadata-only storage model must introduce an equivalent retained immutable source before removing this source.
- `created_at` – upload timestamp

Future metadata-only storage may replace `data` with fields such as `storage_key`, `content_hash`, and `size_bytes`, but only if the new schema preserves the same repair invariant: Published/Active releases must be exactly reproducible for as long as their assets remain non-Retired or design-history reachable.

To associate assets with specific published versions while still allowing reuse across versions, the target Game Design contract requires a separate mapping table. The current implementation has neither this table nor a Draft-version authoring path for its mappings; the following shape and constraints are target-state until both exist:

- `version_asset`:
  - `tenant_id` – owning game
  - `version_id` – owning Draft, Published, Active, or Failed version identifier
  - `asset_id` – foreign key to `game_assets.id`
  - `usage_key` – canonical manifest key. For ordinary binary assets in the current
    first slice, this is the persisted `game_assets.file_name` value verbatim; target
    Draft mapping writers persist the same key and must not derive it from row order,
    `asset_id`, or an object-store URL.
  - `usage_type` – optional classifier such as `logo`, `icon`, or `audio`
  - `created_at` – mapping creation timestamp

The target combinations `(tenant_id, version_id, asset_id)` and
`(tenant_id, version_id, usage_key)` are unique. If two mappings in one version resolve
to the same `usage_key`, the Draft mapping write or publish gate must reject the
collision deterministically; it must not suffix the key or use last-write-wins behavior.
The same asset can be referenced by multiple versions without duplicating the binary
row. Once the target authoring path exists and a mapping belongs to a version in the
Published or Active state described in [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md), the referenced asset must be treated as immutable; replacing the binary requires creating a new `game_assets` row and a new `version_asset` mapping.

Failed versions may retain their normalized `version_asset` mappings while they remain retryable; those mappings continue to make the referenced bytes reachable and exempt from draft-quota accounting. Explicit abandonment must remove those failed-version mappings through the owner-controlled reachability workflow before purge eligibility is established. A failed version is not treated as launchable merely because its mappings remain during retry.

Tenant-scoped uniqueness is not sufficient referential integrity for this boundary. The target schema must tie the tenant, version, and asset identities together: expose tenant-qualified parent keys and use composite foreign keys (or an equivalent owner-transaction plus database guard) for `(tenant_id, version_id)` to `version` and `(tenant_id, asset_id)` to `game_assets`. The release-bundle binding must apply the same tenant/version integrity rule; a scalar `version_id` foreign key alongside an unrelated `tenant_id` can represent a cross-tenant release. Mapping, bundle, migration, and readback proof must include negative cross-tenant insert and lookup cases before version-scoped asset publication is considered isolated.

Artifact lifecycle state for each version must be persisted in a dedicated state table:

- `version_asset_artifact`:
  - `tenant_id`
  - `version_id`
  - `exported_version_number` (current first-slice prefix audit metadata; never target purge-selection authority)
  - `published_object_proofs[] { immutable_object_key, content_digest }` (target frozen exact object-key/digest set for every exported candidate; this is lifecycle proof, not a `PUBLISHED`-only launch attestation)
  - `artifact_state` (`STAGED`, `EXPORTED_UNATTESTED`, `PUBLISHED`, `FAILED`, `TOMBSTONED`, `PURGE_IN_PROGRESS`, `PURGE_FAILED`, `PURGED`)
  - `state_epoch` (monotonic CAS token)
  - `manifest_hash`
  - `last_workflow_id` (publish/repair workflow identity)
  - `last_error_code` / `last_error_message` (nullable; set on failed transitions)
  - `updated_at`

An abandoned failed candidate also requires a durable owner-local abandonment record before it can be tombstoned or purged. The target shape is `version_asset_abandonment`, keyed to `(tenant_id, version_id)` and containing:

- `tenant_id` and `version_id` – the caller-tenant candidate binding
- `abandonment_request_id` – stable request identity for the abandonment operation
- `request_digest` – digest of the canonical abandonment request, including tenant, version, and expected artifact/version epochs
- `observed_artifact_state_epoch` and `observed_version_state_epoch` – the `FAILED` state/fence read at authorization time
- `abandonment_state` – `RECORDED`, `TOMBSTONE_CONSUMED`, or `INVALIDATED`
- `tombstone_state_epoch` – the resulting artifact epoch when the proof is consumed by tombstoning, nullable before then
- `created_at` and nullable invalidation metadata

The target record would be written only by Game Design's authorized owner-local operation `AbandonFailedVersionAssets(tenantId, versionId, expectedArtifactStateEpoch, expectedVersionStateEpoch, abandonmentRequestId, abandonmentRequestDigest)` and read through the target-only `GetFailedVersionAssetAbandonment(tenantId, versionId)` operation. Neither operation is a current proto RPC or implementation. A retry with the same request identity and digest returns the same record; reuse with a different digest is rejected. The write must require a caller-tenant version authority and artifact both in `FAILED`, matching expected epochs, complete frozen candidate proof, no unresolved publish write, and no caller-tenant release bundle or other release reachability. The operation records abandonment evidence but does not itself tombstone the artifact.

Any state or epoch change before tombstoning, or any release-bundle/release-reachability evidence, invalidates or rejects the record. `TombstoneVersionAssets` must atomically consume a still-valid `RECORDED` proof while transitioning the matching `FAILED` artifact to `TOMBSTONED`; `BeginPurgeVersionAssets` must require the resulting `TOMBSTONE_CONSUMED` proof, matching `tombstone_state_epoch`, continued caller-tenant `FAILED` authority, and a fresh no-release-reachability/eligibility check. A failed candidate without this durable proof is not eligible, even when its artifact state is `FAILED` or `TOMBSTONED`.

Before the first byte is exported for a version, the target workflow must freeze an
immutable durable per-version export snapshot. The target shape is a
`version_asset_export_item` projection keyed by `(tenant_id, version_id, usage_key)`
with the selected `asset_id`, source-row identity, and computed source `content_hash`
(plus size/type metadata when needed for verification). Same-version retries and
exact-bytes repair must use this snapshot, never re-select the current tenant asset
list or mutable draft mappings. This durable projection is deferred target
convergence and is not implemented by the current first slice.

`(tenant_id, version_id)` is unique in `version_asset_artifact`. This enum list is the canonical schema contract for both persistence and API validation. Target lifecycle transitions must use compare-and-set on `state_epoch` so concurrent publish/repair/purge workflows cannot race; the current implementation's in-memory check followed by an unconditional row-ID update is documented in the implementation-status and lifecycle sections below.

An index named `idx_game_assets_tenant` speeds up queries scoped to a tenant.
Additional indexes may support common design-time queries (for example by
`tenant_id` and upload timestamps) but are not required for runtime because
published assets are served from object storage.

## API

In the current first slice, assets are uploaded via `POST /assets` using a `multipart/form-data` request, persisted with bytes in `game_assets.data`, and returned as a `GameAssetDto` whose public `id` is the numeric asset-row key and whose data fields follow the current OpenAPI schema. Exposing that row key is pre-v1 drift. The canonical target replaces it directly with the opaque UUID logical asset identifier while retaining any numeric database key only as a private implementation detail; callers, OpenAPI, and DTOs migrate together without dual-field or translation compatibility scaffolding. The target storage contract also streams bytes to object storage and returns metadata plus stable download information; that storage and DTO convergence is not complete. `AssetController` currently exposes only the upload route; REST download and delete endpoints are not implemented. Asset deletion remains a control-plane lifecycle operation through the APIs below.
See the [OpenAPI specification](../../../../services/game-design-service/src/main/resources/openapi.yaml) for request details.
The control-plane lifecycle operations below describe the target contract; only the subset marked live in the implementation-status notes is currently supported. The current Game Design proto exposes lifecycle/proof operations but does not expose a general asset-listing RPC. Tenant asset listing and the failed-candidate abandonment operation are target/deferred and unavailable in the current first slice; callers must not infer either from the existing artifact-state operations.
Control-plane purge APIs are required:

- `TombstoneVersionAssets(tenantId, versionId, expectedArtifactStateEpoch, tombstoneWorkflowId)` – target pre-tombstone authority operation: it checks caller-tenant state, retirement or durable failed-candidate abandonment, all release/reachability rules, and the applicable frozen proof, then performs the CAS-guarded transition to `TOMBSTONED`. Ordinary publish failure remains `FAILED` and retryable. The current implementation accepts only `FAILED` and `PURGE_FAILED`, does not perform those target checks, and does not support retired-`PUBLISHED` tombstoning.
- `CanDeleteVersionAssets(tenantId, versionId)` – target post-tombstone, read-only fail-closed eligibility recheck. It confirms the tombstone proof and current authority/reachability state immediately before purge; it is not the pre-tombstone authority operation.
- `AbandonFailedVersionAssets(tenantId, versionId, expectedArtifactStateEpoch, expectedVersionStateEpoch, abandonmentRequestId, abandonmentRequestDigest)` and `GetFailedVersionAssetAbandonment(tenantId, versionId)` – target-only, illustrative names for the authorized, idempotent owner-local recording/read of the failed-candidate abandonment proof described above; neither operation nor its persistence is live in the current first slice.
- `BeginPurgeVersionAssets(tenantId, versionId, expectedArtifactStateEpoch, purgeWorkflowId)` – target CAS-guarded purge start using a caller-supplied workflow identity; the shared operation envelope carries the canonical request digest.
- `FinalizePurgeVersionAssets(tenantId, versionId, purgeWorkflowId, expectedArtifactStateEpoch)` – target CAS-guarded purge completion.
- `GetVersionAssetArtifactState(tenantId, versionId)` – authoritative lifecycle/proof read for the persisted artifact row.
- `RepairPublishedVersionAssets(tenantId, versionId, expectedArtifactStateEpoch, repairWorkflowId)` – exact-bytes repair start for attested releases.
- `GetVersionAssetPurgeStatus(tenantId, versionId, purgeWorkflowId)` – operator-visible workflow status for in-flight or failed purge attempts.

Logging & Admin, CI, and runbooks must consume these control-plane APIs instead of reconstructing state from `version_asset_artifact` table reads plus bucket inspection.

### Shared destructive-lifecycle operation envelope

The target `TombstoneVersionAssets`, `BeginPurgeVersionAssets`, `FinalizePurgeVersionAssets`, and `RepairPublishedVersionAssets` mutations use one concise owner-local operation envelope defined by [ADR 0048](../../decisions/adr-0048-durable-idempotent-operator-write-execution.md), rather than a lifecycle-specific idempotency variant. Each request carries a caller-stable operation/workflow identity, operation kind, exact normalized target and expected-state inputs, and the ADR 0048 canonical `mutationDigest/v1`. Game Design persists that envelope and its durable outcome in an owner-local ledger before performing the mutation. An exact identity and digest replay returns the stored result without a second mutation; any changed target, operation kind, expected epoch, workflow identity, or digest returns `IDEMPOTENCY_CONFLICT`. Ambiguous external responses remain outcome-pending and require read-only reconciliation through the stored operation/result record.

The caller supplies the workflow identity for every operation, including `purgeWorkflowId` for `BeginPurgeVersionAssets`; the service must not mint a random identity as the only way to start purge. The current first slice has no shared owner ledger or canonical digest validation for these operations, and `BeginPurgeVersionAssets` still generates a random workflow ID server-side. Exact replay, conflict, and reconciliation proof therefore remain target-only.

Implementation notes:

- `GetVersionAssetArtifactState`, `RepairPublishedVersionAssets`, `TombstoneVersionAssets`, `CanDeleteVersionAssets`, `BeginPurgeVersionAssets`, `FinalizePurgeVersionAssets`, and `GetVersionAssetPurgeStatus` are now live in `game-design-service`.
- `version_asset_artifact` is now a persisted control-plane row and full-version publish updates it through `EXPORTED_UNATTESTED` and `PUBLISHED`.
- the persisted artifact row now stores the exact exported version number plus manifest asset keys for current completed-export cleanup/prefix deletion; it does not yet provide the durable frozen source-row/content proof or immutable object-key/digest proof required for exact repair or target content-addressed purge. The current first slice therefore keeps Published/Active repair unavailable after process loss and uses only its current version-prefix cleanup path; a failed write before `ExportAssets` returns still has no durable key proof and remains an implementation gap. See the lifecycle and repair/purge sections below for the target proof boundary.
- `CanDeleteVersionAssets` now also fails closed on live launch-descriptor references and on approved template remap sets that still name the source or target version, so purge cannot silently remove bytes still needed by current launch and replacement-cutover control-plane truth.
- `version_asset_purge_workflow` is now the retained workflow-status surface for purge start/finalization outcomes.

A basic repository (`GameAssetRepository`) and service implementation
(`GameAssetServiceImpl`) persist uploads through the service-local jOOQ/PostgreSQL data boundary.

At publish time, the current implementation reads asset bytes from `game_assets.data`, freezes a process-local selection for the version, revalidates that the version has not become Published/Active immediately before caching the snapshot, and exports those bytes into version-scoped published prefixes in object storage, referencing them in the generated `manifest.json`. This direct prefix export is implementation drift; target convergence builds and verifies a private candidate with mandatory actual-byte digests, then exposes immutable content-addressed objects through the [Asset Lifecycle and Publish Workflow](#asset-lifecycle-and-publish-workflow). Duplicate file-name usage keys and the reserved `manifest.json` key fail closed before any object-store write. The process-local snapshot cache is bounded by `asset.store.frozen-snapshot-cache-max-entries` (default `256`); a new uncached version scope fails closed with `EXPORT_SNAPSHOT_CAPACITY` at capacity rather than evicting retry evidence. Target runtime clients load branding and theme resources directly from the approved public `/assets/**` origin using the manifest; that origin may be backed by a CDN, but a CDN is not required. The Game Design Service is not involved in byte delivery, but its `GetPublishedReleaseBundle` response and attested `manifestHash` remain the control-plane authority. In the current first slice, generated manifest URLs use the private `ASSET_STORE_ENDPOINT` and are not client-facing or usable by runtime clients; delivery remains blocked pending a separate approved public origin/provisioner for canonical `/assets/**`, and private MinIO is not public delivery. A future metadata-only storage model may use retained immutable object-store draft keys, but those keys are target-only and are not the current upload or repair source. See [Game Design Service Architecture](README.md) for how these assets fit into published versions.

The current repair ordering is not the target pre-write safety rule: `AssetExportServiceImpl` writes each version-prefix object and then `manifest.json`; only after that export returns does `VersionAssetArtifactServiceImpl` compare the resulting hash/key set with the published release bundle. A repair mismatch can therefore write or overwrite live prefix keys before it fails with `REPAIR_ATTESTATION_MISMATCH`. Target repair must build and verify away from published keys before any live write; this target rule is not current implementation proof.

The target export boundary persists the artifact `STAGED` intent and its deterministic candidate prefix/manifest evidence before the first object-store write, records each written key (or an equivalent immutable candidate manifest) durably, and uses idempotent content-addressed writes and deletes. A durable cleanup retry/outbox reconciles partial candidates; an object-store timeout or lost response remains nonterminal until exact key/content readback proves the candidate state. Only the owner-local artifact and release-attestation commit can make bytes launchable, and a storage listing cannot substitute for that authority. The current first slice does not yet satisfy this pre-write intent or ambiguous-export recovery boundary.

In the target contract, the `published_release_bundle` attestation must reference the final asset state for the version by including `manifestHash` and mandatory actual-byte `artifactDigests[]` entries exposed through Game Design’s `GetPublishedReleaseBundle` API. Activation, cutover preflight, and repair tooling must consume the API instead of reconstructing asset state from `version_asset_artifact` and version metadata separately.

`published_release_bundle` is persisted in the Game Design Service schema. Game Design owns the table shape, Flyway migrations, and attestation writes for that record; other services consume the attestation only through `GetPublishedReleaseBundle(tenantId, versionId)` and must not treat it as a shared-schema artifact.

### Interaction with Script-Only Patches

Script-only patches (see `system-architecture-versioning-runtime.md`) do not change assets or any data stored in `game_assets` / `version_asset`. In the target contract, published asset selection is bound to `(tenantId, versionId)` and exported during full `PublishVersion` flows. The current first slice does not yet persist that binding: `AssetExportServiceImpl` initially selects every tenant asset, freezes the selection only in process-local memory, and has no `version_asset` authoring path. A same-runtime rerun reuses the frozen selection; a Published/Active retry fails with `REPAIR_VERSION_SCOPE_UNAVAILABLE` when that selection is unavailable instead of reselecting the tenant-wide list. Asset changes therefore belong in a new `versionId` in the target state, while the current tenant-wide selection remains an implementation gap to be corrected before persisted version-bound export can be treated as proven.

### Asset Lifecycle and Publish Workflow

The publish workflow uses a dedicated workflow step to export assets and update
manifest metadata:

The target publication topology follows [ADR 0095](../../decisions/adr-0095-content-addressed-published-assets-with-cas-lifecycle-authority.md) and [ADR 0096](../../decisions/adr-0096-attested-publication-gate-and-quarantined-failed-assets.md):

- Every binary and derived artifact is hashed from its actual bytes with mandatory SHA-256. The attested manifest records a stable usage key, immutable content-addressed object key, digest, content type/schema, and delivery location; URLs, names, and byte lengths are not byte attestation.
- Candidate bytes and manifests are built and verified in a private staging or quarantine namespace. Public manifest and object keys are immutable and content-addressed, and no candidate becomes a runtime discovery surface before the complete release attestation succeeds.
- Retrying the same verified bytes is idempotent. Changed bytes require a new content key and, for an attested release, a new version; a retry or repair never overwrites a live key with an unverified candidate.
- Target state: `version_asset_artifact` and Version lifecycle state/epoch remain the lifecycle authorities. Storage listings, object existence, and CDN responses are delivery evidence only and cannot establish launch, retirement, or purge eligibility. Current artifact and Version state-epoch checks are in-memory followed by unconditional repository updates by row ID; neither target database CAS/atomic-lock behavior is live. Until both saves use an epoch predicate/lock and affected-row proof, an `expectedVersionStateEpoch` or artifact epoch cannot prove that abandonment, retirement, or reachability evidence remained valid.

Artifact lifecycle states for a `(tenantId, versionId)` prefix are explicit:

- `STAGED` – publish attempt has durably reserved the candidate and records its immutable candidate evidence; candidate bytes may be pending or partially written while the version is not yet Published.
- `EXPORTED_UNATTESTED` – candidate bytes and `manifest.json` have been exported and `manifestHash` is known, but the immutable `published_release_bundle` attestation has not yet been committed.
- `PUBLISHED` – publish succeeded, `manifestHash` is attested in `published_release_bundle`, and the immutable bytes for the version are launchable.
- `FAILED` – publish workflow failed for this version.
- `TOMBSTONED` – an eligible retired `PUBLISHED` release or an abandoned `FAILED` artifact is quarantined for diagnostics and excluded from activation paths.
- Target `PURGE_IN_PROGRESS` – the purge workflow has atomically locked this prefix for deletion and is removing object-store bytes. The current implementation has no database lifecycle-row lock/CAS proving that lock.
- `PURGE_FAILED` – purge workflow encountered a deletion/finalization failure; bytes may be partially deleted and require explicit operator retry/resume workflow.

Allowed transitions:

- `STAGED -> EXPORTED_UNATTESTED` on successful `ExportAssets` completion and `manifestHash` computation.
- `EXPORTED_UNATTESTED -> PUBLISHED` only after `published_release_bundle` is written successfully for the same `(tenantId, versionId)` and records the same `manifestHash`.
- `EXPORTED_UNATTESTED -> FAILED` only when publish fails before attestation commit and exact readback proves that no `published_release_bundle` exists at all for the `(tenantId, versionId)`, with no unresolved external write or cleanup ambiguity. Any attestation row for that tenant/version, including one with a mismatched `manifestHash`, prevents `FAILED` and remains nonterminal/reconciliation-required until the owner determines the attestation outcome; a matching committed attestation reconciles the same `(tenantId, versionId, manifestHash)` to `PUBLISHED`, never `FAILED`. An ambiguous external write remains nonterminal and reconciliation-required until exact readback proves either committed attestation (then `PUBLISHED`) or no attestation plus safe cleanup. Once the immutable release is `PUBLISHED`, it is not demoted to `FAILED` by a later workflow or delivery failure.
- `STAGED -> FAILED` when publish workflow fails before activation eligibility and the owner has proved that no external write remains unresolved through exact readback or safe cleanup; an ambiguous or lost-response write remains `STAGED` and reconciliation-required until that proof exists.
- `FAILED -> STAGED` only through an explicit repair/retry workflow.
- `PUBLISHED -> TOMBSTONED` only through the target pre-tombstone `TombstoneVersionAssets` authority check, which requires retirement and all reachability proof, followed by its CAS-guarded transition; the post-tombstone `CanDeleteVersionAssets` recheck then confirms the proof before purge. This transition is not supported by the current implementation.
- `FAILED -> TOMBSTONED` only after operators explicitly abandon retry and quarantine bytes.
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

- Target behavior: every artifact transition is persisted by updating `version_asset_artifact` with CAS on `state_epoch`, and every Version transition uses the corresponding Version epoch CAS; a failed CAS means another workflow already changed state and callers must reload current state and re-evaluate. The current repositories do not implement those predicates or affected-row checks.
- The durable publish workflow and operator runbooks must both use this same state record; object-store state is never treated as authoritative by itself.
- `PUBLISHED` is the only success state that may be treated as launchable. Object-store bytes in `STAGED` or `EXPORTED_UNATTESTED` are not publish-complete on their own.

- For each `(tenantId, versionId)` the durable publish workflow runs an `ExportAssets` step that:
  - **Current implementation:** initially selects every `game_assets` row for `tenantId` through `GameAssetRepository.findByTenantId`, including assets with no `version_asset` mapping, and reads the export bytes from `game_assets.data`. It freezes that selection in process-local memory before writing bytes. The `version_asset` table and a Draft-version authoring action that creates or removes those mappings are not implemented yet.
  - **Target convergence:** freezes a persisted selection from `version_asset` joined to `game_assets` for the target `(tenantId, versionId)`; unmapped tenant assets are excluded from that version's export. The candidate is built privately, every selected byte is SHA-256 hashed, and the verified manifest and objects receive immutable content-addressed keys before public delivery.
  - Copies the selected ordinary asset bytes from `game_assets.data` (or a future equivalent immutable repair source) into private candidate storage, verifies the complete candidate, and only then publishes immutable content-addressed objects. A tenant/version prefix may remain a delivery grouping, but it is not the object identity or lifecycle authority.
  - Writes the version-scoped `manifest.json` as a content-addressed immutable object after candidate verification; it must not overwrite an attested manifest key.
  - Updates version metadata with the manifest location.
  - Transitions `version_asset_artifact` from `STAGED` to `EXPORTED_UNATTESTED`.
  - **Target-only validation:** once `version_asset` authoring exists, fails the workflow step if any asset referenced in `version_asset` for the target `(tenantId, versionId)` is missing, so partially published versions cannot be marked as Published. The current implementation cannot perform this validation because the mapping table and authoring path do not exist.
- **Target-state retry behavior:** Once the frozen export snapshot exists, rerunning `ExportAssets` for the same `(tenantId, versionId)` reuses that snapshot, recomputes and verifies the same content-addressed bytes, and leaves the version metadata consistent. It must not re-select assets after the snapshot is frozen, and it must not overwrite an attested public key. Changed bytes require a new key and release version.
- **Current implementation boundary:** `AssetExportServiceImpl` freezes a process-local version-scoped snapshot and reuses it for same-runtime reruns, but it does not persist `version_asset_export_item` evidence. A Published/Active retry reuses that frozen snapshot when it is still available and fails closed with `REPAIR_VERSION_SCOPE_UNAVAILABLE` when it is absent, rather than calling `GameAssetRepository.findByTenantId` again. After process loss, exact repair therefore remains unavailable until the target persisted snapshot exists. **Current drift:** if the Version row is now `RETIRED`, the live resolver can instead take a fresh tenant-wide `findByTenantId` snapshot after process loss because only `PUBLISHED`/`ACTIVE` fail closed; that selection is not exact repair proof and operators must treat the path as fail-closed until the target durable snapshot exists.
- A later `FinalizePublishedRelease` step must read the computed `manifestHash`, write `published_release_bundle`, and only then transition `version_asset_artifact` from `EXPORTED_UNATTESTED` to `PUBLISHED`. If the attestation write has a lost or ambiguous response, the artifact remains `EXPORTED_UNATTESTED` and reconciliation-required; it may move to `FAILED` only after exact readback proves that no `published_release_bundle` exists for the tenant/version at all and no unresolved attestation write or cleanup ambiguity remains. Any existing row, including a mismatched attestation, keeps the artifact nonterminal until owner reconciliation determines the outcome; a matching committed attestation permits reconciliation to `PUBLISHED` and never `FAILED`. Implementations must not expose launchable `PUBLISHED` assets without a matching release attestation.
- The `EXPORTED_UNATTESTED` readback and any transition to `FAILED` must be serialized against concurrent publication for the same `(tenantId, versionId)`. The owner transaction (or one shared lifecycle CAS/lock fence held across the readback and state transition) must lock or compare the artifact row and exact `published_release_bundle` identity together, re-read the bundle immediately before deciding `FAILED`, and reject a stale state epoch. A matching bundle that committed before or during the readback reconciles the artifact to `PUBLISHED`; a missing bundle can transition to `FAILED` only when the same fenced readback proves no publication committed and no external write remains ambiguous. A later failure must never demote a matching publication or delete its version/assets.
- Current implementation drift is sharper than the target rule: after bundle creation, the publish catch path still marks failure, performs best-effort cleanup, and calls `VersionRepository.delete`. Since `published_release_bundle.version_id` is a non-cascading foreign key, that compensation delete can fail after a committed bundle. Target recovery must reconcile bundle/readback evidence and must never delete Version to compensate for an ambiguous post-bundle failure; focused proof must cover committed-bundle, missing-bundle, and lost-response branches.
- Once a version is in the **Published** or **Active** state, immutability rules apply:
  - `version_asset` rows for `(tenantId, versionId)` must be treated as immutable mappings.
  - Referenced `game_assets` binaries must not be modified in place; replacing bytes requires a new `game_assets` row and (for Draft versions only) an updated mapping.
  - The per-version export snapshot's source row IDs and content hashes are immutable; a same-version retry or repair must prove those exact IDs and hashes before rewriting any object-store bytes.
  - Retrying `ExportAssets` for a Published/Active version must reproduce the exact attested content-addressed bytes without mutating live keys.
  - Version metadata and the immutable `published_release_bundle` attestation must record the manifest digest and mandatory per-artifact content digests so operators and CI can detect drift between metadata mappings and object-store contents.
  - If `manifestHash` verification fails for a Published/Active version, treat it as a data corruption or process bug incident. Do not “fix” the version in place by changing attested content; the only allowed repair is an exact-bytes rebuild that reproduces the existing `published_release_bundle` attestation. If that is impossible, recovery requires publishing a new `versionId`.
- If any downstream publish step fails conclusively before the immutable release is `PUBLISHED`, with no unresolved external write or cleanup ambiguity, and exact readback proves that no `published_release_bundle` row of any kind exists for the tenant/version, the durable workflow must:
  - mark the version as **Failed** in the Game Design Service so it cannot be activated, and
  - transition the asset artifact to `FAILED` instead of silently deleting bytes.

  Once `PUBLISHED` is committed, that release remains the launchable immutable state and must not be demoted to `FAILED` by a later publish, delivery, or repair failure. Post-publication failures use the owner-specific reconciliation and incident-handling path (including exact-byte repair or controlled retirement/purge when eligible), while preserving the attested release state and its launch-gating evidence.

  Manual deletion of failed artifact prefixes is not part of normal compensation. Purge is a separate operator workflow after failure triage. Failed versions follow the lifecycle rules in
  [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md)
  and require an explicit repair or retry action before they can transition
  back to Draft or Published. Moving a failed artifact to `TOMBSTONED` is an explicit operator abandonment decision, not an automatic publish-failure transition.

Exact-bytes repair rule:

- Repair of a Published/Active version must begin by reading `GetPublishedReleaseBundle(tenantId, versionId)`.
- **Target state:** Repair must also read `GetVersionAssetArtifactState(tenantId, versionId)` and prove the expected `artifactState`, `stateEpoch`, and `manifestHash` before any bytes are rewritten. **Current implementation:** the export invoked by repair writes version-prefix objects and `manifest.json` before `VersionAssetArtifactServiceImpl` performs the exact repair-match comparison, so a mismatch can mutate live prefix keys before returning `REPAIR_ATTESTATION_MISMATCH`.
- Repair must materialize candidate bytes away from published keys and verify every attested object and manifest digest before writing any missing immutable object. It may restore only exact attested content-addressed objects; if a repair source or digest is unavailable, it fails closed and requires a new version.
- Repair must read and verify the immutable per-version export snapshot, including every exported source-row ID and content hash. In the current implementation, the process-local frozen selection is the only available snapshot evidence; if it is absent because the service restarted or the version was exported without that evidence, Published/Active repair must fail closed with `REPAIR_VERSION_SCOPE_UNAVAILABLE` rather than guessing from current draft assets. Recovery then requires publishing a new `versionId` until the target persisted snapshot exists.
- Once version-scoped selection and its frozen snapshot exist, the repair workflow may regenerate ordinary object-store bytes from the retained `game_assets.data` rows recorded by that snapshot. The current repository does not enforce those rows as immutable and can update `data` by ID; target publication must replace bytes with a new source row or otherwise provide a retained immutable repair source before a Published/Active release relies on it.
- If a future storage model replaces `game_assets.data` with metadata plus object-store handles, the replacement repair source must be immutable and retained for every non-Retired or design-history-reachable release. A mutable draft object key by itself is not a valid repair source.
- The repair workflow may only regenerate object-store bytes that reproduce the existing attested `manifestHash` and every mandatory actual-byte digest in `artifactDigests[]`.
- If regenerated bytes would change the attestation payload, the workflow must fail closed and require a new `versionId` rather than mutating the published release in place.

Required deterministic repair/purge failure vocabulary:

- `VERSION_ASSET_NOT_DELETABLE` when `CanDeleteVersionAssets` rejects eligibility.
- `ASSET_ARTIFACT_STATE_CONFLICT` when `state_epoch` CAS fails or the lifecycle row no longer matches the caller's proof.
- `ASSET_USAGE_KEY_COLLISION` when duplicate file-name-derived manifest keys or the reserved `manifest.json` key make the export ambiguous.
- `EXPORT_SNAPSHOT_CAPACITY` when the bounded process-local snapshot cache cannot admit a new version scope without evicting retry evidence.
- `REPAIR_VERSION_SCOPE_UNAVAILABLE` when a Published/Active release lacks the frozen version-scoped export snapshot required for exact repair.
- `REPAIR_ATTESTATION_MISMATCH` when repair cannot reproduce the attested `manifestHash`.
- `PURGE_WORKFLOW_NOT_FOUND` when status or finalization reads reference an unknown `purgeWorkflowId`.
- `PURGE_FINALIZATION_CONFLICT` when byte deletion completed but lifecycle finalization failed and the retained workflow must be resumed.

These are control-plane application outcomes, not operator-inferred storage symptoms. Implementations must return them in normal responses rather than requiring humans to infer intent from raw object-store errors.

Manifest evolution rule for attested releases:

- Published/Active releases are immutable with respect to manifest bytes and `manifestHash`; they must not be migrated in place to a different manifest schema version by rerunning export.
- Runtime consumers, activation, and repair tooling must continue to understand older attested manifest schema versions for all non-Retired releases they may encounter.
- Rerunning `ExportAssets` to change manifest schema is allowed only before attestation completes or as part of a future explicit re-attestation workflow that defines how release immutability is preserved.

Deletion-eligibility authority:

For a published/release artifact, retirement is necessary but insufficient for purge. An abandoned failed candidate has a separate purge-eligibility path: it requires an existing caller-tenant `FAILED` version authority and durable explicit abandonment proof; it must not be forced through a `FAILED -> RETIRED` transition. Both paths must prove that all applicable launch, template, history, mapping, remap, and shared-object reachability checks pass before the CAS-guarded purge workflow can begin.

- Game Design Service is the sole owner of deletion eligibility. `TombstoneVersionAssets` is the pre-tombstone authority operation: it validates the applicable version state, abandonment proof, frozen artifact proof, and all release/reachability rules before its CAS transition. `CanDeleteVersionAssets` is the post-tombstone, read-only fail-closed recheck immediately before purge; it must not be used as a pre-tombstone approval oracle.
- The pre-tombstone authority and post-tombstone recheck together must validate all of the following before purge can proceed:
  - for a published/release artifact, a version authority row for the caller tenant exists and is already in `RETIRED` state; for an abandoned failed candidate, a version authority row for the caller tenant exists in `FAILED` state together with durable explicit abandonment proof. If no caller-tenant row exists—including when `versionId` exists only under another tenant—eligibility is false for either path,
  - an abandoned failed candidate has no caller-tenant `published_release_bundle` or other published/release reachability; any such release evidence requires the published/release path and its `RETIRED` authority,
  - there is no dangling `published_release_bundle` attestation with no corresponding version-state row,
  - no launch descriptor still resolves to the version,
  - no approved template remap set still names the version as its source or target,
  - no non-Retired `version_asset` references remain,
  - no reachable `revision_asset` / branch references require retained bytes,
  - no normalized template or launch metadata still references the version prefix.

Race-safe purge workflow:

- Eligibility checks and purge start must not run as a loose "check then delete" pair. The target order is: `TombstoneVersionAssets` performs the pre-tombstone authority/reachability/abandonment check and CAS transition; `CanDeleteVersionAssets` performs the post-tombstone fail-closed recheck; `BeginPurgeVersionAssets` performs the final atomic recheck and `TOMBSTONED -> PURGE_IN_PROGRESS` CAS. The current implementation has the opposite unusable drift—`CanDeleteVersionAssets` rejects pre-tombstone `FAILED`/`PUBLISHED` states, while `TombstoneVersionAssets` does not perform the target authority checks—and its artifact and Version row updates are not database CAS. Missing-tenant-matching-version/no-bundle eligibility (including a wrong-tenant version row) also currently fails open; all remain implementation gaps.
- Focused proof must cover a caller-tenant artifact with no tenant-matching version authority, both when `versionId` is absent and when a row exists only for another tenant, with no caller-tenant release bundle; each case must return `deletable=false`. Positive controls are a caller-tenant `RETIRED` version for a published/release artifact and a caller-tenant `FAILED` version with durable explicit abandonment proof for an abandoned candidate, each with the other applicable reachability checks clear. A `FAILED` candidate without that abandonment proof remains non-eligible.
- The target workflow calls `TombstoneVersionAssets` first for a retired `PUBLISHED` release, or for a `FAILED` candidate only after caller-tenant `FAILED` authority and durable abandonment proof are available. It then reloads the artifact `stateEpoch`, calls post-tombstone `CanDeleteVersionAssets`, and only then calls `BeginPurgeVersionAssets`; the current implementation/order drift is explicitly fail-closed until these branches are implemented.
- `TombstoneVersionAssets` must independently require the matching durable abandonment proof for the failed-candidate path and perform the pre-tombstone reachability check; `CanDeleteVersionAssets` must recheck the resulting tombstone and current authority/reachability; `BeginPurgeVersionAssets` must re-evaluate all proof and reachability conditions again rather than trusting an earlier response.
- Purge must begin through a single CAS-guarded control-plane API, for example:
  - `BeginPurgeVersionAssets(tenantId, versionId, expectedArtifactStateEpoch, purgeWorkflowId)`
- `BeginPurgeVersionAssets` must atomically:
  - re-evaluate post-tombstone deletion eligibility (same fail-closed rules as `CanDeleteVersionAssets`),
  - claim and advance the shared lifecycle fence by transitioning `version_asset_artifact` from `TOMBSTONED` to `PURGE_IN_PROGRESS` (or equivalent) using `state_epoch` CAS, and
  - persist and return the caller-supplied `purgeWorkflowId` for the object-store deletion phase under the shared destructive-lifecycle operation envelope.
- Every commit that acquires a launch descriptor, normalized asset mapping, template dependency, approved remap, retained-history reference, or any other reachability reference must participate in the same artifact lifecycle fence. Within the acquiring transaction it must compare and advance the expected `artifactState`/`stateEpoch` (or hold the equivalent lifecycle-row lock through commit), and it must fail or retry if the artifact is `TOMBSTONED`, `PURGE_IN_PROGRESS`, `PURGE_FAILED`, `PURGED`, or epoch-changed.
- A concurrent reference acquisition that loses the lifecycle CAS must not commit its reference. If `BeginPurgeVersionAssets` loses the CAS or eligibility no longer holds, it must delete nothing, reload fresh lifecycle and reachability state, and retry only through the approved workflow.
- Target lifecycle proof freezes the exact `published_object_proofs[] { immutableObjectKey, contentDigest }` set before candidate publication completes. The same frozen proof applies when an unpublished candidate is `FAILED`, then explicitly abandoned into `TOMBSTONED`, and finally advanced to `PURGE_IN_PROGRESS`; in those states the keys remain private quarantine objects, while a `PUBLISHED` artifact's proof corresponds to its attested immutable objects. `published_object_proofs[]` is distinct from the `published_release_bundle.artifactDigests[]` launch attestation. A failed candidate with no complete durable proof is not eligible for `TOMBSTONED` or `PURGE_IN_PROGRESS`; the purge workflow must not discover missing candidates by listing quarantine or other mutable namespaces. Finalization must select candidates only from the frozen proof. `exported_version_number`, version prefixes, bucket listings, current draft assets, and mutable version state are audit or delivery metadata and never purge-selection authority.
- While holding the publication/purge fence, finalization removes this release's reference and revalidates global reachability or transactionally maintained reference counts for every frozen key/digest tuple. It deletes only frozen keys proven globally unreachable and retains shared keys that remain reachable from another release. It transitions `PURGE_IN_PROGRESS -> PURGED` only after every required object deletion succeeds and must retain the lifecycle metadata row for audit. Direct bucket commands are outside the supported lifecycle.
- On deletion/finalization failure, workflow must transition to `PURGE_FAILED` with structured `last_error_code`/`last_error_message`; operators then use retry/resume APIs instead of manual object-store surgery.

## Asset Upload Guardrails

To prevent persistence and performance failures in asset workflows:

- Maximum single asset size is 25 MiB; oversized uploads must fail with `ASSET_TOO_LARGE`.
- Per-tenant draft asset quota is 2 GiB. **Target admission rule:** exclude bytes retained solely because a `version_asset` reference keeps them reachable from a Published, Active, or Failed version. The current first slice has no authoritative `version_asset` mapping and performs no quota accounting or enforcement; it therefore neither proves that exemption nor applies a conservative charge. Once version-scoped references and quota enforcement exist, the quota query must derive exempt bytes from those normalized references rather than from tenant-wide export contents or object-store path conventions. Writes that would exceed the target quota must fail with `ASSET_QUOTA_EXCEEDED`. A future metadata-only storage model may measure retained immutable draft-object bytes instead, without changing the draft-only target boundary.
- Upload/download APIs must support streaming/chunked transfer at the transport layer; services must not require buffering full payloads in memory before persistence.
- Publish/export workers must process assets in bounded batches (configurable), with backpressure metrics to avoid starving version publish orchestration.
- Quota and size limits must be configurable per environment but default to the values above when unset.

In the current first slice, `game_assets` is the canonical design-time store for asset metadata and bytes. Its retained `data` values become the repair source only after the frozen version-scoped export snapshot identifies the exact rows and hashes used by that release; neither the database schema nor repository update path enforces immutability, so editing a referenced row would invalidate that assumption. Until the snapshot is available, same-version repair fails with `REPAIR_VERSION_SCOPE_UNAVAILABLE` for Published/Active releases. A future metadata-only storage model may treat the table as metadata and use retained immutable object-store draft keys, but that is target-only.

Published assets still retain their `game_assets` rows for design history and exact-bytes repair. In the target contract, version-level `FinalizePurgeVersionAssets` deletes only the frozen version-artifact objects and never removes those source rows or `game_assets.data` bytes; it routes through the Game Design reachability and CAS-guarded artifact-purge workflow rather than direct object-store commands. The current implementation only deletes the frozen version-prefix objects and lacks that database CAS/reachability fence.

A separate target maintenance workflow may mark an unreferenced `game_assets` row as `obsolete` and remove that row and its `game_assets.data` bytes. That workflow must independently prove global normalized `version_asset` reachability across every version state, global normalized `revision_asset` and branch reachability, and any other retained design-history dependency, then perform its own CAS-guarded maintenance transition. Assets referenced by any retained version or history path must never be deleted, and their binary contents must not be modified in place. The current version-artifact purge workflow is not this source-row maintenance workflow and must not delete `game_assets` rows or data. A future metadata-only storage model may apply the same separate reachability/CAS boundary to unreferenced draft objects. The exact retention policy (for example “keep assets referenced by the last N versions per tenant”) is configurable but should be documented alongside operational runbooks.

The export location is configured with `ASSET_STORE_ENDPOINT`,
`ASSET_STORE_BUCKET`, `ASSET_STORE_REGION`, `ASSET_STORE_ACCESS_KEY`, and
`ASSET_STORE_SECRET_KEY`. For development, the Docker Compose stack runs a
`minio` container that satisfies these variables.
