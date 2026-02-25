# FireMUD Asset Store Runbook

This runbook describes operational procedures for the **asset store** backing game content (binary design assets such as icons, audio, themes, and exported bundles).

For the architecture of asset storage, see `design/architecture/microservices/game-design-service/asset-storage.md`.

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
   - Verify checksums or version metadata where available.
   - Coordinate with backup and recovery procedures if persistent corruption is suspected.
   - Prefer comparing the published version’s recorded `manifestHash` (and, where implemented, per-asset `contentHash` values) against the currently served `manifest.json` and objects to detect silent drift. `manifestHash` is mandatory for Published/Active versions; missing hashes should be treated as a publish contract violation.

## MinIO Deployment and Configuration

When using a self-hosted MinIO cluster as the asset store:

1. **Deploy MinIO**
   - Deploy the manifests under `k8s/minio/`.
   - Create a `minio-credentials` Secret with `accessKey` and `secretKey` keys.
   - Rotate these credentials via the MinIO credentials manifest (`k8s/minio/credentials.yaml`) and follow the [Environment & Secrets Management](./infrastructure/environment-and-secrets.md#minio-credentials) guidelines when updating keys.
2. **Create the `firemud-assets` bucket**

   ```bash
   kubectl run mc --rm -it --image=minio/mc --command -- \
     sh -c "mc alias set local http://minio:9000 $ACCESS $SECRET && mc mb local/firemud-assets"
   ```

3. **Allow public reads and CORS from the gateway domain**

   ```bash
   kubectl run mc --rm -it --image=minio/mc --command -- \
     sh -c "mc alias set local http://minio:9000 $ACCESS $SECRET && \
            mc anonymous set download local/firemud-assets && \
            printf '[{\"AllowedMethods\":[\"GET\"],\"AllowedOrigins\":[\"https://your-gateway-domain\"],\"AllowedHeaders\":[\"*\"]}]' > /tmp/cors.json && \
            mc cors set local/firemud-assets /tmp/cors.json"
   ```

4. **Service configuration**
   - The Game Design Service is the **sole writer** to the asset bucket and uses `ASSET_STORE_*` environment variables to export assets and manifests during publish workflows. Runtime services and clients consume published assets via CDN or gateway URLs derived from the manifest; they do not write directly to the bucket.
   - When the gateway proxies `/assets/**` to MinIO, set `ASSET_STORE_ENDPOINT` to `https://<gateway-domain>/assets` so published manifests generate public URLs.
5. **Scope of stored data**

   The asset store holds only binary design-time assets and version-scoped `manifest.json` files managed by the Game Design Service. Logical world and entity templates remain in PostgreSQL schemas owned by World Management, Entity Management, and related domain services as described in their architecture documents; they are **not** stored in the object store.

6. **Removing published versions**

   Removing assets for a published version must be coordinated with the version
   lifecycle described in
   [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md):

   - Only **retired** versions (no `game_instances` rows reference the
     `version_id` as `runtime_version`, and the version is no longer listed as
     launchable in `game_manifest`) are eligible for asset deletion.
   - Retired eligibility must also account for **design-time dependencies**: the version must not be referenced by any game templates. Operators should validate that no normalized `game_template_*_ref` rows in the Game Design Service still reference `(tenantId, versionId)` before deleting the corresponding object-store prefix.
   - Asset deletion eligibility must account for design-history reachability as well as version mappings. Use the Game Design control-plane check `CanDeleteVersionAssets(tenantId, versionId)` that validates no non-Retired `version_asset` references remain, no reachable `revision_asset` / branch references require retained bytes, and no template or launch metadata still points to the version prefix.
   - Do not run deletion as a separate check + manual delete sequence. Start purge only through `BeginPurgeVersionAssets(tenantId, versionId, expectedArtifactStateEpoch)` so eligibility re-check and `version_asset_artifact` state transition are atomic and CAS-guarded.
   - Operators must verify the persisted artifact lifecycle row (`version_asset_artifact`) is `TOMBSTONED` before issuing purge actions. `BeginPurgeVersionAssets` must transition state to `PURGE_IN_PROGRESS` before bytes are removed, and `FinalizePurgeVersionAssets` must transition to retained terminal state `PURGED` after bytes are removed. Do not infer lifecycle state solely from object-store contents.
   - Published assets are discovered via the `version_asset` mapping table in the
     Game Design Service. Operators must never delete individual objects that are
     still referenced by any `version_asset` row for a non-retired version.
   - If object-store contents drift from the database (for example missing objects for
     a still-published version), operators should re-run the `ExportAssets` Saga step
     for the affected `(tenantId, versionId)` so the manifest and prefix are rebuilt
     from the authoritative `version_asset` mappings rather than attempting manual
     repair.
   - Because Published/Active versions are immutable, rerunning `ExportAssets` for a Published/Active version must produce bit-for-bit identical bytes. If a rerun would change outputs, treat it as a process bug or data corruption incident rather than “fixing” the published version in place.
   - Prefer invoking a higher-level admin workflow (for example a `RetireVersion` operation in the Game Design or Logging & Admin Service) that verifies retirement eligibility, updates manifests/internal metadata to retired state, and deletes the corresponding `<tenant>/<version>/` prefix from the object store.

   Directly deleting a prefix with `mc rm` should be treated as a last-resort
   manual fix and only performed after verifying that:

   - No game instances can be started or admitted/cut over against this `version_id`.
   - The Game Design and Game Session services have already marked the version
     as retired.

## Handling Failed Publish Versions

Occasionally the asset-export step of `PublishVersion` may fail in a way that
leaves a version incomplete or unusable:

- When the Saga marks a version as **Failed**, it must not appear in
  `game_manifest` or any launch manifests, and operators must not attempt to
  start game instances against it.
- Failed versions may have partially written prefixes in the object store. Do
  not delete these manually unless the Game Design Service has already marked
  the version Failed and there is no intention to retry publish. Failed prefixes
  should normally be marked `TOMBSTONED` and retained for diagnostics.
- State transitions for failed artifacts must follow the asset lifecycle contract:
  - `FAILED -> STAGED` only through documented repair/retry workflow.
  - `FAILED -> TOMBSTONED` when retry is abandoned.
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
    field; when manifest formats evolve, operators should prefer rerunning
    `ExportAssets` so manifests are regenerated consistently for the new schema
    version rather than attempting manual edits.
- If clean-up is required after deciding to abandon a Failed version, follow
  the same safety checks as for Retired versions (including
  `CanDeleteVersionAssets(tenantId, versionId)`), then remove the corresponding
  `<tenant>/<version>/` prefix from the object store.
