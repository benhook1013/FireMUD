# Logging & Admin Service Operations

This document collects Logging & Admin operational behavior, readiness expectations, and observability-related operating constraints.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health/readiness` and `/actuator/health/liveness` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.
- REST endpoints listen on port `8080` and gRPC on port `6565`.

## Availability and Degradation Expectations

- Core operator control-plane features remain writable and supported even when observability backends are unavailable.
- Observability-backed features may become read-only, partially unavailable, or hidden behind degraded-state messaging.
- Readiness and degradation reporting must distinguish core operator control-plane capability from observability backend degradation so moderation and remediation controls are not withdrawn just because Elasticsearch, Prometheus, Grafana, Kibana, Jaeger, or Alertmanager are unhealthy.

## Operator Workflows

- Tick and coordination remediation actions must be issued through Game Session-owned control-plane APIs and documented runbooks; Logging & Admin records the operator action and resulting audit trail but does not mutate runtime coordination state itself.
- Saga inspection is supported through `/sagas` and `/sagas/{id}/steps`, backed by the shared saga tables.
