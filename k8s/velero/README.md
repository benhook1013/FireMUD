# Velero Backups

This directory contains Kubernetes manifests for installing Velero and scheduling PostgreSQL backups for the FireMUD cluster.

The `schedule.yaml` file defines three backup schedules matching the retention policy described in the architecture docs.

Apply the manifests with:

```bash
kubectl apply -f schedule.yaml -n velero
```

Ensure Velero is installed and configured with access to your object storage bucket prior to applying the schedule. A starter [values.example.yaml](./values.example.yaml) file is included. Copy it to `values.yaml` and edit the provider and bucket for your environment.

Example `values.yaml` snippet when using AWS S3:

```yaml
configuration:
  provider: aws
  backupStorageLocation:
    bucket: firemud-backups
    prefix: postgres
```

For Google Cloud Storage set `provider: gcp` and adjust the bucket name accordingly.

The repository includes a `verify-backups-cronjob.yaml` manifest that runs
`dev-tools/verify-backups.sh` daily. Production Terraform modules deploy this
CronJob automatically, but you can apply it manually in other environments:

```bash
kubectl apply -f verify-backups-cronjob.yaml -n velero
```

## Local Backup with MinIO

If running backups locally, deploy MinIO on the cluster and configure Velero to use it as the backup storage location. The manifest `minio.yaml` starts a single-node MinIO instance with a `ClusterIP` service.

Example `values-minio.yaml` config:

```yaml
configuration:
  provider: aws
  backupStorageLocation:
    name: local
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
aws_access_key_id=myaccesskey
aws_secret_access_key=mysecretkey'
```

Create the backup bucket using the MinIO client or web UI before installing Velero. Example with the `mc` CLI:

```bash
mc alias set local http://minio.minio.svc.cluster.local:9000 myaccesskey mysecretkey
mc mb local/firemud-backups
```

Install Velero with these values after the MinIO bucket has been created.
