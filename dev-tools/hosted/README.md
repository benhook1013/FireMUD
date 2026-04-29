# Hosted Environment Tooling

This directory contains tooling for FireMUD's hosted Kubernetes environments.

## Folder Map

- `shared/`
  - helpers used by both hosted lanes
  - kubeconfig setup, namespace deletion, shared smoke, shared image wait, and shared pull-secret/TLS setup

- `preview/`
  - PR-preview-only helpers
  - capacity checks, PR-head freshness checks, preview namespace pruning, preview NodePort allocation, and preview-specific value rendering/summary output

- `dev-demo/`
  - fixed `develop` environment helpers
  - dev-demo-specific namespace labels, value rendering, and summary output
