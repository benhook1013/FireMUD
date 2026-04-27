# Production Kubernetes Modules

This directory provides example Terraform configuration for deploying a production FireMUD environment.
It installs PostgreSQL plus separate Coordination and Cache/Rate-Limit Redis releases using Bitnami Helm charts.

These modules assume an existing Kubernetes cluster and `kubectl` access via a kubeconfig file.
The configuration is intentionally minimal and should be customized for real deployments.

Persistent volumes are configured via the included Helm values files:

- `postgres-values.yaml` provisions a **10Gi** volume for the PostgreSQL primary and each replica.
- `redis-coord-values.yaml` provisions **8Gi** volumes for Coordination Redis master and replicas with AOF enabled.
- `redis-cache-values.yaml` provisions **8Gi** volumes for Cache/Rate-Limit Redis master and replicas without AOF.

Adjust these sizes to fit your production retention policy before applying the module.

## Variables

The module exposes variables to configure Velero:

- `velero_provider` – Object storage provider (e.g. `aws`, `gcp`).
- `velero_bucket` – Backup bucket name.
- `velero_bucket_prefix` – Prefix inside the bucket for backups.
- `velero_credentials_secret` – Name of the Kubernetes secret containing credentials.

The module sets `configuration.defaultVolumesToFsBackup` to `false` to ensure Velero
backs up only Kubernetes manifests. Do not change this unless you intend to back up
PVC contents via filesystem snapshots.

Create a `terraform.tfvars` file (or pass variables via the CLI) with values for
these settings. An example `terraform.tfvars.example` is provided:

```tfvars
kubeconfig               = "~/.kube/config"
namespace                = "firemud"
velero_provider          = "aws"
velero_bucket            = "firemud-backups"
velero_bucket_prefix     = "postgres"
velero_credentials_secret = "velero-creds"
```

Replace the bucket name and credentials secret with your production object
storage details to prevent failed backups.
