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

Expose the service with an Ingress or Port-forward as needed. Configure
application services using the `ASSET_STORE_*` environment variables to point to
this endpoint.
