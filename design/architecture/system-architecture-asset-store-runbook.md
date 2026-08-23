# FireMUD Asset Store Runbook

This runbook describes operational procedures for the **asset store** backing game content (binary design assets such as icons, audio, themes, and exported bundles).

For the architecture of asset storage, see `design/architecture/microservices/game-design-service/asset-storage.md`.

Game Design owns publication coordination, release descriptors, asset lifecycle, CAS state, and purge eligibility. Platform Operations owns MinIO/object-storage, CDN, credentials, availability, and delivery infrastructure. Operators use the Game Design control-plane evidence for lifecycle decisions; bucket listings and CDN responses are not lifecycle authority.

## Implementation Status

- `CanDeleteVersionAssets`, `GetVersionAssetArtifactState`, `RepairPublishedVersionAssets`, `TombstoneVersionAssets`, `BeginPurgeVersionAssets`, `FinalizePurgeVersionAssets`, and `GetVersionAssetPurgeStatus` are live Game Design control-plane APIs. The `version_asset_artifact` proof row currently records lifecycle state, state epoch, manifest hash, exported version number, and exported manifest asset keys. In the current first slice, `RepairPublishedVersionAssets` repairs ordinary assets from `game_assets.data` to those persisted exported version-scoped keys only while the process-local frozen version selection remains available; the proof row alone is not restart-safe, and an unavailable selection fails closed with `REPAIR_VERSION_SCOPE_UNAVAILABLE`.
- Content-addressed private-candidate publication and producer-owned derived-artifact handoff remain target-state. The current first slice still repairs ordinary bytes from `game_assets.data`, exports version-scoped objects, and persists a narrower `published_release_bundle` that lacks the complete target manifest-schema and mandatory actual-byte artifact-digest fields.
- The live `TombstoneVersionAssets` implementation accepts only `FAILED` and `PURGE_FAILED`; target tombstoning of an eligible retired `PUBLISHED` release is not implemented. The runbook must fail closed for that path rather than claim the current API supports it.
- The current first slice has one `ASSET_STORE_ENDPOINT` used by the S3 client and generated manifest URLs; it does not implement the target private-origin/public-delivery split or `ASSET_STORE_PUBLIC_BASE_URL`. The current Gateway has no `/assets/**` route or asset-store route ID, and private MinIO is not public delivery; target `/assets/**` delivery remains deferred pending a separate approved public origin/provisioner.

## Health Checks

- Monitor storage availability, latency, and error rates.
- Verify that upload and download operations succeed from the Game Design Service.

## Incident Handling

1. **Asset Store Unavailable**
   - Confirm whether the outage is limited to one region or global.
   - Check upstream providers (object storage service) and networking.
2. **Degraded Performance**
   - Monitor throughput and latency; consider scaling the asset store or caching layers if documented.
3. **Data Integrity Concerns**
   - Verify the attested manifest digest and mandatory per-object SHA-256 content digests against delivery evidence. Missing object digests, mutable delivery keys, or an unattested manifest are publish contract violations; storage availability never makes a candidate launchable.
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

3. **Keep the bucket private and configure CORS only for an approved public delivery origin**

   The current first slice has no public asset route or provisioner, so do not run this CORS step as part of the current deployment. After a separate approved public `/assets/**` origin/provisioner exists, set `ASSET_STORE_CORS_ORIGIN` to the exact browser origin that is authorized to consume that delivery path and run the bootstrap below. This configures browser-origin policy only; it does not make MinIO public and must not be replaced with anonymous bucket access.

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

   - Complete the canonical `RetireVersion` workflow before calling `CanDeleteVersionAssets(tenantId, versionId)`. It must leave a **retired** version with no `game_instances` rows referencing the `version_id` as `runtime_version` and no launchable listing in `game_manifest`; retirement is necessary but not sufficient for asset deletion.
   - Retired eligibility must also account for **design-time dependencies**: the version must not be referenced by any game templates. Operators should validate that no normalized `game_template_*_ref` rows in the Game Design Service still reference `(tenantId, versionId)` before requesting the corresponding purge through Game Design.
   - Asset deletion eligibility must account for design-history reachability as well as version mappings. Use the Game Design control-plane check `CanDeleteVersionAssets(tenantId, versionId)` that validates no non-Retired `version_asset` references remain, no reachable `revision_asset` / branch references require retained bytes, and no template or launch metadata still points to the version prefix.
   - Before purge or repair, read `GetVersionAssetArtifactState(tenantId, versionId)` and treat its `artifactState`, `stateEpoch`, `manifestHash`, `exportedVersionNumber`, and exported manifest asset keys as the authoritative proof row. Do not infer lifecycle state from bucket listings.
   - Do not run deletion as a separate check + manual delete sequence. `CanDeleteVersionAssets(tenantId, versionId)` is read-only and never tombstones an artifact. After eligibility is confirmed, the target workflow calls `TombstoneVersionAssets(tenantId, versionId, expectedArtifactStateEpoch, tombstoneWorkflowId)` for an eligible retired `PUBLISHED` release or for a `FAILED` artifact only after retry abandonment; reload the proof row and use its resulting epoch for `BeginPurgeVersionAssets(tenantId, versionId, expectedArtifactStateEpoch)`. The current implementation supports only `FAILED` and `PURGE_FAILED`, so an eligible retired `PUBLISHED` release must fail closed until that target transition is implemented.
   - Operators must verify the persisted artifact lifecycle row (`version_asset_artifact`) is `TOMBSTONED` before issuing purge actions. `BeginPurgeVersionAssets` must re-check eligibility and transition state to `PURGE_IN_PROGRESS` before bytes are removed, and `FinalizePurgeVersionAssets` must transition to retained terminal state `PURGED` after bytes are removed. Do not infer lifecycle state solely from object-store contents.
   - `EXPORTED_UNATTESTED` means bytes were exported but publish did not complete. Such prefixes are not launchable and must be repaired or failed through the documented workflow; operators must not treat them as equivalent to `PUBLISHED`.
   - Published-asset discovery and deletion eligibility use the live `CanDeleteVersionAssets(tenantId, versionId)` and `GetVersionAssetArtifactState(tenantId, versionId)` APIs. The `version_asset` mapping checks are target-state because the current implementation has no mapping authoring path; operators must never delete referenced objects or bypass the Game Design reachability/CAS lifecycle.
   - If object-store contents drift from the database (for example missing objects for a still-published version), the current first-slice procedure is to use live `RepairPublishedVersionAssets` to restore ordinary bytes from `game_assets.data` to the persisted exported version-scoped keys, recheck the `version_asset_artifact` proof row and attested `manifestHash`, and use the still-resident process-local frozen version selection. After process loss, or whenever that selection is unavailable, repair fails closed with `REPAIR_VERSION_SCOPE_UNAVAILABLE`; it must not infer selection from the persisted proof row. Never overwrite a published key with different content or rebuild a public prefix in place. Target-state repair instead builds and verifies a private content-addressed candidate with mandatory per-object actual-byte digests and producer-owned derived-artifact sources, as defined in `asset-storage.md`.
   - Use `RepairPublishedVersionAssets(tenantId, versionId, expectedArtifactStateEpoch, repairWorkflowId)` for Published/Active releases rather than ad hoc reruns. That workflow must fail with deterministic application outcomes such as `REPAIR_ATTESTATION_MISMATCH` or `ASSET_ARTIFACT_STATE_CONFLICT` instead of silently mutating attested bytes.
   - **Target-state content-addressed repair:** Because Published/Active versions are immutable, the target workflow must produce bit-for-bit identical bytes matching the existing `published_release_bundle` attestation returned by `GetPublishedReleaseBundle(tenantId, versionId)`, verify mandatory per-object actual-byte digests, and restore only exact immutable content-addressed objects. Producer-owned derived artifacts must come from their immutable producer contracts. If any required artifact cannot reproduce the attested bytes, fail closed rather than “fixing” the published version in place; this target procedure does not describe the current first-slice version-scoped repair path above.
   - Route deletion through `CanDeleteVersionAssets(tenantId, versionId)`, `TombstoneVersionAssets(...)` for an eligible retired Published release or an explicitly abandoned Failed artifact, `BeginPurgeVersionAssets(tenantId, versionId, expectedArtifactStateEpoch)`, and `FinalizePurgeVersionAssets(...)`. `CanDeleteVersionAssets` and `BeginPurgeVersionAssets` re-check reachability and CAS state before deletion; the lifecycle APIs retain terminal metadata after physical bytes are removed.
   - Direct object-store commands such as `mc rm` are outside the supported lifecycle. Incident recovery may preserve or isolate evidence for escalation, but it must not bypass the Game Design reachability/CAS APIs or claim to finalize lifecycle state.

## Handling Failed Publish Versions

Occasionally the asset-export step of `PublishVersion` may fail in a way that
leaves a version incomplete or unusable:

- When the Saga marks a version as **Failed**, it must not appear in
  `game_manifest` or any launch manifests, and operators must not attempt to
  start game instances against it.
- Failed versions may have partially written candidates in private quarantine. Do not delete these manually unless the Game Design Service has already marked the version Failed and there is no intention to retry publish. Failed candidates remain `FAILED` and retryable until an operator explicitly abandons retry and invokes `TombstoneVersionAssets(tenantId, versionId, expectedArtifactStateEpoch, tombstoneWorkflowId)`; only that CAS-guarded transition may move them to `TOMBSTONED` for the configurable diagnostic window.
- Failed or `EXPORTED_UNATTESTED` candidates are never launchable, never used as fallback, and never repaired in place while runtime admission continues. Retry creates a new approved workflow attempt and either completes a fully attested `PUBLISHED` release or remains non-launchable.
- State transitions for failed artifacts must follow the asset lifecycle contract:
  - `STAGED -> EXPORTED_UNATTESTED` when export succeeds but attestation has not yet committed.
  - `EXPORTED_UNATTESTED -> PUBLISHED` only after `published_release_bundle` commit succeeds.
  - `EXPORTED_UNATTESTED -> FAILED` when attestation or later publish completion fails.
  - `FAILED -> STAGED` only through documented repair/retry workflow.
  - `PUBLISHED -> TOMBSTONED` only after retirement and `CanDeleteVersionAssets` eligibility proof, through the target CAS-guarded tombstone operation; the current implementation does not support this transition.
  - `FAILED -> TOMBSTONED` only through `TombstoneVersionAssets(tenantId, versionId, expectedArtifactStateEpoch, tombstoneWorkflowId)` after retry is explicitly abandoned.
  - `TOMBSTONED -> PURGE_IN_PROGRESS` only through `BeginPurgeVersionAssets(tenantId, versionId, expectedArtifactStateEpoch)`.
  - `PURGE_IN_PROGRESS -> PURGED` only after object deletion succeeds and finalization CAS passes.
  - `PURGE_IN_PROGRESS -> PURGE_FAILED` when deletion or finalization fails.
  - `PURGE_FAILED -> PURGE_IN_PROGRESS` only through explicit retry/resume workflow (new purge workflow id).
  - `PURGE_FAILED -> TOMBSTONED` when operators abandon purge retry.
- All transitions above are CAS-guarded using the artifact state epoch in `version_asset_artifact`. If a transition fails CAS, reload current state and re-run the approved workflow rather than forcing manual object-store edits.
- `PURGED` remains a retained terminal metadata row in `version_asset_artifact`; purge removes object-store bytes, not lifecycle metadata. Do not delete the artifact row as part of purge.
- Preferred recovery is to:
  - Investigate and fix the underlying issue (for example missing assets or
    permission errors).
  - Trigger a documented “retry publish” or “repair version” workflow in the
    Game Design or Logging & Admin Service so the Saga re-runs the asset export
    step and transitions the version back to Draft/Published as appropriate. The
    exporter always writes a `manifest.json` that includes a `schemaVersion`
    field. For Published/Active releases, do not use repair to move the release
    to a newer manifest schema version; repair is exact-bytes only unless a
    separate re-attestation workflow is explicitly defined.
  - For Published/Active versions, treat the workflow as exact-bytes repair only: it must verify the current `GetPublishedReleaseBundle` attestation and fail if the regenerated bytes would change the attested `manifestHash`.
  - If clean-up is required after deciding to abandon a Failed version, use `CanDeleteVersionAssets(tenantId, versionId)`, then `TombstoneVersionAssets(...)`, then the CAS-guarded `BeginPurgeVersionAssets(...)` / `FinalizePurgeVersionAssets(...)` workflow. Do not remove the corresponding prefix directly.
