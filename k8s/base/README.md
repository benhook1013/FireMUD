# Baseline Kubernetes Manifests

This directory contains baseline deployment files for running the core FireMUD services in a Kubernetes cluster. They are intended as reference manifests and starting points for ad hoc cluster bring-up, not as the main player-facing deployment contract.

Apply all manifests with:

```bash
kubectl apply -n firemud -f account-service.yaml
kubectl apply -n firemud -f automation-scripting-service.yaml
kubectl apply -n firemud -f entity-management-service.yaml
kubectl apply -n firemud -f game-design-service.yaml
kubectl apply -n firemud -f game-logic-service.yaml
kubectl apply -n firemud -f game-session-service.yaml
kubectl apply -n firemud -f logging-admin-service.yaml
kubectl apply -n firemud -f social-groups-service.yaml
kubectl apply -n firemud -f tcp-proxy-service.yaml
kubectl apply -n firemud -f world-management-service.yaml
kubectl apply -n firemud -f spring-cloud-gateway.yaml
```

These manifests assume a `firemud` namespace.

These files expose the services internally using `ClusterIP` (except the gateway and TCP proxy which are `LoadBalancer`). They also carry more baked-in assumptions than a purely minimal example set, including explicit `prod` profile usage, JWT/JWKS mounts, gRPC TLS mounts, and Fluent Bit sidecars in some services. `base/` is still a baseline scaffold rather than the player-facing deployment contract, but the checked-in player-facing overlays no longer inherit placeholder bootstrap Secrets or TLS material from it.

Ports align with the design documents:

- Application services expose `8080`.
- The TCP proxy listens on `2323` for Telnet connections.
- Spring Cloud Gateway is reachable via port `80`.

All deployments include readiness probes against `/actuator/health/readiness`, liveness probes against `/actuator/health/liveness`, and startup probes against `/actuator/health/liveness` as described in the design docs.

## Database Settings

All Spring Boot services expect PostgreSQL and Redis connection details via environment variables. The baseline set also expects the auth-related `jwt-signing-keys` Secret and public `jwt-jwks` ConfigMap where applicable. `firemud-db-env.yaml` supplies the shared non-secret `firemud-config` `ConfigMap`; example bootstrap Secret/TLS manifests now live in `bootstrap-secrets.example.yaml` and `grpc-bootstrap.example.yaml` for ad hoc bring-up only. Player-facing environments should supply those bindings through environment-owned resources instead of applying the example files verbatim:

```bash
FIREMUD_POSTGRES_HOST=postgres
FIREMUD_POSTGRES_PORT=5432
FIREMUD_POSTGRES_DB=firemud
FIREMUD_REDIS_COORD_HOST=redis-coord
FIREMUD_REDIS_COORD_PORT=6379
FIREMUD_REDIS_CACHE_HOST=redis-cache
FIREMUD_REDIS_CACHE_PORT=6379
OTEL_ENDPOINT=http://otel-collector:4317
FLUENT_ELASTICSEARCH_HOST=elasticsearch
FLUENT_ELASTICSEARCH_PORT=9200
```

Each deployment loads these variables using `envFrom`. Use the example bootstrap manifests only as reference input when bringing up a non-player-facing ad hoc cluster.

An example `HorizontalPodAutoscaler` manifest is provided in `hpa-example.yaml`. It is commented out by default and can be customized with CPU or other metrics when deploying to production clusters.
