# Velero Backups

This directory contains Kubernetes manifests for installing Velero and scheduling PostgreSQL backups for the FireMUD cluster.

The `schedule.yaml` file defines three backup schedules matching the retention policy described in the architecture docs.

Apply the manifests with:

```bash
kubectl apply -f schedule.yaml -n velero
```

Ensure Velero is installed and configured with access to your object storage bucket prior to applying the schedule.

Example `values.yaml` snippet when using AWS S3:

```yaml
configuration:
  provider: aws
  backupStorageLocation:
    bucket: firemud-backups
```

For Google Cloud Storage set `provider: gcp` and adjust the bucket name accordingly.
