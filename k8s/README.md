# Kubernetes Manifests

This directory contains Kubernetes manifests and Helm chart placeholders for deploying the FireMUD services.

The `base/` folder provides minimal deployment files that can be applied to a development cluster:
These manifests set `metadata.namespace: firemud` directly in YAML, so `firemud` is the default shared namespace unless you apply namespace transforms via overlays.

```bash
kubectl apply -n firemud -f base/account-service.yaml
kubectl apply -n firemud -f base/automation-scripting-service.yaml
kubectl apply -n firemud -f base/entity-management-service.yaml
kubectl apply -n firemud -f base/game-design-service.yaml
kubectl apply -n firemud -f base/game-logic-service.yaml
kubectl apply -n firemud -f base/game-session-service.yaml
kubectl apply -n firemud -f base/logging-admin-service.yaml
kubectl apply -n firemud -f base/social-groups-service.yaml
kubectl apply -n firemud -f base/tcp-proxy-service.yaml
kubectl apply -n firemud -f base/world-management-service.yaml
kubectl apply -n firemud -f base/spring-cloud-gateway.yaml
```

## Kustomize overlays (staging/production)

Staging and production deployments are applied via Kustomize overlays:

```bash
kubectl apply -k k8s/overlays/stage
kubectl apply -k k8s/overlays/prod
```

The staging overlay is intentionally treated as disposable by default and does not include production backup schedules.
PRs that modify `k8s/` run `.github/workflows/validate-kustomize-overlays.yml`, which blocks staging backup schedules unless `k8s/overlays/stage/STAGING_BACKUPS_ENABLED` is present.

The file `base/firemud-db-env.yaml` defines the shared `firemud-config`
`ConfigMap` and `firemud-secret` `Secret` used by these deployments.

After the services are running, apply the default network policies found in
`network-policies/` to restrict traffic to internal pods only:

```bash
kubectl apply -n firemud -f network-policies/internal-services.yaml
kubectl apply -n firemud -f base/firemud-grpc-certificate.yaml
```

The policy allows gRPC (8080, 6565) and OpenTelemetry traffic on port `4317` in
addition to database access.

## Monitoring Components

The `monitoring/` folder provides example manifests for observability tools:

- `redis-exporter.yaml` exposes Redis metrics to Prometheus.
- `otel-collector.yaml` receives OTLP spans from the services.
- `jaeger.yaml` stores traces and offers a web UI on port `16686`.
- `alertmanager.yaml` handles alert notifications from Prometheus.

Apply them with:

```bash
kubectl apply -n firemud -f monitoring/redis-exporter.yaml
kubectl apply -n firemud -f monitoring/otel-collector.yaml
kubectl apply -n firemud -f monitoring/jaeger.yaml
kubectl apply -n firemud -f monitoring/alertmanager.yaml
```

Customize these manifests with proper image repositories and resource limits before running in production.
All Spring Boot services are configured to run with the `prod` profile by default via the `SPRING_PROFILES_ACTIVE` environment variable.

Services follow the port scheme described in the infrastructure docs: most
containers listen on `8080`, the TCP proxy exposes `2323` for Telnet clients,
and the Spring Cloud Gateway is published on port `80`.

## Terraform Sample

A sample Terraform module is available in [`terraform/`](./terraform) to spin up a local Kind cluster with a `firemud` namespace and admin RBAC. It can optionally install Redis via Helm.

## Persistent Storage

The production Terraform modules provision persistent volumes for PostgreSQL plus separate Coordination and Cache/Rate-Limit Redis releases. Default sizes are defined in `terraform-production/postgres-values.yaml`, `terraform-production/redis-coord-values.yaml`, and `terraform-production/redis-cache-values.yaml` as **10Gi**, **8Gi**, and **8Gi** respectively. Update these values to match your capacity planning when deploying to a real cluster.

## Helm Charts

The [`helm/`](./helm) folder contains example charts. Use `values-local.yaml` or `values-dev.yaml` to override connection details and feature flags when deploying locally. `values-local.yaml` also reduces replica counts to 1 so a Kind or minikube cluster doesn't run out of resources:

```bash
helm install game-session ./helm/game-session-service -f helm/values-local.yaml
```

For production deployments, use the umbrella chart:

```bash
helm upgrade --install firemud ./charts/firemud -n firemud --create-namespace
```

## Preview Cluster Prerequisites

The [`preview/`](./preview) directory captures the one-time cluster prerequisites for the hosted `pr-preview` environment, including:

- Let's Encrypt `ClusterIssuer` resources for Traefik-hosted preview URLs
- the dedicated `preview-deployer` ServiceAccount and RBAC for GitHub Actions

Apply them with:

```bash
kubectl apply -k k8s/preview
```
