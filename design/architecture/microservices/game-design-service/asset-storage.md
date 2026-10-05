# Asset Storage Setup

Game assets are published through a Game Design-owned lifecycle. The target contract builds and verifies a private candidate, then publishes immutable content-addressed manifest entries that bind stable asset roles to mandatory actual-byte digests, content type/schema, and delivery locations. A manifest is produced for every published version, even if no assets are present, and its recorded delivery location is only a runtime retrieval surface, not release authority. The Game Design Service remains the control-plane authority and is not queried during gameplay; each record is scoped to its `tenantId`. For official hosted deployments, asset uploads and asset-bearing publication are creator-intent mutations covered by the Account-owned gate in [Account Service API Contracts](../account-service/api-contracts.md#account-owned-hosted-terms-and-creator-party) under [ADR 0180](../../decisions/adr-0180-account-owned-hosted-terms-acceptance-gate.md); stale acceptance and frozen-release continuity remain target-only under [ADR 0181](../../decisions/adr-0181-changed-hosted-terms-decline-and-existing-content-continuity.md).

Target runtime architecture uses an approved public `/assets/**` origin as the branding/theme byte data plane, including delivery of the published manifest and asset bytes; a CDN may back that origin but is not required. Game Design is the control-plane and attestation authority: `GetPublishedReleaseBundle(tenantId, versionId)` and its attested `manifestHash` establish which release the origin bytes belong to. Runtime clients fetch bytes from that approved origin using the attested release data and must not treat origin availability or object paths as release authority.

## Implementation Status

- **Ordinary-byte producer boundary:** Owner-local Draft mappings and durable per-version snapshots select actual `game_assets.data` bytes; database guards retain referenced source identity, filename, content type and bytes. The exporter records and independently reads the complete candidate before conditional content-addressed writes and exact byte readback. This is bounded producer implementation, not authenticated creator ingress, derived-artifact completeness, public delivery or runtime activation. Detailed capability and proof status belongs to the [publishing tracker](../../../project-management/implementation-tracking/game-authoring-publishing-and-activation.md).
- **Current upload admission gap:** The target upload guardrails (25 MiB maximum file size, 2 GiB per-tenant draft quota, and streaming/chunked transfer) are requirements, not current proof. `AssetController` accepts multipart uploads and `GameAssetServiceImpl` calls `MultipartFile.getBytes()` without size or quota enforcement; `AssetStoreProperties` has no matching limits. Upload admission and resource-safety readiness therefore remain incomplete and the route must not be treated as ready for external enablement.
- **Hosted-content gate gap:** Current asset upload and publish paths do not evaluate Account currentness or persist a tenant/evidence binding. The target gate also applies to Draft asset writes and changed-term stale acceptance; no current route, schema, storage, UI/status, or focused proof implements it. The Gateway's explicit read-only Game Design routes do not forward `/api/design/assets` to the live `POST /assets` controller, whose service-local boundary still checks only privileged JWT/tenant access. Official-hosted asset-upload readiness remains blocked until the exact Account-owned gate is implemented and proved; edge containment is not the owner-side gate.
- **Candidate and lifecycle proof:** `version_asset_artifact` retains immutable manifest/schema, complete artifact and manifest object-key/digest evidence tied to the durable snapshot. Artifact updates use tenant/version-qualified epoch CAS and checked affected-row counts. Snapshot and candidate retries read the same committed evidence; they do not rebuild ownership from listings. The global publication/reachability/purge fence and Version lifecycle CAS remain incomplete; candidate persistence does not establish those authorities.
- **Current destructive-operation idempotency gap:** The target destructive lifecycle operations do not yet share an ADR 0048 operation envelope or owner-local durable result ledger. Tombstone/finalize/repair accept workflow identifiers without a canonical request digest and exact replay/conflict contract, while live `BeginPurgeVersionAssets` generates a random workflow ID server-side. A lost response can therefore not reliably replay the same result; this remains target-only.
- **Current purge finalization:** The exporter denies physical deletion with `CONTENT_ADDRESSED_PURGE_PROOF_REQUIRED`. An exported version number or listing cannot authorize deletion of shared content-addressed objects. Target finalization requires frozen object-key/digest proofs and the publication/refcount fence below; it is not enabled by this producer.
- **Current purge failure durability gap:** `FinalizePurgeVersionAssets` catches a runtime deletion/finalization failure, saves `PURGE_FAILED`/failed-workflow state, and rethrows from its `@Transactional` method. Those status writes can roll back with the transaction, while object-store deletion may already have succeeded, leaving durable `PURGE_IN_PROGRESS` with no resumable failure evidence. Target recovery requires an independent durable failure/reconciliation boundary (or equivalent non-rollback record) and focused proof for deletion and post-delete database-save failures.
- **Version-level purge boundary:** Target `FinalizePurgeVersionAssets` deletes only the frozen version-artifact objects authorized by the purge proof; it never removes `game_assets` source rows or their `game_assets.data` bytes.
- **Current tombstone gap:** Live `VersionAssetArtifactServiceImpl.tombstoneVersionAssets` accepts only `FAILED` and `PURGE_FAILED`; it does not yet support the target `PUBLISHED -> TOMBSTONED` transition for an eligible retired release. Current runbooks must expose that gap and fail closed rather than claim the retired-release path is live.
- **Current failed-candidate abandonment gap:** The target durable abandonment proof record and owner-local abandonment operation/read are not implemented. The live tombstone path checks only artifact state and epoch, so it does not enforce the required abandonment, caller-tenant `FAILED` authority, or no-release-reachability proof; no current implementation or focused proof claim is made for this path.
- **Current purge eligibility gap:** `CanDeleteVersionAssets` rejects a non-retired version when a tenant-matching version row exists, but its `findById(versionId).filter(tenant match)` lookup treats a row owned by another tenant as absent. When no caller-tenant release bundle remains, either no row or only a wrong-tenant row can therefore fall through to `deletable=true`; this is fail-open drift for dangling artifact rows. Target eligibility must require an existing caller-tenant version authority in `RETIRED` state and fail closed for both absence and tenant mismatch.
- **Current release attestation:** Newly constructed ordinary-asset bundles retain `manifestSchemaVersion` and exact `artifactDigests[]` in storage and readback. Retained incomplete rows remain explicitly without this evidence; absence is not a proved empty manifest. Full owner-provided derived-artifact, ability-schema and authenticated release/descriptor proof remains incomplete, and consumers must not synthesize it.
- **Publication recovery boundary:** `VersionPublishCommandServiceImpl` commits and independently reads the staged intent before export. An uncertain export outcome, including a later object conflict, remains `PENDING` for exact candidate/write reconciliation. Finalization recovery compares the committed bundle and complete ordinary-asset candidate; only proved rollback with no release may record failure. Neither branch deletes the retained Version or compensates by deleting shared content-addressed bytes. This boundary does not close the remaining authenticated participant, creator, full-release or abandonment proof gaps.
- **Current delivery gap:** Export requires an explicitly configured HTTPS `asset.store.public-base-url` ending at `/assets`, rejects private endpoint aliases, and never generates public locations from `ASSET_STORE_ENDPOINT`. This check does not authenticate or provision an approved origin. `AssetController` still exposes only `POST /assets`; no client GET/download or Gateway `/assets/**` rewrite is enabled. Missing origin/readiness proof remains an activation gate; private MinIO is not public delivery.
- **Asset identifier drift:** `game_assets.tenant_id` still accepts a REST `tenantId` string and the public asset row key is a `BIGSERIAL`. The owner-local export now qualifies its Version through Game Design's canonical tenant/Version provenance, but that does not migrate the public asset API or authorize an association by numeric equality. Account's separately proved identity associations are not an asset mapping authority; private numeric keys must not become canonical logical identities.
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
- `data` – retained ordinary binary source bytes. Frozen snapshot references prevent changes to the source identity, name, type or bytes and prevent deletion. A future metadata-only model must introduce equivalent immutable retained repair evidence before removing this source.
- `created_at` – upload timestamp

Future metadata-only storage may replace `data` with fields such as `storage_key`, `content_hash`, and `size_bytes`, but only if the new schema preserves the same repair invariant: Published/Active releases must be exactly reproducible for as long as their assets remain non-Retired or design-history reachable.

Version-scoped ordinary-asset selection uses the owner-local mapping table below. Its Draft writer verifies exact canonical Version provenance and same-tenant source assets. An authenticated public creator mapping surface remains required work; private owner-row identifiers are not external identity.

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
list or mutable draft mappings. The durable header proves the complete selection, including a genuinely empty set; a missing header is unavailable evidence, not an empty selection. The ordinary-byte producer implements this owner-local projection; the publishing tracker records executed and outstanding proof.

`(tenant_id, version_id)` is unique in `version_asset_artifact`. This enum list is the canonical schema contract for persistence and API validation. Artifact transitions use checked tenant/version/epoch CAS. This does not complete the separate Version lifecycle and global publication/purge fence.

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
- The persisted artifact row freezes complete candidate object proof before external writes. Durable snapshot readback supports exact ordinary-byte reproduction after process loss; incomplete retained rows remain unavailable. Shared-object purge remains denied until independent authority/reachability proof exists. See the lifecycle and repair/purge sections below.
- `CanDeleteVersionAssets` now also fails closed on live launch-descriptor references and on approved template remap sets that still name the source or target version, so purge cannot silently remove bytes still needed by current launch and replacement-cutover control-plane truth.
- `version_asset_purge_workflow` is now the retained workflow-status surface for purge start/finalization outcomes.

A basic repository (`GameAssetRepository`) and service implementation
(`GameAssetServiceImpl`) persist uploads through the service-local jOOQ/PostgreSQL data boundary.

At publish time, the owner locks the canonical Draft Version, freezes its mappings and actual-byte proofs durably, and independently reads them before constructing the candidate. Duplicate file-name usage keys, invalid text and the reserved `manifest.json` key fail closed before object-store writes. Complete manifest/object proof is recorded and independently read from the lifecycle row before conditional content-addressed writes; every successful or lost-acknowledgement write requires exact byte/type/length readback. Unresolved writes remain outcome-pending, not permission to delete or remint. No process-local cache is recovery authority. Configured HTTPS `/assets` URLs remain delivery locations pending approved origin/provisioning/readiness proof; private MinIO and object existence establish neither release nor public-delivery authority. See [Game Design Service Architecture](README.md) and the [publishing tracker](../../../project-management/implementation-tracking/game-authoring-publishing-and-activation.md).

Ordinary-byte retries and repair compare the complete candidate against its immutable durable evidence before conditional writes and exact readback. They cannot overwrite a published content key or reinterpret absent retained proof as an empty set. Full release repair still requires all applicable owner-source and release evidence; shared-object purge remains separately gated.

The export boundary persists the artifact `STAGED` intent and complete immutable candidate manifest/object evidence before the first object-store write. Conditional content-addressed writes require exact byte readback; an object-store timeout or lost response remains nonterminal until that proof resolves the outcome. Only the owner-local artifact and complete release-attestation commit can make bytes launchable, and a storage listing cannot substitute for that authority. The ordinary-byte producer implements intent/candidate storage and readback; the separate owner-controlled reachability/abandonment and cleanup workflow remains incomplete.

In the target contract, the `published_release_bundle` attestation must reference the final asset state for the version by including `manifestHash` and mandatory actual-byte `artifactDigests[]` entries exposed through Game Design’s `GetPublishedReleaseBundle` API. Activation, cutover preflight, and repair tooling must consume the API instead of reconstructing asset state from `version_asset_artifact` and version metadata separately.

`published_release_bundle` is persisted in the Game Design Service schema. Game Design owns the table shape, Flyway migrations, and attestation writes for that record; other services consume the attestation only through `GetPublishedReleaseBundle(tenantId, versionId)` and must not treat it as a shared-schema artifact.

### Interaction with Script-Only Patches

Script-only patches (see `system-architecture-versioning-runtime.md`) do not change assets or data stored in `game_assets` / `version_asset`. Ordinary full-version publication selects only its exact owner-qualified mappings. Retries and repair read the durable snapshot rather than the current tenant asset list. Missing snapshot proof is denied; changed bytes or mappings require a new Version, with no process-local fallback.

### Asset Lifecycle and Publish Workflow

The publish workflow uses a dedicated workflow step to export assets and update
manifest metadata:

The target publication topology follows [ADR 0095](../../decisions/adr-0095-content-addressed-published-assets-with-cas-lifecycle-authority.md) and [ADR 0096](../../decisions/adr-0096-attested-publication-gate-and-quarantined-failed-assets.md):

- Every binary and derived artifact is hashed from its actual bytes with mandatory SHA-256. The attested manifest records a stable usage key, immutable content-addressed object key, digest, content type/schema, and delivery location; URLs, names, and byte lengths are not byte attestation.
- Candidate bytes and manifests are built and verified in a private staging or quarantine namespace. Public manifest and object keys are immutable and content-addressed, and no candidate becomes a runtime discovery surface before the complete release attestation succeeds.
- Retrying the same verified bytes is idempotent. Changed bytes require a new content key and, for an attested release, a new version; a retry or repair never overwrites a live key with an unverified candidate.
- `version_asset_artifact` and Version lifecycle state/epoch remain the lifecycle authorities. Storage listings, object existence, and CDN responses are delivery evidence only and cannot establish launch, retirement, or purge eligibility. Artifact saves use checked tenant/version epoch CAS; incomplete Version lifecycle CAS and global reachability/fence proof still block target abandonment, retirement and physical purge.

Artifact lifecycle states for a `(tenantId, versionId)` prefix are explicit:

- `STAGED` – publish attempt has durably reserved the candidate and records its immutable candidate evidence; candidate bytes may be pending or partially written while the version is not yet Published.
- `EXPORTED_UNATTESTED` – candidate bytes and `manifest.json` have been exported and `manifestHash` is known, but the immutable `published_release_bundle` attestation has not yet been committed.
- `PUBLISHED` – publish succeeded, `manifestHash` is attested in `published_release_bundle`, and the immutable bytes for the version are launchable.
- `FAILED` – publish workflow failed for this version.
- `TOMBSTONED` – an eligible retired `PUBLISHED` release or an abandoned `FAILED` artifact is quarantined for diagnostics and excluded from activation paths.
- Target `PURGE_IN_PROGRESS` – the purge workflow has atomically fenced the exact frozen keys for deletion. The current artifact row CAS does not establish the complete global reachability/purge fence, and physical deletion remains denied.
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

- Every artifact transition is persisted with checked `state_epoch` CAS; a failed CAS means another workflow changed state and callers must reload and re-evaluate. Every Version transition must use its corresponding owner fence as well; artifact CAS alone is not Version lifecycle or reachability proof.
- The durable publish workflow and operator runbooks must both use this same state record; object-store state is never treated as authoritative by itself.
- `PUBLISHED` is the only success state that may be treated as launchable. Object-store bytes in `STAGED` or `EXPORTED_UNATTESTED` are not publish-complete on their own.

- For each `(tenantId, versionId)` the durable publish workflow runs an `ExportAssets` step that:
  - Freezes the exact owner-qualified `version_asset`/`game_assets` selection durably; unmapped tenant assets are excluded. The candidate is built privately, actual bytes are SHA-256 hashed, and complete immutable candidate proof is committed/read back before external writes. This does not enable public delivery.
  - Copies the selected ordinary asset bytes from `game_assets.data` (or a future equivalent immutable repair source) into private candidate storage, verifies the complete candidate, and only then publishes immutable content-addressed objects. A tenant/version prefix may remain a delivery grouping, but it is not the object identity or lifecycle authority.
  - Writes the version-scoped `manifest.json` as a content-addressed immutable object after candidate verification; it must not overwrite an attested manifest key.
  - Updates version metadata with the manifest location.
  - Transitions `version_asset_artifact` from `STAGED` to `EXPORTED_UNATTESTED`.
  - Rejects missing or inconsistent mapped sources; composite foreign keys and owner readback bind each exact asset to its tenant and Version. Missing snapshot evidence is not permission to select the current tenant list.
- **Target-state retry behavior:** Once the frozen export snapshot exists, rerunning `ExportAssets` for the same `(tenantId, versionId)` reuses that snapshot, recomputes and verifies the same content-addressed bytes, and leaves the version metadata consistent. It must not re-select assets after the snapshot is frozen, and it must not overwrite an attested public key. Changed bytes require a new key and release version.
- **Ordinary-byte implementation boundary:** Retry and repair reuse the persisted source snapshot and exact recorded candidate across process loss. An absent snapshot or incomplete retained candidate is unavailable, including for Retired Versions; no lifecycle state permits tenant-wide reselection or synthesized empty evidence.
- A later `FinalizePublishedRelease` step must read the computed `manifestHash`, write `published_release_bundle`, and only then transition `version_asset_artifact` from `EXPORTED_UNATTESTED` to `PUBLISHED`. If the attestation write has a lost or ambiguous response, the artifact remains `EXPORTED_UNATTESTED` and reconciliation-required; it may move to `FAILED` only after exact readback proves that no `published_release_bundle` exists for the tenant/version at all and no unresolved attestation write or cleanup ambiguity remains. Any existing row, including a mismatched attestation, keeps the artifact nonterminal until owner reconciliation determines the outcome; a matching committed attestation permits reconciliation to `PUBLISHED` and never `FAILED`. Implementations must not expose launchable `PUBLISHED` assets without a matching release attestation.
- The `EXPORTED_UNATTESTED` readback and any transition to `FAILED` must be serialized against concurrent publication for the same `(tenantId, versionId)`. The owner transaction (or one shared lifecycle CAS/lock fence held across the readback and state transition) must lock or compare the artifact row and exact `published_release_bundle` identity together, re-read the bundle immediately before deciding `FAILED`, and reject a stale state epoch. A matching bundle that committed before or during the readback reconciles the artifact to `PUBLISHED`; a missing bundle can transition to `FAILED` only when the same fenced readback proves no publication committed and no external write remains ambiguous. A later failure must never demote a matching publication or delete its version/assets.
- Current publication recovery retains the Version and candidate evidence and reconciles complete bundle readback; uncertain export and finalization remain nonterminal. Focused proof must distinguish committed-bundle, proved rollback/no-bundle, incomplete or mismatched proof, and lost-response branches. Ordinary-byte evidence does not establish the remaining complete owner-derived release or activation boundary.
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
- Repair must also read `GetVersionAssetArtifactState(tenantId, versionId)` and prove expected state, epoch and manifest hash before byte writes. The ordinary producer checks the recorded complete candidate before conditional content-addressed writes; that is not a substitute for the complete published release and owner-source checks.
- Repair must materialize candidate bytes away from published keys and verify every attested object and manifest digest before writing any missing immutable object. It may restore only exact attested content-addressed objects; if a repair source or digest is unavailable, it fails closed and requires a new version.
- Repair reads and verifies the durable per-version snapshot, every exported source-row identity and content digest. Missing retained snapshot proof fails closed rather than guessing from current Draft assets; publishing a new Version is required if the attested bytes cannot be reproduced.
- Ordinary repair may regenerate only the exact retained `game_assets.data` bytes recorded by that snapshot. Database guards retain referenced identity, filename, content type and bytes; replacing content requires a new source row and new Version mapping.
- If a future storage model replaces `game_assets.data` with metadata plus object-store handles, the replacement repair source must be immutable and retained for every non-Retired or design-history-reachable release. A mutable draft object key by itself is not a valid repair source.
- The repair workflow may only regenerate object-store bytes that reproduce the existing attested `manifestHash` and every mandatory actual-byte digest in `artifactDigests[]`.
- If regenerated bytes would change the attestation payload, the workflow must fail closed and require a new `versionId` rather than mutating the published release in place.

Required deterministic repair/purge failure vocabulary:

- `VERSION_ASSET_NOT_DELETABLE` when `CanDeleteVersionAssets` rejects eligibility.
- `ASSET_ARTIFACT_STATE_CONFLICT` when `state_epoch` CAS fails or the lifecycle row no longer matches the caller's proof.
- `ASSET_USAGE_KEY_COLLISION` when duplicate file-name-derived manifest keys or the reserved `manifest.json` key make the export ambiguous.
- `ASSET_EXPORT_CANDIDATE_CONFLICT` when the proposed manifest or artifact evidence differs from its immutable recorded candidate.
- `CONTENT_ADDRESSED_PURGE_PROOF_REQUIRED` when shared-object deletion lacks its complete reachability and purge authority.
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

- Eligibility checks and purge start must not run as a loose "check then delete" pair. The target order is: `TombstoneVersionAssets` performs the pre-tombstone authority/reachability/abandonment check and CAS transition; `CanDeleteVersionAssets` performs the post-tombstone fail-closed recheck; `BeginPurgeVersionAssets` performs the final atomic recheck and `TOMBSTONED -> PURGE_IN_PROGRESS` CAS. Current artifact saves use database epoch CAS, but the authority/reachability/abandonment checks and Version/global fence remain incomplete. Missing-tenant-matching-version/no-bundle eligibility (including a wrong-tenant version row) also currently fails open. Physical deletion is denied rather than relying on those partial checks.
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
- Per-tenant draft asset quota is 2 GiB. **Target admission rule:** exclude bytes retained solely because a `version_asset` reference keeps them reachable from a Published, Active, or Failed version. The owner-local mapping exists, but the upload path performs no quota accounting or enforcement and does not prove the exemption. The quota query must derive exempt bytes from normalized references rather than from tenant-wide export contents or object-store path conventions. Writes that would exceed the target quota must fail with `ASSET_QUOTA_EXCEEDED`. A future metadata-only storage model may measure retained immutable draft-object bytes instead, without changing the draft-only target boundary.
- Upload/download APIs must support streaming/chunked transfer at the transport layer; services must not require buffering full payloads in memory before persistence.
- Publish/export workers must process assets in bounded batches (configurable), with backpressure metrics to avoid starving version publish orchestration.
- Quota and size limits must be configurable per environment but default to the values above when unset.

`game_assets` is the canonical design-time store for asset metadata and bytes. Its retained `data` values become the repair source only after the frozen version-scoped export snapshot identifies the exact rows and hashes. Database guards preserve referenced source identity, name, type and bytes. Missing retained snapshot/candidate evidence fails closed; a future metadata-only storage model must supply equivalent immutable repair evidence before removing these bytes.

Published assets retain their `game_assets` rows for design history and exact-bytes repair. Target `FinalizePurgeVersionAssets` deletes only frozen objects authorized by the complete global reachability/purge fence, never source rows or `game_assets.data`. Current physical deletion is denied pending that proof; a prefix or lifecycle CAS alone cannot authorize deletion.

A separate target maintenance workflow may mark an unreferenced `game_assets` row as `obsolete` and remove that row and its `game_assets.data` bytes. That workflow must independently prove global normalized `version_asset` reachability across every version state, global normalized `revision_asset` and branch reachability, and any other retained design-history dependency, then perform its own CAS-guarded maintenance transition. Assets referenced by any retained version or history path must never be deleted, and their binary contents must not be modified in place. The current version-artifact purge workflow is not this source-row maintenance workflow and must not delete `game_assets` rows or data. A future metadata-only storage model may apply the same separate reachability/CAS boundary to unreferenced draft objects. The exact retention policy (for example “keep assets referenced by the last N versions per tenant”) is configurable but should be documented alongside operational runbooks.

The export location is configured with `ASSET_STORE_ENDPOINT`,
`ASSET_STORE_BUCKET`, `ASSET_STORE_REGION`, `ASSET_STORE_ACCESS_KEY`, and
`ASSET_STORE_SECRET_KEY`. For development, the Docker Compose stack runs a
`minio` container that satisfies these variables.
