# Logging & Admin Service Operations

This document collects Logging & Admin operational behavior, readiness expectations, and observability-related operating constraints.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health/readiness` and `/actuator/health/liveness` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.
- REST endpoints listen on port `8080` and gRPC on port `6565`.

## Availability and Degradation Expectations

- Core operator control-plane features remain supported during observability-only failure when their authentication/scope, durable audit/intent, authoritative owner/fence, stable request identity, and durable owner acknowledgement remain available. Missing mandatory owner or audit prerequisites fail the specific action closed.
- Observability-backed features may become read-only, partially unavailable, or hidden behind degraded-state messaging.
- Risk-reducing actions remain available under the core prerequisites. Exposure-increasing and recovery actions additionally retain their ordinary action-specific compatibility, recovery, freshness, and safety gates; missing telemetry alone neither authorizes nor prohibits them.
- Readiness and degradation reporting must distinguish core operator control-plane capability from observability backend degradation so moderation and remediation controls are not withdrawn just because Elasticsearch, Prometheus, Grafana, Kibana, Jaeger, or Alertmanager are unhealthy.
- Fault-injection evidence must prove that backend failure and saturation cannot consume the pools or deadlines reserved for core control. Split the deployable if that isolation cannot be maintained.

## Operator Workflows

- Tick and coordination remediation actions must be issued through Game Session-owned control-plane APIs and documented runbooks; Logging & Admin records the operator action and resulting audit trail but does not mutate runtime coordination state itself.
- Saga inspection is supported through `/sagas` and `/sagas/{id}/steps`, backed by the shared saga tables.
