# Logging & Admin Service Operations

This document collects Logging & Admin operational behavior, readiness expectations, and observability-related operating constraints.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health/readiness` and `/actuator/health/liveness` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.
- REST endpoints listen on port `8080` and gRPC on port `6565`.

## Availability and Degradation Expectations

- Normal owner-forwarded core writes remain supported during observability-only failure only when their owner-side mutation gates are complete: the action-family schema, shared `mutationDigest/v1` golden vectors, Account authorization-reference issuance plus redemption by the authoritative receiving owner, authentication/scope, durable intent or audit, authoritative owner/fence, stable request identity, and durable owner acknowledgement. The local `/moderation/actions` receiving-service path has a separate gate: the action-family schema, shared digest vectors, Account authorization-reference issuance plus exactly-once local receiving-boundary redemption, authentication/scope, stable request identity, durable local intent/audit commit, and local acknowledgement. That local path does not require downstream owner-side redemption, an authoritative downstream owner/fence, or owner acknowledgement because it does not mutate downstream owner state. Missing mandatory gates for the selected path fail that action closed; core reads retain their independent availability contract.
- Observability-backed features may become read-only, partially unavailable, or hidden behind degraded-state messaging.
- Risk-reducing actions remain available when the applicable path's prerequisites hold. Exposure-increasing and recovery actions additionally retain their ordinary action-specific compatibility, recovery, freshness, and safety gates; the action-family schema, shared digest vectors, and Account authorization-reference path remain mandatory, and missing telemetry alone neither authorizes nor prohibits them.
- Readiness and degradation reporting must distinguish core operator control-plane capability from observability backend degradation so moderation and remediation controls are not withdrawn just because Elasticsearch, Prometheus, Grafana, Kibana, Jaeger, or Alertmanager are unhealthy.
- Fault-injection evidence must prove that backend failure and saturation cannot consume the pools or deadlines reserved for core control. Split the deployable if that isolation cannot be maintained. Validation and runtime proof for implemented isolation follows [Validation and Runtime Proof](../../../developer-workflows/validation-and-runtime-proof.md), and direct execution results belong in PR/CI evidence or the owning implementation tracker.

## Operator Workflows

- Tick and coordination remediation actions must be issued through Game Session-owned control-plane APIs and documented runbooks; Logging & Admin does not mutate runtime coordination state itself. In the current implementation, the Logging & Admin owner-directed remediation endpoint is unavailable and returns `503 TICK_REMEDIATION_UNAVAILABLE`; it has no callable forwarding path. Game Session's coarse instance-scoped pause/resume surface also lacks complete durable operator-result readback, so it does not prove a completed audit, recovery, or convergence outcome. The target flow may record intent, forward the request, and report success only after the owner contract and durable result/readback are implemented and proven.
- Saga inspection is supported through `/sagas` and `/sagas/{id}/steps`, backed by the shared saga tables.
