# Baseline Kubernetes Manifests

This directory contains minimal deployment files for running the core FireMUD services in a Kubernetes cluster. The manifests are intended as starting points and should be customized with image repositories, resource limits, and environment variables.

Apply all manifests with:

```bash
kubectl apply -f account-service.yaml
kubectl apply -f automation-scripting-service.yaml
kubectl apply -f entity-management-service.yaml
kubectl apply -f game-design-service.yaml
kubectl apply -f game-logic-service.yaml
kubectl apply -f game-session-service.yaml
kubectl apply -f logging-admin-service.yaml
kubectl apply -f social-groups-service.yaml
kubectl apply -f tcp-proxy-service.yaml
kubectl apply -f world-management-service.yaml
kubectl apply -f spring-cloud-gateway.yaml
```

These files expose the services internally using `ClusterIP` (except the gateway and TCP proxy which are `LoadBalancer`). See the [Deployment Environments](../../design/architecture/infrastructure/deployment-environments.md) document for production considerations.

Ports align with the design documents:

- Application services expose `8080`.
- The TCP proxy listens on `2323` for Telnet connections.
- Spring Cloud Gateway is reachable via port `80`.

All deployments include basic readiness and liveness probes that hit the `/actuator/health` endpoint (or open a TCP socket for the proxy service) as described in the design docs.

Spring Boot services run with the `prod` Spring profile by default. This is set via the `SPRING_PROFILES_ACTIVE` environment variable in each deployment manifest.

## Database Settings

All Spring Boot services expect PostgreSQL and Redis connection details via
environment variables. A `firemud-config` `ConfigMap` and `firemud-secret`
`Secret` are provided to supply these values:

```bash
POSTGRES_HOST=postgres
POSTGRES_PORT=5432
POSTGRES_DB=firemud
POSTGRES_USER=firemud
POSTGRES_PASSWORD=firemud
REDIS_HOST=redis
REDIS_PORT=6379
```

Each deployment loads these variables using `envFrom`. Replace the sample
credentials or mount your own Secrets for production environments.
