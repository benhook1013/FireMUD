# Helm Charts

This directory contains Helm charts for deploying FireMUD services.

- `firemud/` is the real full-stack chart path used for the hosted `pr-preview` and fixed `dev-demo-cluster` environments.
- `account-service/` and `game-session-service/` are narrower service-specific example charts.
- The top-level `charts/firemud` chart elsewhere in the repository is a separate support chart, not the main full-stack Helm surface described here.

The top-level `values-local.yaml` and `values-dev.yaml` files in this directory are for the narrower example service charts in this folder, not for the hosted full-stack `firemud/` chart. Replica counts and resource limits are also defined in each chart's own `values.yaml` so they can be tuned without modifying templates.

Install the example chart with:

```bash
helm install game-session ./game-session-service \
  -f values-local.yaml
```

Use the Terraform module in `../terraform` to create a test cluster if desired.

To deploy the Account Service:

```bash
helm install account-service ./account-service \
  -f values-local.yaml
```

To deploy all services at once through the full-stack chart path, render environment-specific values from the shared hosted template:

```bash
python3 ../../dev-tools/hosted/preview/render-preview-values.py \
  firemud/values-hosted-shared.example.yaml \
  /tmp/preview-values.yaml \
  123 pr-123 pr-123 pr-123.preview.firedevops.net pr-123-deadbeef 32000
helm upgrade --install firemud ./firemud \
  -f /tmp/preview-values.yaml
```

For hosted PR preview environments, start from:

```bash
python3 ../../dev-tools/hosted/preview/render-preview-values.py \
  firemud/values-hosted-shared.example.yaml \
  /tmp/preview-values.yaml \
  123 pr-123 pr-123 pr-123.preview.firedevops.net pr-123-deadbeef 32000
helm upgrade --install pr-123 ./firemud \
  -f /tmp/preview-values.yaml \
  --namespace pr-123 \
  --create-namespace
```

`firemud/values-hosted-shared.example.yaml` is the shared source template for the hosted deployment contract. `dev-tools/hosted/preview/render-preview-values.py` materializes preview-specific values from it:

- PR number, namespace, release name, and preview hostname
- immutable per-PR image tags from GHCR
- Traefik/TLS settings
- persistent storage for PostgreSQL, MinIO, and Redis
- conservative preview capacity assumptions for the single-node Hetzner host
- stubbed first-create seed/bootstrap hooks that can be replaced once the runtime data model stabilizes

`dev-tools/hosted/dev-demo/render-dev-demo-values.py` materializes the fixed `develop` hosted environment values from the same shared template:

- stable namespace/release/hostname for the shared `develop` branch environment
- fixed TCP NodePort separate from the per-PR preview range
- the same full-stack hosted smoke target (`LOGIN -> PLAY -> LOOK`) as PR preview
- clean-redeploy expectations for a reproducible shared-branch environment

Current limitation:

- The umbrella chart is now the real hosted deploy path for preview and dev-demo, including Helm apply and hosted smoke.
- Hosted environments still intentionally clean-redeploy today rather than preserving mutable state across updates.
- The broader frontend/runtime delivery path remains under implementation even though the TCP-first hosted proof path is live.
