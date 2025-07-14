# Production Kubernetes Modules

This directory provides example Terraform configuration for deploying a production FireMUD environment.
It installs PostgreSQL and Redis using Bitnami Helm charts with replication enabled.

These modules assume an existing Kubernetes cluster and `kubectl` access via a kubeconfig file.
The configuration is intentionally minimal and should be customized for real deployments.
Persistent volumes are configured via the included Helm values files:

- `postgres-values.yaml` provisions a **10Gi** volume for the PostgreSQL primary and each replica.
- `redis-values.yaml` provisions **8Gi** volumes for Redis master and replicas with AOF enabled.

Adjust these sizes to fit your production retention policy before applying the module.
