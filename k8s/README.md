# Kubernetes Manifests

This directory contains the repository's Kubernetes-side deployment assets. The current deployment surfaces are not all equal:

- `k8s/helm/firemud/` is the exercised hosted deployment path for `pr-preview` and `dev-demo-cluster`.
- `overlays/` is the Git-tracked Kustomize path for staging and production, with digest-pinned workload images and externally managed bootstrap bindings enforced by preflight.
- `base/` contains a baseline manifest set that is useful for reference and ad hoc cluster bring-up, but it is not the main player-facing deployment contract.
- `network-policies/`, `monitoring/`, `preview/`, `postgres/`, `velero/`, and the Terraform directories provide supporting infrastructure assets.

The `base/` manifests set `metadata.namespace: firemud` directly in YAML, so `firemud` is the default shared namespace unless you apply namespace transforms via overlays.

The command examples below assume you are running from the repository root.

```bash
kubectl apply -n firemud -f k8s/base/account-service.yaml
kubectl apply -n firemud -f k8s/base/automation-scripting-service.yaml
kubectl apply -n firemud -f k8s/base/entity-management-service.yaml
kubectl apply -n firemud -f k8s/base/game-design-service.yaml
kubectl apply -n firemud -f k8s/base/game-logic-service.yaml
kubectl apply -n firemud -f k8s/base/game-session-service.yaml
kubectl apply -n firemud -f k8s/base/logging-admin-service.yaml
kubectl apply -n firemud -f k8s/base/social-groups-service.yaml
kubectl apply -n firemud -f k8s/base/tcp-proxy-service.yaml
kubectl apply -n firemud -f k8s/base/world-management-service.yaml
kubectl apply -n firemud -f k8s/base/spring-cloud-gateway.yaml
```

## Kustomize overlays (staging/production)

Staging and production deployments are applied via Kustomize overlays:

```bash
kubectl apply -k k8s/overlays/stage
kubectl apply -k k8s/overlays/prod
```

The staging overlay is intentionally treated as disposable by default and does not include production backup schedules.
PRs that modify `k8s/` run `.github/workflows/validate-kustomize-overlays.yml`, which blocks staging backup schedules unless `k8s/overlays/stage/STAGING_BACKUPS_ENABLED` is present.

Player-facing bootstrap bindings are intentionally environment-owned rather than rendered inline in the overlays. The canonical Kustomize path expects:

- `base/firemud-db-env.yaml` for the shared `firemud-config` `ConfigMap`
- externally managed `postgres-credentials`, `jwt-signing-keys`, and `jwt-jwks` Secrets
- externally managed workload mTLS material such as `firemud-grpc-tls`
- expected-binding manifests under `design/operations/environments/*` plus `dev-tools/deploy/preflight.py` to validate that those bindings match the target environment contract

The base manifests and overlays expect those names unless you intentionally customize them together with the matching expected-binding and operator bootstrap evidence.

The canonical internal-service network policies are part of `base/`. The `network-policies/` directory is documentation-only now; apply the policy manifests from `base/` if you are selectively applying files outside Kustomize:

```bash
kubectl apply -n firemud -f k8s/base/internal-services-network-policy.yaml
kubectl apply -n firemud -f k8s/base/internal-services-egress-network-policy.yaml
```

The policy allows gRPC (8080, 6565) and OpenTelemetry traffic on port `4317` in
addition to database access.

Hosted preview/dev-demo now also render their own checked-in baseline internal-service network policies from `k8s/helm/firemud`, so the hosted path no longer silently diverges from the player-facing policy posture.

## Monitoring Components

The `monitoring/` folder provides example manifests for observability tools:

- `redis-exporter.yaml` exposes Redis metrics to Prometheus.
- `otel-collector.yaml` receives OTLP spans from the services.
- `jaeger.yaml` stores traces and offers a web UI on port `16686`.
- `alertmanager.yaml` handles alert notifications from Prometheus.

Apply them with:

```bash
kubectl apply -n firemud -f k8s/monitoring/redis-exporter.yaml
kubectl apply -n firemud -f k8s/monitoring/otel-collector.yaml
kubectl apply -n firemud -f k8s/monitoring/jaeger.yaml
kubectl apply -n firemud -f k8s/monitoring/alertmanager.yaml
```

Customize these manifests with proper image repositories and resource limits before running in production.
All Spring Boot services are configured to run with the `prod` profile by default via the `SPRING_PROFILES_ACTIVE` environment variable.

Services follow the port scheme described in the infrastructure docs: most
containers listen on `8080`, the TCP proxy exposes `2323` for Telnet clients,
and the Spring Cloud Gateway is published on port `80`.

## Terraform Sample

A sample Terraform module is available in [`k8s/terraform/`](terraform/) to spin up a local Kind cluster with a `firemud` namespace and admin RBAC. It can optionally install Redis via Helm.

## Persistent Storage

The production Terraform modules provision persistent volumes for PostgreSQL plus separate Coordination and Cache/Rate-Limit Redis releases. Default sizes are defined in `terraform-production/postgres-values.yaml.tftpl`, `terraform-production/redis-coord-values.yaml`, and `terraform-production/redis-cache-values.yaml` as **10Gi**, **8Gi**, and **8Gi** respectively. Update these values and provide real Terraform credential inputs before deploying to a real cluster.

## Helm Charts

The main Helm deployment path in this repository is [`k8s/helm/firemud`](helm/firemud/), which is used for the hosted preview and dev-demo environments. The top-level `k8s/helm/values-local.yaml` and `values-dev.yaml` files belong to the narrower example service charts under `k8s/helm/`; they are not the full-stack hosted chart contract.

```bash
helm install game-session ./k8s/helm/game-session-service -f k8s/helm/values-local.yaml
```

For the hosted full-stack chart path, render environment-specific values from the shared hosted template under `k8s/helm/firemud`:

```bash
python3 ./dev-tools/hosted/preview/render-preview-values.py \
  k8s/helm/firemud/values-hosted-shared.example.yaml \
  /tmp/preview-values.yaml \
  123 pr-123 pr-123 pr-123.preview.firedevops.net pr-123-deadbeef 32000
helm upgrade --install firemud ./k8s/helm/firemud -f /tmp/preview-values.yaml -n firemud --create-namespace
```

Helm treats same-name `Secret/jwt-jwks` and `ConfigMap/jwt-jwks` objects as
distinct resource identities, so an upgrade creates the target ConfigMap and
removes the obsolete Secret without a manual pre-delete. The player-facing
Kustomize Secret contract remains separate.

The top-level `charts/firemud` chart is a narrower support chart rather than the main full-stack deployment surface.

## Preview Cluster Prerequisites

The [`k8s/preview/`](preview/) directory captures the one-time cluster prerequisites for the hosted `pr-preview` environment, including:

- Let's Encrypt `ClusterIssuer` resources for Traefik-hosted preview URLs
- the dedicated `preview-deployer` ServiceAccount and RBAC for GitHub Actions

Apply them with:

```bash
kubectl apply -k k8s/preview
```
