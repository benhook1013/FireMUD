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

To automatically verify that backups continue to run, apply the optional
`verify-backups-cronjob.yaml` which executes a daily check using the included
script:

```bash
kubectl apply -f verify-backups-cronjob.yaml -n velero
```
