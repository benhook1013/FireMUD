# Production Kubernetes Modules

This directory provides example Terraform configuration for deploying a production FireMUD environment.
It installs PostgreSQL and Redis using Bitnami Helm charts with replication enabled.

These modules assume an existing Kubernetes cluster and `kubectl` access via a kubeconfig file.
The configuration is intentionally minimal and should be customized for real deployments.
