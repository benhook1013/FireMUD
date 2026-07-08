# Production Kubernetes Modules

This directory provides example Terraform configuration for deploying a production FireMUD environment.
It installs PostgreSQL plus separate Coordination and Cache/Rate-Limit Redis releases using Bitnami Helm charts.

These modules assume an existing Kubernetes cluster and `kubectl` access via a kubeconfig file.
The configuration is intentionally minimal and should be customized for real deployments.

Persistent volumes are configured via the included Helm values files:

- `postgres-values.yaml.tftpl` provisions a **10Gi** volume for the PostgreSQL primary and each replica.
- `redis-coord-values.yaml` provisions **8Gi** volumes for Coordination Redis master and replicas with AOF enabled.
- `redis-cache-values.yaml` provisions **8Gi** volumes for Cache/Rate-Limit Redis master and replicas without AOF.

Adjust these sizes to fit your production retention policy before applying the module. PostgreSQL credentials are intentionally not checked in; Terraform renders the Postgres values template from variables at apply time.

## Variables

The module exposes variables to configure Velero:

- `postgres_superuser_password` – PostgreSQL superuser password for the Helm release.
- `postgres_app_username` – Application PostgreSQL username.
- `postgres_app_password` – Application PostgreSQL password.
- `postgres_database` – Application PostgreSQL database name.
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
postgres_superuser_password = "replace-with-a-real-superuser-password"
postgres_app_username    = "firemud"
postgres_app_password    = "replace-with-a-real-app-password"
postgres_database        = "firemud"
velero_provider          = "aws"
velero_bucket            = "firemud-backups"
velero_bucket_prefix     = "postgres"
velero_credentials_secret = "velero-creds"
```

Replace the example passwords, bucket name, and credentials secret with your
real production values before applying the module.
