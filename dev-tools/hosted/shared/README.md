# Shared Hosted Environment Helpers

This directory contains helpers used by both hosted environment lanes:

- `pr-preview`
- `dev-demo-cluster`

These scripts are shared because they manage infrastructure or validation behavior that is the same for both environments.

## Script Map

- `write-kubeconfig.sh`
  - writes the runner kubeconfig file from the configured secret payload

- `persist-runner-kubeconfig.sh`
  - persists the generated kubeconfig into the runner's standard kubeconfig location

- `delete-hosted-namespace.sh`
  - uninstalls the Helm release and deletes the target namespace for a hosted environment

- `ensure-ghcr-pull-secret.sh`
  - creates or updates the shared GHCR image-pull secret in the target namespace

- `ensure-grpc-tls-secret.sh`
  - creates or updates the hosted environment's gRPC TLS secret from the local development cert helper

- `wait-for-runtime-images.sh`
  - waits for the `runtime-images.yml` workflow to publish the requested image tag

- `hosted-login-look-smoke.sh`
  - runs the canonical hosted TCP LOGIN -> PLAY -> LOOK smoke proof against the exposed environment
