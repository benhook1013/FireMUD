# Kubernetes Manifests

This directory contains Kubernetes manifests and Helm chart placeholders for deploying the FireMUD services.

The `base/` folder provides minimal deployment files that can be applied to a development cluster:

```bash
kubectl apply -f base/account-service.yaml
kubectl apply -f base/automation-scripting-service.yaml
kubectl apply -f base/entity-management-service.yaml
kubectl apply -f base/game-design-service.yaml
kubectl apply -f base/game-logic-service.yaml
kubectl apply -f base/game-session-service.yaml
kubectl apply -f base/logging-admin-service.yaml
kubectl apply -f base/social-groups-service.yaml
kubectl apply -f base/tcp-proxy-service.yaml
kubectl apply -f base/world-management-service.yaml
kubectl apply -f base/spring-cloud-gateway.yaml
```

The file `base/firemud-db-env.yaml` defines the shared `firemud-config`
`ConfigMap` and `firemud-secret` `Secret` used by these deployments.

After the services are running, apply the default network policies found in
`network-policies/` to restrict traffic to internal pods only:

```bash
kubectl apply -f network-policies/internal-services.yaml
```

## Monitoring Components

The `monitoring/` folder provides example manifests for observability tools:

- `redis-exporter.yaml` exposes Redis metrics to Prometheus.
- `otel-collector.yaml` receives OTLP spans from the services.
- `jaeger.yaml` stores traces and offers a web UI on port `16686`.
- `alertmanager.yaml` handles alert notifications from Prometheus.

Apply them with:

```bash
kubectl apply -f monitoring/redis-exporter.yaml
kubectl apply -f monitoring/otel-collector.yaml
kubectl apply -f monitoring/jaeger.yaml
kubectl apply -f monitoring/alertmanager.yaml
```

Customize these manifests with proper image repositories and resource limits before running in production.
All Spring Boot services are configured to run with the `prod` profile by default via the `SPRING_PROFILES_ACTIVE` environment variable.

Services follow the port scheme described in the infrastructure docs: most
containers listen on `8080`, the TCP proxy exposes `2323` for Telnet clients,
and the Spring Cloud Gateway is published on port `80`.

## Terraform Sample

A sample Terraform module is available in [`terraform/`](./terraform) to spin up a local Kind cluster with a `firemud` namespace and admin RBAC. It can optionally install Redis via Helm.

## Helm Charts

The [`helm/`](./helm) folder contains example charts. Use `values-local.yaml` or `values-dev.yaml` to override connection details and feature flags when deploying locally:

```bash
helm install game-session ./helm/game-session-service -f helm/values-local.yaml
```
