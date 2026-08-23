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

   A trusted bootstrap using `minio-credentials` must create the generated service identity and bucket-scoped policy required by Game Design. The policy may permit only `GetObject`, `PutObject`, `ListBucket`, and `DeleteObject` on `firemud-assets`; it must deny admin operations and access to other buckets. This policy is least-privileged at bucket/API scope and does not enforce lifecycle state: possession of the Game Design credential permits the listed operations, including `PutObject` for publication and exact-bytes repair. Distribution is restricted to Game Design, whose CAS-guarded workflow governs `DeleteObject`; the credentials are not for direct operator use. The bootstrap must materialize `firemud-assets-writer` through the approved secret mechanism without credentials in the repository or shell history. Repository automation for this provisioner is currently absent, so shared and production-like deployment is blocked until it exists.

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

   These manifests deploy MinIO only; they do not provision the `firemud-assets-writer` identity or its bucket-scoped policy. No trusted identity/policy provisioner is included in this repository, so the shown deployment remains incomplete and Game Design is blocked until an approved trusted bootstrap provisions that identity and policy and materializes the writer Secret. Do not use `minio-credentials` as the Game Design writer credential.

4. Create the bucket and keep its policy private. This is bucket bootstrap only; it does not provision the `firemud-assets-writer` identity or policy. The pinned private bootstrap intentionally does not configure bucket CORS or create a public asset route. Any later approved `/assets/**` public-origin provisioner must use delivery-platform tooling that supports its required CORS policy and remains separate from this private MinIO writer endpoint. The `mc` pod receives credentials from the Kubernetes Secret as environment variables; the values are never supplied as command-line arguments:

   ```bash
   kubectl run mc --rm -it --restart=Never \
     --image=minio/mc:RELEASE.2024-05-09T17-04-24Z@sha256:3e9666a093d0a8fcbbac606346c415ae9277a0ca96989a6bdddd3d03e90a21b4 \
     --overrides='{"spec":{"containers":[{"name":"mc","env":[{"name":"MINIO_ACCESS_KEY","valueFrom":{"secretKeyRef":{"name":"minio-credentials","key":"accessKey"}}},{"name":"MINIO_SECRET_KEY","valueFrom":{"secretKeyRef":{"name":"minio-credentials","key":"secretKey"}}}]}]}}' \
     --command -- sh -c '
       export MC_HOST_local="http://${MINIO_ACCESS_KEY}:${MINIO_SECRET_KEY}@minio:9000" &&
       mc mb --ignore-existing local/firemud-assets &&
       mc anonymous set private local/firemud-assets
     '
   ```

Configure Game Design's authenticated S3 access with `ASSET_STORE_ENDPOINT=http://minio:9000` inside the cluster and the Secret-backed writer credentials. Local Game Design setup is blocked until `firemud-assets-writer` has actually been provisioned through an approved local-only or trusted bootstrap path; the placeholder `minio-credentials` Secret alone is insufficient. Do not expose the MinIO bucket as an anonymous delivery surface.

Validation must separate the private MinIO endpoint from public delivery: an unauthenticated GET to the private `ASSET_STORE_ENDPOINT` must be rejected, while a target public-origin request is tested only against an attested immutable published object.

Target public delivery uses a separate gateway/CDN origin recorded through target-only `ASSET_STORE_PUBLIC_BASE_URL`; that setting is not implemented in the current single-endpoint first slice. The current Gateway has no `/assets/**` route or asset-store route ID, and private MinIO is not public delivery. Target `/assets/**` remains reserved pending a separate approved public origin/provisioner and stays separate from `/frontend-assets/**`. The public origin must expose only attested immutable published objects. Private staging, quarantine, `FAILED`, and `EXPORTED_UNATTESTED` objects remain inaccessible through that public origin or another public delivery path; the authenticated `firemud-assets-writer` retains only its separately authorized bucket access.

## Validation and Evidence Checklist

Documentation checks for this change:

- `git diff --check -- design/architecture/microservices/game-design-service/asset-storage.md design/architecture/system-architecture-asset-store-runbook.md k8s/minio/README.md`
- `bash dev-tools/tests/architecture-doc-contracts.sh`
- `./gradlew linkCheck lintMarkdown`

Required runtime evidence remains unrun for this documentation-only change. Before the target public-delivery path may be considered implemented, operators must retain:

- an unauthenticated GET attempt against the private MinIO/S3 endpoint showing rejection;
- an authenticated private-endpoint request showing each allowed writer read, write, list, and delete operation through the Secret-backed writer configuration;
- denied writer attempts for admin operations and other buckets;
- a public-origin gateway/CDN request for an attested immutable published object showing delivery;
- denied public reads for private candidate, `FAILED`, and `EXPORTED_UNATTESTED` bytes; and
- a gateway response showing the configured CORS headers for an allowed GET origin.
