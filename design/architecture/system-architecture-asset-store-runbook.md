# FireMUD Asset Store Runbook

This runbook describes operational procedures for the **asset store** backing game content (binary design assets such as icons, audio, themes, and exported bundles).

For the architecture of asset storage, see `design/architecture/microservices/game-design-service/asset-storage.md`.

Game Design owns publication coordination, release descriptors, asset lifecycle, CAS state, and purge eligibility. Platform Operations owns MinIO/object-storage, CDN, credentials, availability, and delivery infrastructure. Operators use the Game Design control-plane evidence for lifecycle decisions; bucket listings and CDN responses are not lifecycle authority.

## Implementation Status

- `CanDeleteVersionAssets`, `GetVersionAssetArtifactState`, `RepairPublishedVersionAssets`, `TombstoneVersionAssets`, `BeginPurgeVersionAssets`, `FinalizePurgeVersionAssets`, and `GetVersionAssetPurgeStatus` are live Game Design control-plane APIs. The `version_asset_artifact` proof row currently records lifecycle state, state epoch, manifest hash, exported version number, and exported manifest asset keys. In the current first slice, `RepairPublishedVersionAssets` repairs ordinary assets from `game_assets.data` to those persisted exported version-scoped keys only while the process-local frozen version selection remains available; the proof row alone is not restart-safe, and an unavailable selection fails closed with `REPAIR_VERSION_SCOPE_UNAVAILABLE`.
- Content-addressed private-candidate publication and producer-owned derived-artifact handoff remain target-state. The current first slice still repairs ordinary bytes from `game_assets.data`, exports version-scoped objects, and persists a narrower `published_release_bundle` that lacks the complete target manifest-schema and mandatory actual-byte artifact-digest fields.
- The live `TombstoneVersionAssets` implementation accepts only `FAILED` and `PURGE_FAILED`; target tombstoning of an eligible retired `PUBLISHED` release is not implemented. The runbook must fail closed for that path rather than claim the current API supports it.
- The target failed-candidate path requires the Game Design-owned, idempotent `AbandonFailedVersionAssets` record/read proof bound to the caller tenant, `FAILED` version/artifact epochs, request identity/digest, and no release reachability. That abandonment record/API is not implemented; the current tombstone path does not enforce it.
- The current `CanDeleteVersionAssets` implementation has a fail-open gap: when the artifact exists but no caller-tenant version authority exists (including when only a wrong-tenant row exists), and no caller-tenant release bundle remains, it can return `deletable=true`. Operators must treat both absent and wrong-tenant-only authority as non-eligible and must not tombstone or purge until this guard is closed; the owning target contract and focused proof are tracked in [Asset Storage](./microservices/game-design-service/asset-storage.md#deletion-eligibility-authority).
- Lifecycle CAS/lock serialization described below is target-only. The current repositories compare artifact/Version epochs in service memory and then update each row by ID without an epoch predicate or affected-row check; neither update is concurrency-safe and neither proves serialization against a competing lifecycle writer. This blocks using `expectedVersionStateEpoch` as durable abandonment/retirement invalidation proof.
- The live `CompareAndSetVersionState` operation has an additional transition-safety gap: it accepts any non-null lifecycle enum after its in-memory epoch check, without requiring a committed release bundle/artifact for `DRAFT -> PUBLISHED` or enforcing active-instance/template/in-flight-activation predicates before retirement. Operators must not treat this API as activation- or retirement-safe until the legal transition matrix and focused proof are implemented.
- The target destructive lifecycle operations share the ADR 0048 owner-local operation envelope and durable result ledger: caller-supplied operation/workflow identity, canonical `mutationDigest/v1`, exact replay, and `IDEMPOTENCY_CONFLICT` for changed input. The current first slice has no shared ledger/digest contract; `BeginPurgeVersionAssets` still mints a random workflow ID, so lost responses cannot be reliably replayed.
- Purge failure status is not currently durable: `FinalizePurgeVersionAssets` saves `PURGE_FAILED`/failed-workflow markers and rethrows inside `@Transactional`, so rollback can discard the markers after object deletion. A resulting `PURGE_IN_PROGRESS` row has no reliable resume evidence until an independent durable failure/reconciliation boundary and focused deletion/post-delete-save proof are added.
- If publish fails after `published_release_bundle` may have committed, target recovery reads back bundle/attestation state and never deletes Version as compensation. The current catch path still attempts that delete; the non-cascading bundle foreign key can make the delete fail, so the promised terminal result is not reliable.
- The current first slice has one `ASSET_STORE_ENDPOINT` used by the S3 client and generated manifest URLs; it does not implement the target private-origin/public-delivery split or `ASSET_STORE_PUBLIC_BASE_URL`. The current Gateway has no `/assets/**` route or asset-store route ID, and private MinIO is not public delivery; target `/assets/**` delivery remains deferred pending a separate approved public origin/provisioner.

## Health Checks

- Monitor storage availability, latency, and error rates.
- Verify supported Game Design uploads and the persisted Game Design artifact/bundle metadata reads used by the current repair path. Game Design does not currently expose a version-asset object-store GET/HEAD or byte-readback operation; current repair re-exports from the process-local frozen snapshot and fails closed after process loss. Client-facing asset download remains target-only because the approved public `/assets/**` origin is not implemented.

## Incident Handling

1. **Asset Store Unavailable**
   - Confirm whether the outage is limited to one region or global.
   - Check upstream providers (object storage service) and networking.
2. **Degraded Performance**
   - Monitor throughput and latency; consider scaling the asset store or caching layers if documented.
3. **Data Integrity Concerns**
   - **Target-state integrity check:** Verify the attested manifest digest and mandatory per-object SHA-256 content digests against delivery evidence. Missing object digests, mutable delivery keys, or an unattested manifest are publish contract violations; storage availability never makes a candidate launchable. The current first slice exposes only persisted `manifestHash`, exported manifest asset keys/version, and artifact/bundle metadata reads; it has no Game Design object-byte GET/HEAD or readback operation, and its narrower release bundle has no `artifactDigests[]`, so operators must not claim current actual-byte attestation from this check.
   - Coordinate with backup and recovery procedures if persistent corruption is suspected.

## MinIO Deployment and Configuration

When using a self-hosted MinIO cluster as the asset store:

1. **Deploy MinIO**
   - Deploy the manifests under `k8s/minio/`.
   - Create a `minio-credentials` Secret with `accessKey` and `secretKey` keys.
   - Rotate these credentials via the MinIO credentials manifest (`k8s/minio/credentials.yaml`) and follow the [Environment & Secrets Management](./infrastructure/environment-and-secrets.md#minio-credentials) guidelines when updating keys.
   - The MinIO deployment and the bucket/CORS bootstrap below do not provision the `firemud-assets-writer` identity or its bucket-scoped policy. No trusted identity/policy provisioner is included here; the shown deployment/bootstrap is incomplete and Game Design remains blocked until an approved trusted bootstrap provisions that identity and policy and materializes its Secret. Never use `minio-credentials` as the Game Design writer credential.
2. **Create the `firemud-assets` bucket**

   ```bash
   kubectl run mc --rm -it --restart=Never \
     --image=minio/mc:RELEASE.2024-05-09T17-04-24Z@sha256:3e9666a093d0a8fcbbac606346c415ae9277a0ca96989a6bdddd3d03e90a21b4 \
     --overrides='{"spec":{"containers":[{"name":"mc","env":[{"name":"MINIO_ACCESS_KEY","valueFrom":{"secretKeyRef":{"name":"minio-credentials","key":"accessKey"}}},{"name":"MINIO_SECRET_KEY","valueFrom":{"secretKeyRef":{"name":"minio-credentials","key":"secretKey"}}}]}]}}' \
     --command -- sh -c '
       export MC_HOST_local="http://${MINIO_ACCESS_KEY}:${MINIO_SECRET_KEY}@minio:9000" &&
       mc mb --ignore-existing local/firemud-assets
     '
   ```

3. **Keep the bucket private; the approved public delivery origin owns browser CORS**

   The current first slice has no public asset route or provisioner, so do not run this CORS step as part of the current deployment. After a separate approved public `/assets/**` origin/provisioner exists, Platform Operations must configure that public delivery origin, or its Gateway/CDN forwarder, to emit or forward the exact CORS response for the approved browser origin. `ASSET_STORE_CORS_ORIGIN` and the bootstrap below may configure supporting MinIO bucket CORS only when MinIO participates behind that separately approved public delivery path; they never authorize direct browser use of the private storage endpoint. Private-bucket CORS alone is not public delivery authority, does not make MinIO public, and must not be replaced with anonymous bucket access.

   ```bash
   if [[ -z "${ASSET_STORE_CORS_ORIGIN:-}" || "$ASSET_STORE_CORS_ORIGIN" == *"*"* || "$ASSET_STORE_CORS_ORIGIN" == *"?"* || "$ASSET_STORE_CORS_ORIGIN" == *"["* || "$ASSET_STORE_CORS_ORIGIN" == *"]"* ]]; then
     echo "ASSET_STORE_CORS_ORIGIN must be set to a specific, non-wildcard origin" >&2
     exit 1
   fi
   if [[ ! "$ASSET_STORE_CORS_ORIGIN" =~ ^https://[A-Za-z0-9.-]+(:[0-9]+)?$ && ! "$ASSET_STORE_CORS_ORIGIN" =~ ^http://localhost:[0-9]+$ ]]; then
     echo "ASSET_STORE_CORS_ORIGIN must use https, except for a local localhost origin" >&2
     exit 1
   fi

   kubectl run mc --rm -it --restart=Never \
     --image=minio/mc:RELEASE.2024-05-09T17-04-24Z@sha256:3e9666a093d0a8fcbbac606346c415ae9277a0ca96989a6bdddd3d03e90a21b4 \
     --overrides="{\"spec\":{\"containers\":[{\"name\":\"mc\",\"env\":[{\"name\":\"MINIO_ACCESS_KEY\",\"valueFrom\":{\"secretKeyRef\":{\"name\":\"minio-credentials\",\"key\":\"accessKey\"}}},{\"name\":\"MINIO_SECRET_KEY\",\"valueFrom\":{\"secretKeyRef\":{\"name\":\"minio-credentials\",\"key\":\"secretKey\"}}},{\"name\":\"ASSET_STORE_CORS_ORIGIN\",\"value\":\"${ASSET_STORE_CORS_ORIGIN}\"}]}]}}" \
     --command -- sh -c '
       export MC_HOST_local="http://${MINIO_ACCESS_KEY}:${MINIO_SECRET_KEY}@minio:9000" &&
       mc anonymous set private local/firemud-assets &&
       printf "%s\\n" \
         "<CORSConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" \
         "  <CORSRule>" \
         "    <AllowedOrigin>${ASSET_STORE_CORS_ORIGIN}</AllowedOrigin>" \
         "    <AllowedMethod>GET</AllowedMethod>" \
         "    <AllowedHeader>*</AllowedHeader>" \
         "  </CORSRule>" \
         "</CORSConfiguration>" > /tmp/cors.xml &&
       mc cors set local/firemud-assets /tmp/cors.xml
     '
   ```

4. **Service configuration**
   - Once the trusted bootstrap has provisioned `firemud-assets-writer`, the Game Design Service is the **sole writer** to the asset bucket and uses `ASSET_STORE_*` environment variables to export assets and manifests during publish workflows. Target runtime services and clients consume published assets via CDN or gateway URLs derived from the manifest; they do not write directly to the bucket. The current private MinIO endpoint is not public delivery.
   - Keep the bucket private. The gateway or CDN origin uses authenticated object-store credentials and exposes only attested immutable published objects; private staging, quarantine, `FAILED`, and `EXPORTED_UNATTESTED` objects must not be publicly readable.
   - `ASSET_STORE_ENDPOINT` is the private authenticated MinIO/S3 API used by Game Design reads and writes, for example `http://minio:9000` inside the cluster. It is never the public gateway delivery URL.
   - The target split uses `ASSET_STORE_PUBLIC_BASE_URL=https://<gateway-domain>/assets` only to generate public manifest links. That setting is not implemented in the current single-endpoint first slice; target `/assets/**` delivery remains pending a separate approved public origin/provisioner. Operators must not compensate by granting anonymous bucket-wide access. Any future public origin must expose only attested immutable published objects.
   - Keep validation paths distinct: an unauthenticated GET to the private `ASSET_STORE_ENDPOINT` must be rejected, while a public-origin delivery test may request only an attested immutable published object.
5. **Scope of stored data**

   The asset store holds only binary design-time assets and version-scoped `manifest.json` files managed by the Game Design Service. Logical world and entity templates remain in PostgreSQL schemas owned by World Management, Entity Management, and related domain services as described in their architecture documents; they are **not** stored in the object store.

6. **Removing published versions**

   Removing assets for a published version must be coordinated with the version
   lifecycle described in
   [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md):

   The target lifecycle preserves explicit state evidence: `EXPORTED_UNATTESTED -> FAILED` only after exact no-bundle/no-ambiguity readback; `FAILED -> TOMBSTONED` only through explicit abandonment and pre-tombstone authority; `TOMBSTONED -> PURGE_IN_PROGRESS` only through the final CAS/reachability recheck; `PURGE_IN_PROGRESS -> PURGED` only after frozen-proof deletion succeeds; and `PURGE_IN_PROGRESS -> PURGE_FAILED` on a retryable deletion/finalization failure. **`PURGED` remains a retained terminal metadata row**; physical bytes may be removed only through the owner workflow. These target transitions are not all live in the current first slice.

   - **Target-state procedure only:** For a published/release artifact, complete the canonical `RetireVersion` workflow before calling `CanDeleteVersionAssets(tenantId, versionId)`. It must leave a **retired** version with no `game_instances` rows referencing the `version_id` as `runtime_version` and with no canonical Game Design release/version/launch descriptor or attestation resolving that version; the legacy untenant-qualified `game_manifest` is diagnostic/disposition-only and cannot gate retirement or launch. Retirement is necessary but not sufficient for asset deletion. No safe `RetireVersion` operation exists in the current service. Operators must stop rather than use `CompareAndSetVersionState` as a substitute, because that operation is not retirement-safe. An abandoned failed candidate follows the separate failed-candidate eligibility path below and must not be converted to `RETIRED` merely to enable purge.
   - Retired eligibility must also account for **design-time dependencies**: the version must not be referenced by any game templates. Operators should validate that no normalized `game_template_*_ref` rows in the Game Design Service still reference `(tenantId, versionId)` before requesting the corresponding purge through Game Design.
   - Asset deletion eligibility must account for design-history reachability as well as version mappings. Use the Game Design control-plane check `CanDeleteVersionAssets(tenantId, versionId)` that validates no non-Retired `version_asset` references remain, no reachable `revision_asset` / branch references require retained bytes, and no template or launch metadata still points to the version prefix. Until the current fail-open guard is closed, a result for an artifact with no caller-tenant matching version authority—including a wrong-tenant-only row—must be treated as non-eligible regardless of the API's current boolean result.
   - Before purge or repair, read `GetVersionAssetArtifactState(tenantId, versionId)` and treat its `artifactState`, `stateEpoch`, `manifestHash`, `exportedVersionNumber`, and exported manifest asset keys as the authoritative proof row. Do not infer lifecycle state from bucket listings.
   - Do not run deletion as a separate check + manual delete sequence. The target order is `TombstoneVersionAssets(...)` first: it performs the pre-tombstone caller-tenant authority, retirement-or-abandonment, frozen-proof, and reachability checks and its CAS transition. For an abandoned failed candidate, record durable proof with `AbandonFailedVersionAssets(...)` before tombstoning; for an eligible retired `PUBLISHED` release, no abandonment proof is required. Reload the artifact row, run post-tombstone `CanDeleteVersionAssets(tenantId, versionId)`, then call `BeginPurgeVersionAssets(...)` with the caller-supplied workflow identity. Current implementation/order drift includes a fail-closed pre-tombstone state check: `CanDeleteVersionAssets` rejects pre-tombstone `FAILED`/`PUBLISHED`, while tombstone lacks the target authority checks and retired `PUBLISHED` transition. Separately, the current `CanDeleteVersionAssets` authority lookup can fail open when no caller-tenant version row exists, including a wrong-tenant-only row; that remains a non-eligibility warning until the guard is fixed.
   - Operators must verify the persisted artifact lifecycle row (`version_asset_artifact`) is `TOMBSTONED` before issuing purge actions. **Target-state procedure only:** `BeginPurgeVersionAssets` must re-check eligibility and transition state to `PURGE_IN_PROGRESS` before bytes are removed, and `FinalizePurgeVersionAssets` must transition to retained terminal state `PURGED` after bytes are removed. The current path is subject to the non-atomic implementation-status gap above; do not infer lifecycle state solely from object-store contents.
   - `EXPORTED_UNATTESTED` means bytes were exported but publish did not complete. Such prefixes are not launchable and must be repaired or failed through the documented workflow; operators must not treat them as equivalent to `PUBLISHED`.
   - Published-asset discovery and post-tombstone deletion recheck use `CanDeleteVersionAssets(tenantId, versionId)` and `GetVersionAssetArtifactState(tenantId, versionId)`; pre-tombstone authority/reachability belongs to `TombstoneVersionAssets`. The `version_asset` mapping checks are target-state because the current implementation has no mapping authoring path; operators must never delete referenced objects or bypass the Game Design reachability/CAS lifecycle. In particular, absence of a caller-tenant matching version authority, including a wrong-tenant-only row, remains a fail-closed stop condition until the current guard is remediated.
   - If object-store contents drift from the database (for example missing objects for a still-published version), do not use the current `RepairPublishedVersionAssets` path for Published/Active repair: it writes objects and `manifest.json` before `VersionAssetArtifactServiceImpl` performs the exact repair-match check, so a mismatch can write or overwrite live prefix keys before returning `REPAIR_ATTESTATION_MISMATCH`. Keep the release closed and escalate until repair either fails closed before every write or uses independently verified exact selection; process loss also remains `REPAIR_VERSION_SCOPE_UNAVAILABLE` for Published/Active, and a RETIRED version must not fall through to a fresh tenant-wide snapshot. The target content-addressed candidate and per-object digest procedure is defined in `asset-storage.md`.
   - The target `RepairPublishedVersionAssets(tenantId, versionId, expectedArtifactStateEpoch, repairWorkflowId)` operation may be used only after that pre-write exact-selection gate exists. It must produce deterministic `REPAIR_ATTESTATION_MISMATCH` or `ASSET_ARTIFACT_STATE_CONFLICT` outcomes and never mutate attested bytes on a mismatch; until then, operators must not recommend or invoke the current path as a repair procedure.
   - **Target-state content-addressed repair:** Because Published/Active versions are immutable, the target workflow must produce bit-for-bit identical bytes matching the existing `published_release_bundle` attestation returned by `GetPublishedReleaseBundle(tenantId, versionId)`, verify mandatory per-object actual-byte digests, and restore only exact immutable content-addressed objects. Producer-owned derived artifacts must come from their immutable producer contracts. If any required artifact cannot reproduce the attested bytes, fail closed rather than “fixing” the published version in place; this target procedure does not describe the current first-slice version-scoped repair path above.
   - **Target-state workflow only:** Route deletion through pre-tombstone `TombstoneVersionAssets(...)`, post-tombstone `CanDeleteVersionAssets(tenantId, versionId)`, `BeginPurgeVersionAssets(tenantId, versionId, expectedArtifactStateEpoch, purgeWorkflowId)`, and `FinalizePurgeVersionAssets(...)`. Every mutation carries the shared ADR 0048 operation envelope and caller-supplied workflow identity. The target workflow re-checks reachability and CAS state before deletion, and the lifecycle APIs retain terminal metadata after physical bytes are removed. The current path is subject to the order, idempotency, and non-atomic implementation gaps above.
   - Direct object-store commands such as `mc rm` are outside the supported lifecycle. Incident recovery may preserve or isolate evidence for escalation, but it must not bypass the Game Design reachability/CAS APIs or claim to finalize lifecycle state.

## Handling Failed Publish Versions

Occasionally the asset-export step of `PublishVersion` may fail in a way that
leaves a version incomplete or unusable:

- When the Game Design publication workflow coordinated by Temporal marks a
  version as **Failed**, the canonical Game Design release/version/launch
  descriptor and attestation surfaces must not make it launchable, and
  operators must not attempt to start game instances against it. Any retained
  legacy `game_manifest` row is diagnostic/disposition-only and is neither
  evidence nor a launch gate.
- **Target-state only:** Failed versions may have partially written candidates in a private quarantine namespace; do not delete those candidates manually. If there is no intention to retry publish, use the target Game Design `AbandonFailedVersionAssets(...)` operation to record durable abandonment, then invoke pre-tombstone `TombstoneVersionAssets(tenantId, versionId, expectedArtifactStateEpoch, tombstoneWorkflowId)` to prove caller-tenant `FAILED` authority, frozen proof, and no reachability before its CAS transition. **Target-state only:** the abandonment record and target authority/CAS transition are not implemented. The current exporter writes directly to the private `tenant/version/` prefix and has no separate quarantine namespace.
- **Target-state only:** Failed or `EXPORTED_UNATTESTED` candidates are never launchable, never used as fallback, and never repaired in place while runtime admission continues. A retry creates a new approved workflow attempt and either completes a fully attested `PUBLISHED` release or remains non-launchable. In the current first slice, a failed `PublishAttempt` is terminal for that workflow identity and no retry-publish or repair-version orchestration is exposed; only the published-artifact repair API exists.
- The canonical lifecycle states, transitions, bundle-readback rules, and target serialization fence are defined in [Asset Storage Setup](./microservices/game-design-service/asset-storage.md#asset-lifecycle-and-publish-workflow). Operators must preserve these local consequences:
  - `FAILED` and `EXPORTED_UNATTESTED` remain non-launchable and non-fallback; retry uses the documented workflow, while failed-candidate purge requires caller-tenant `FAILED` authority plus durable explicit abandonment proof.
  - A published/release purge requires caller-tenant `RETIRED` authority, and target `PUBLISHED -> TOMBSTONED -> PURGE_IN_PROGRESS -> PURGED` proceeds only through the control-plane APIs after eligibility and epoch reload. `PURGED` retains lifecycle metadata; physical deletion never removes the artifact row.
  - A failed-candidate purge uses the separate abandoned-candidate path and must not invent a `FAILED -> RETIRED` transition. `PURGE_FAILED` retry/resume and abandonment remain explicit workflow actions.
  - **Target-state only:** lifecycle transitions and publication/failure readback are CAS/lock serialized for both artifact and Version rows. The current row-ID updates have no database epoch predicate or affected-row check, so they must not be treated as concurrency protection; CAS loss requires fresh readback and approved workflow retry, never manual object-store edits. The target owner ledger also supplies exact operation replay/conflict handling under ADR 0048; the current random-only Begin workflow identity does not.
- Preferred recovery is to:
  - Investigate and fix the underlying issue (for example missing assets or
    permission errors).
  - **Target-state only:** Trigger a documented “retry publish” or “repair version” workflow in the Game Design or Logging & Admin Service so the Temporal-coordinated publication workflow re-runs the asset export step and transitions the version back to Draft/Published as appropriate. No retry-publish or failed-candidate repair-version operation is currently exposed by Game Design or Logging & Admin. Game Design does expose `RepairPublishedVersionAssets(...)` for an already-published artifact; it is exact-bytes repair against the existing release attestation, not a retry of failed publication. The current first slice writes a plain filename-to-URL
    `manifest.json` map without a `schemaVersion` field. The target manifest
    contract adds an explicit schema version; until that target is implemented,
    operators must not claim current schema-versioned manifest discovery. For
    Published/Active releases, do not use repair to move the release to a newer
    manifest schema version; repair is exact-bytes only unless a separate
    re-attestation workflow is explicitly defined.
  - For Published/Active versions, treat the workflow as exact-bytes repair only: it must verify the current `GetPublishedReleaseBundle` attestation and fail if the regenerated bytes would change the attested `manifestHash`.
  - If clean-up is required after deciding to abandon a Failed version, first record the durable abandonment proof with `AbandonFailedVersionAssets(...)`, then use pre-tombstone `TombstoneVersionAssets(...)`, post-tombstone `CanDeleteVersionAssets(tenantId, versionId)`, and the **target-state CAS-guarded** `BeginPurgeVersionAssets(..., purgeWorkflowId)` / `FinalizePurgeVersionAssets(...)` workflow. The current abandonment, order, idempotency, and CAS paths are not implemented; do not remove the corresponding prefix directly.
