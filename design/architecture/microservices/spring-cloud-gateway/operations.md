# Spring Cloud Gateway Operations

## Operational Notes

- Spring Cloud Gateway runs as a Kubernetes Deployment and also supports Docker Compose for local development.
- Health probes use `/actuator/health/readiness` and `/actuator/health/liveness`.
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.
- gRPC endpoints use `LoggingInterceptor`, `MetricsInterceptor`, and `TracingInterceptor` for consistent observability.

## Metrics and Observability Contract

Gateway Architecture requires the following observability surfaces for gameplay WebSocket behavior:

- `gateway.websocket.closes{reason,subreason}`
- `gateway.websocket.handshake.rejected`
- `gateway.websocket.slow_client_closes`

Non-`101` `/ws/game/**` handshake failures must emit the canonical bounded handshake error class in the gateway response and structured logs so clients and operators can distinguish `CONNECT_TOKEN_REJECTED`, `POLICY_DENY`, `BACKEND_UNAVAILABLE`, `CONNECT_REPLAY_PROTECTION_UNAVAILABLE`, and other documented retry classes. A canonical wire-level surface is the `X-Firemud-Handshake-Error-Class` response header paired with matching structured-log fields.

Readiness transition observability follows the shared contract from [Deployment Environments](../../infrastructure/deployment-environments.md):

- `firemud.readiness.current`
- `firemud.readiness.transitions`
- Structured logs keyed by `gameplayRoute`

## Dynamic Route Operational Guardrails

- Dynamic route mutation is intended for dev/test use until persistence, multi-pod convergence, and route-change audit controls are present.
- Player-facing environments must fail startup when dynamic route mutation is enabled without those controls, including the unsafe combination `firemud.gateway.dynamic-routes.enabled=true` with `firemud.gateway.dynamic-routes.allow-player-facing=true`.
- The required readiness predicates for safe player-facing enablement are `dynamic_routes.persistence_ready`, `dynamic_routes.convergence_ready`, `dynamic_routes.audit_ready`, and aggregate `dynamic_routes_ready`.

## Scalability

- The gateway scales horizontally to handle high concurrency.
- The gameplay-route readiness canary must remain short and operation-shaped; it is not a substitute for retry filters.
- Retry filters are resilience mechanisms, not evidence that the route is ready for fresh gameplay admission.
