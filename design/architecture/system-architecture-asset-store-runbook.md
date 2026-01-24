# FireMUD Asset Store Runbook

This runbook describes operational procedures for the **asset store** backing game content (artifacts, templates, and related static assets).

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
   - Services access the bucket using the `ASSET_STORE_*` environment variables.
   - When the gateway proxies `/assets/**` to MinIO, set `ASSET_STORE_ENDPOINT` to `https://<gateway-domain>/assets` so published manifests generate public URLs.
5. **Removing published versions**

   Removing assets for a published version must be coordinated with the version
   lifecycle described in
   [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md):

   - Only **retired/archived** versions (no `game_instances` rows reference the
     `version_id` as `runtime_version`, and the version is no longer listed as
     launchable in `game_manifest`) are eligible for asset deletion.
   - Published assets are discovered via the `version_asset` mapping table in the
     Game Design Service. Operators must never delete individual objects that are
     still referenced by any `version_asset` row for a non-retired version.
   - If object-store contents drift from the database (for example missing objects for
     a still-published version), operators should re-run the `ExportAssets` Saga step
     for the affected `(tenantId, versionId)` so the manifest and prefix are rebuilt
     from the authoritative `version_asset` mappings rather than attempting manual
     repair.
   - Prefer invoking a higher-level admin workflow (for example an
     `ArchiveVersion` or `RetireVersion` operation in the Game Design or
     Logging & Admin Service) that:
       - Verifies the version is eligible for retirement.
       - Updates manifests and internal metadata to mark it as retired.
       - Deletes the corresponding `<tenant>/<version>/` prefix from the object
         store.

   Directly deleting a prefix with `mc rm` should be treated as a last-resort
   manual fix and only performed after verifying that:

   - No game instances can be started or restarted against this `version_id`.
   - The Game Design and Game Session services have already marked the version
     as retired.
