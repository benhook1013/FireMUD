# MinIO Deployment

This directory provides example manifests for running a MinIO instance to host
published game assets.

## Usage

1. Create the Kubernetes Secret consumed by the MinIO Deployment:

   ```bash
   # Local-only smoke values. Never reuse these placeholders in shared or
   # production-like environments.
   kubectl create secret generic minio-credentials \
     --from-literal=accessKey=LOCAL_ONLY_ACCESS_KEY \
     --from-literal=secretKey=LOCAL_ONLY_SECRET_KEY
   ```

   For shared or production-like environments, generate unique credentials in the approved secret manager and materialize them as the `minio-credentials` Kubernetes Secret. Generate a separate bucket-scoped, least-privileged `firemud-assets-writer` Secret for Game Design. Do not put real values in manifests, shell history, or process arguments. The MinIO Deployment and Game Design configuration must consume these values through Secret-backed environment/configuration references.

   A trusted bootstrap using `minio-credentials` must create the generated service identity and bucket-scoped policy required by Game Design. The policy may permit only `GetObject`, `PutObject`, `ListBucket`, and `DeleteObject` on `firemud-assets`; it must deny admin operations and access to other buckets. The bootstrap must materialize `firemud-assets-writer` through the approved secret mechanism without credentials in the repository or shell history. Repository automation for this provisioner is currently absent, so shared and production-like deployment is blocked until it exists.

   The MinIO Deployment already injects `minio-credentials` through `secretKeyRef`. A Game Design Deployment should use the writer Secret in the same way:

   ```yaml
   env:
     - name: ASSET_STORE_ACCESS_KEY
       valueFrom:
         secretKeyRef:
           name: firemud-assets-writer
           key: accessKey
     - name: ASSET_STORE_SECRET_KEY
       valueFrom:
         secretKeyRef:
           name: firemud-assets-writer
           key: secretKey
   ```

2. Create a PersistentVolumeClaim named `minio-data` appropriate for your
   storage class.
3. Apply the manifests:

   ```bash
   kubectl apply -f k8s/minio/deployment.yaml
   kubectl apply -f k8s/minio/service.yaml
   ```

4. Create the bucket and configure its private policy and gateway CORS. The `mc` pod receives credentials from the Kubernetes Secret as environment variables; the values are never supplied as command-line arguments:

   ```bash
   kubectl run mc --rm -it --restart=Never \
     --image=minio/mc:RELEASE.2024-05-09T17-04-24Z \
     --overrides='{"spec":{"containers":[{"name":"mc","env":[{"name":"MINIO_ACCESS_KEY","valueFrom":{"secretKeyRef":{"name":"minio-credentials","key":"accessKey"}}},{"name":"MINIO_SECRET_KEY","valueFrom":{"secretKeyRef":{"name":"minio-credentials","key":"secretKey"}}}]}]}}' \
     --command -- sh -c '
       export MC_HOST_local="http://${MINIO_ACCESS_KEY}:${MINIO_SECRET_KEY}@minio:9000" &&
       mc mb --ignore-existing local/firemud-assets &&
       mc anonymous set private local/firemud-assets &&
       printf "%s\\n" \
         "<CORSConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" \
         "  <CORSRule>" \
         "    <AllowedOrigin>https://your-gateway-domain</AllowedOrigin>" \
         "    <AllowedMethod>GET</AllowedMethod>" \
         "    <AllowedHeader>*</AllowedHeader>" \
         "  </CORSRule>" \
         "</CORSConfiguration>" > /tmp/cors.xml &&
       mc cors set local/firemud-assets /tmp/cors.xml
     '
   ```

Configure Game Design's authenticated S3 access with `ASSET_STORE_ENDPOINT=http://minio:9000` inside the cluster and the Secret-backed writer credentials. Do not expose the MinIO bucket as an anonymous delivery surface.

Target public delivery uses a separate gateway/CDN origin recorded through target-only `ASSET_STORE_PUBLIC_BASE_URL`; that setting is not implemented in the current single-endpoint first slice. The public origin must expose only attested immutable published objects. Private staging, quarantine, `FAILED`, and `EXPORTED_UNATTESTED` objects remain inaccessible.

## Validation and Evidence Checklist

Documentation checks for this change:

- `git diff --check -- design/architecture/microservices/game-design-service/asset-storage.md design/architecture/system-architecture-asset-store-runbook.md k8s/minio/README.md`
- `bash dev-tools/tests/architecture-doc-contracts.sh`
- `./gradlew linkCheck lintMarkdown`

Required runtime evidence remains unrun for this documentation-only change. Before the target public-delivery path may be considered implemented, operators must retain:

- an anonymous GET attempt against a published object showing rejection;
- an authenticated private-endpoint request showing each allowed writer read, write, list, and delete operation through the Secret-backed writer configuration;
- denied writer attempts for admin operations and other buckets;
- denied public reads for private candidate, `FAILED`, and `EXPORTED_UNATTESTED` bytes; and
- a gateway response showing the configured CORS headers for an allowed GET origin.
