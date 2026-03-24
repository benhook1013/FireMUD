# Local Kubernetes with Terraform

This directory contains a **sample Terraform module** for spinning up a local Kubernetes cluster using the [Kind](https://kind.sigs.k8s.io/) provider. The cluster is intended for development only and mirrors the setup used by the Docker Compose environment.

The module creates a `firemud` namespace and an admin `ServiceAccount` bound to the `cluster-admin` role. Optionally it can install a Redis Helm chart for testing purposes.

> These files are provided as examples and are **not** used by the CI/CD pipeline.

## Usage

```bash
cd k8s/terraform
terraform init
terraform apply
```

After applying, use the generated kubeconfig path to deploy the manifests or Helm charts from the `k8s/` directory.

For a production-ready starting point, see [../terraform-production](../terraform-production),
which installs PostgreSQL and Redis with replication enabled on an existing Kubernetes cluster.
