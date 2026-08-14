# MinIO Deployment

This directory provides example manifests for running a MinIO instance to host
published game assets.

## Usage

1. Create a Secret with access credentials:

   ```bash
   kubectl create secret generic minio-credentials \
     --from-literal=accessKey=MINIOADMIN \
     --from-literal=secretKey=MINIOADMIN
   ```

2. Create a PersistentVolumeClaim named `minio-data` appropriate for your
   storage class.
3. Apply the manifests:

   ```bash
   kubectl apply -f k8s/minio/deployment.yaml
   kubectl apply -f k8s/minio/service.yaml
   ```

4. Create the bucket used by FireMUD:

   ```bash
    kubectl run mc --rm -it --image=minio/mc --command -- \
      sh -c "mc alias set local http://minio:9000 MINIOADMIN MINIOADMIN && mc mb local/firemud-assets"
    ```

5. Keep the bucket private and configure CORS for the gateway domain:

    ```bash
    kubectl run mc --rm -it --image=minio/mc --command -- \
      sh -c "mc alias set local http://minio:9000 MINIOADMIN MINIOADMIN && \
             mc anonymous set private local/firemud-assets && \
             printf '[{\"AllowedMethods\":[\"GET\"],\"AllowedOrigins\":[\"https://your-gateway-domain\"],\"AllowedHeaders\":[\"*\"]}]' > /tmp/cors.json && \
             mc cors set local/firemud-assets /tmp/cors.json"
    ```

Configure Game Design's authenticated S3 access with `ASSET_STORE_ENDPOINT=http://minio:9000` inside the cluster plus the bucket credentials. Do not expose the MinIO bucket as an anonymous delivery surface.

Target public delivery uses a separate gateway/CDN origin recorded through target-only `ASSET_STORE_PUBLIC_BASE_URL`; that setting is not implemented in the current single-endpoint first slice. The public origin must expose only attested immutable published objects. Private staging, quarantine, `FAILED`, and `EXPORTED_UNATTESTED` objects remain inaccessible.
