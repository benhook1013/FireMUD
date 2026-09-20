# Shared Hosted Environment Helpers

This directory contains helpers used by both hosted environment lanes:

- `pr-preview`
- `dev-demo-cluster`

These scripts are shared because they manage infrastructure or validation behavior that is the same for both environments.

## Script Map

- `write-kubeconfig.sh`
  - writes the runner kubeconfig file from the configured secret payload

- `delete-hosted-namespace.sh`
  - requires the runtime namespace and Helm release name, and validates that both are canonical and match before deletion
  - deletes the exact ownership-validated runtime namespace under a UID precondition and waits for Kubernetes cascading cleanup to make it absent

- `ensure-ghcr-pull-secret.sh`
  - creates or updates the shared GHCR image-pull secret in the target namespace

- `ensure-grpc-tls-secret.sh`
  - creates or updates the hosted environment's gRPC TLS secret from the local development cert helper

- `wait-for-runtime-images.sh`
  - for a pull request, waits for a `runtime-images.yml` run whose exact base, head, and test-merge identities match, then waits for the trusted publisher to publish the immutable `pr-merge-<merge-sha>` tag
  - with one SHA argument, retains the branch/dev-demo waiter behavior

- `hosted-login-look-smoke.sh`
  - runs the canonical hosted TCP LOGIN -> PLAY -> LOOK smoke proof against the exposed environment

- `push-verified-image.sh`
  - retries one trusted Docker image push up to three times with 5-second and 10-second backoff
  - extracts exactly one valid sha256 digest from the successful push output and writes `digest=` to `GITHUB_OUTPUT`; failed-attempt output is never reused

- `show-rollout-diagnostics.sh`
  - prints the canonical hosted rollout failure view for both preview lanes, including blocked readiness reasons, service/target ports, safe config summaries, secret/TLS summaries, events, describes, and current plus previous logs for problematic pods

Preview-only image handling uses [`../preview/resolve-preview-image-tag.sh`](../preview/resolve-preview-image-tag.sh) to select the immutable `pr-merge-<merge-sha>` tag for a runtime-changing pull request, or the immutable base-commit tag when no runtime-image trigger paths changed. The resolver rejects incomplete file metadata before allowing base-image reuse. When the base tag is selected, [`../preview/wait-for-base-images.sh`](../preview/wait-for-base-images.sh) checks that each base runtime image is available in GHCR before deployment.
