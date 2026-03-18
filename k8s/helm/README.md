# Helm Charts

This directory contains Helm charts for deploying FireMUD services.
Charts are provided for the **Account Service** and **Game Session Service** as focused examples.
The umbrella chart under `firemud/` is the evolving deployment surface for the hosted `pr-preview` environment.

The files `values-local.yaml` and `values-dev.yaml` demonstrate how runtime
settings such as Redis connection info, tick interval, and feature flags can be
overridden per environment.
Replica counts and resource limits are also defined in the chart `values.yaml`
so they can be tuned without modifying the templates.

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

To deploy all services at once you can use the umbrella chart:

```bash
helm install firemud ./firemud \
  -f values-local.yaml
```

For the future hosted PR preview environment, start from:

```bash
helm upgrade --install pr-123 ./firemud \
  -f firemud/values-preview.example.yaml \
  --namespace pr-123 \
  --create-namespace
```

`firemud/values-preview.example.yaml` documents the preview deployment contract:

- PR number, namespace, release name, and preview hostname
- immutable per-PR image tags from GHCR
- Traefik/TLS settings
- persistent storage for PostgreSQL, MinIO, and Redis
- conservative preview capacity assumptions for the single-node Hetzner host
- stubbed first-create seed/bootstrap hooks that can be replaced once the runtime data model stabilizes

Current limitation:

- The umbrella chart now renders the core backend/stateful preview topology and passes server-side validation against the preview cluster API.
- Final preview deployment is still intentionally gated in `preview.yml` while the frontend/runtime delivery path and first-create data bootstrap remain under implementation.
