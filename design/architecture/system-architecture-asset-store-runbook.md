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

   To remove a published tenant version, delete the corresponding prefix:

   ```bash
   kubectl run mc --rm -it --image=minio/mc --command -- \
     sh -c "mc rm -r --force local/firemud-assets/<tenant>/<version>/"
   ```
