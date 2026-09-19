# Velero Backups

These manifests are canonical pre-release backup assets. FireMUD has no player-facing production deployment yet, so changing them does not claim that a production cluster was updated. If these assets are later applied to a live production environment, the production overlay and its promotion evidence remain the deployment authority.

This directory contains Kubernetes manifests for installing Velero and scheduling namespace backups for the FireMUD cluster. Velero now backs up **only Kubernetes manifests** (Deployments, Services, StatefulSets, Secrets, etc.). PostgreSQL data is backed up separately using a `pg_dump` CronJob.

The `schedule.yaml` file defines three backup schedules matching the retention policy described in the architecture docs. Each schedule sets `snapshotVolumes: false` to avoid PVC snapshots.

Apply the manifests with:

```bash
kubectl apply -f schedule.yaml -n velero
```

Ensure Velero is installed and configured with access to your object storage bucket prior to applying the schedule. A starter [values.example.yaml](./values.example.yaml) file is included. Copy it to `values.yaml` and edit the provider and bucket for your environment. Keep `defaultVolumesToFsBackup: false` so PVCs are not backed up.

Example `values.yaml` snippet when using AWS S3:

```yaml
configuration:
  defaultVolumesToFsBackup: false
  backupStorageLocation:
    - name: default
      provider: aws
      bucket: firemud-backups
      prefix: postgres
```

For Google Cloud Storage set the `backupStorageLocation` provider to `gcp` and adjust the bucket name accordingly.

The repository includes a `verify-backups-cronjob.yaml` pre-release manifest that runs the independently promoted, CI-built, digest-pinned `ghcr.io/benhook1013/backup-verifier` image daily. The image carries `verify-backups.sh`, the pinned Velero CLI, and AWS CLI, runs as a non-root UID, and uses a `firemud` ServiceAccount with only `get`/`list` access to Velero `backups` in the `velero` namespace. The checked-in production Terraform stack declares and manages these CronJob resources through `kubernetes_manifest.velero_verify`; that repository wiring does not prove a live production apply. Applying this manifest does not claim live deployment or restore readiness. You can apply it manually in other environments:

```bash
kubectl apply -f verify-backups-cronjob.yaml -n firemud
```

The CronJob leaves the optional `PG_DUMP_BUCKET` check disabled by default. An approved environment-specific projection must provide the bucket and its credentials together; credentials are never bundled into the verifier image. CI image smoke proves the image contents and offline client commands, while a live cluster backup-list result remains unrun deployment proof.

The checked-in Terraform Helm release is pinned to the verified VMware Tanzu Velero chart `12.2.0`, released 2026-09-16, whose `appVersion` is Velero `1.18.2`. The `VELERO_VERSION` and `VELERO_IMAGE_DIGEST` authority, the Velero stage in `docker/backup-verifier.Dockerfile`, and the Terraform server image projection must stay aligned. The CronJob's `backup-verifier` digest is independently promoted from its exact CI-smoked image and is intentionally not rewritten by this Velero updater transaction. Update those three Velero projections with the canonical transaction, supplying the chart version explicitly:

```bash
python3 dev-tools/maintenance/update-workflow-tool.py velero <velero-version> \
  --velero-dockerfile docker/backup-verifier.Dockerfile \
  --velero-chart-version <chart-version> --image-evidence-file <path>
```

The explicit chart argument is required because Helm chart versions and Velero app versions are independent. This updates repository plans and pre-release manifests only; it does not claim that a live production deployment changed.

## Local Backup with MinIO

If running backups locally, deploy MinIO on the cluster and configure Velero to use it as the backup storage location. The manifest `minio.yaml` starts a single-node MinIO instance with a `ClusterIP` service and expects a pre-created `minio-creds` secret in the `minio` namespace.

Create the MinIO credentials secret first:

```bash
kubectl create namespace minio --dry-run=client -o yaml | kubectl apply -f -
kubectl create secret generic minio-creds -n minio \
  --from-literal=accesskey='<set-a-local-user>' \
  --from-literal=secretkey='<set-a-local-password>'
```

Then apply the manifest:

```bash
kubectl apply -f minio.yaml
```

Example `values-minio.yaml` config:

```yaml
configuration:
  defaultVolumesToFsBackup: false
  backupStorageLocation:
    - name: local
      provider: aws
      bucket: firemud-backups
      config:
        region: minio
        s3Url: http://minio.minio.svc.cluster.local:9000
        insecureSkipTLSVerify: true
credentials:
  useSecret: true
  existingSecret: velero-minio-creds
```

Create the access secret:

```bash
kubectl create secret generic velero-minio-creds -n velero \
  --from-literal=cloud='[default]
aws_access_key_id=<same-local-user>
aws_secret_access_key=<same-local-password>'
```

Run the helper script to deploy MinIO, create the bucket, and install Velero. The helper creates the `minio` and `velero` namespaces as needed, creates or updates `minio-creds` and `velero-minio-creds` for you, does not require `PG_DUMP_BUCKET`, and reuses the existing MinIO secret on reruns unless you explicitly override `MINIO_ROOT_USER` or `MINIO_ROOT_PASSWORD`:

```bash
dev-tools/backups/setup-local-backup.sh
```

Velero backups exclude PostgreSQL and Redis data. PostgreSQL dumps are created by
the `firemud-pg-dump` CronJob defined under `k8s/postgres/pg-dump-cronjob.yaml`.
The CronJob's script rotates 15min/daily/weekly/monthly dumps and can upload them to a
bucket when `PG_DUMP_BUCKET` is set. Redis is intentionally ephemeral and
repopulates from the database on startup.
