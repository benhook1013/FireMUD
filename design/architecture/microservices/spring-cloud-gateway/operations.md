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

- Dynamic route mutation is an explicitly enabled local/dev/test capability, not an initial production control plane. These are target-state acceptance criteria; the current implementation does not enforce all of them.
- Target-state acceptance: player-facing environments fail startup if mutation components or endpoints are enabled, regardless of persistence or convergence claims.
- Target-state acceptance: mutation endpoints remain internal-only and use the gateway-boundary authentication/authorization safeguards described in [Gateway Architecture](../../system-architecture-gateway.md#management-plane-security).
- Production emergency changes use an expedited baseline rollout or a predeclared bounded failover switch. Any generic production runtime control plane requires a separate future decision.

## Scalability

- The gateway scales horizontally to handle high concurrency.
- The gameplay-route readiness canary must remain short and operation-shaped; it is not a substitute for retry filters.
- Retry filters are resilience mechanisms, not evidence that the route is ready for fresh gameplay admission.
