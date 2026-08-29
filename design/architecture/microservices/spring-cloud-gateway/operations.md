# Spring Cloud Gateway Operations

## Metrics and Observability Contract

Gateway Architecture requires the following observability surfaces for gameplay WebSocket behavior:

- `gateway.websocket.closes{reason,subreason}` for public gameplay WebSockets, with top-level `reason` drawn from the Gateway-owned `logout`, `session_replaced`, `service_restart`, `idle_timeout`, `policy_violation`, `internal_error`, or `backend_unavailable` taxonomy.
- `gateway.tcp_proxy_bridge.closes{reason,subreason,bridge_shutdown_class}` for authenticated TCP Proxy bridge observations only, with top-level `reason` drawn from the same fixed `logout`, `session_replaced`, `service_restart`, `idle_timeout`, `policy_violation`, `internal_error`, or `backend_unavailable` taxonomy as `gateway.websocket.closes`; bridge-only `bridge_shutdown_class` is restricted to `planned_drain`, `valid_upstream_close`, or `unattributed_failure`. Public WebSocket close metrics do not emit that label. `subreason` is optional diagnostic context restricted to `user_logout`, `takeover`, `gateway_restart`, `admin_termination`, `edge_backpressure`, or `none`, and neither field is lifecycle authority. Free-form diagnostic detail belongs only in structured logs, not in metric or wire values.
- `gateway.websocket.handshake.rejected`
- `gateway.websocket.slow_client_closes`

Non-`101` `/ws/game/**` handshake failures must emit the canonical bounded handshake error class in the gateway response and structured logs so clients and operators can distinguish `CONNECT_TOKEN_REJECTED`, `POLICY_DENY`, `BACKEND_UNAVAILABLE`, `CONNECT_REPLAY_PROTECTION_UNAVAILABLE`, and other documented retry classes. A canonical wire-level surface is the `X-Firemud-Handshake-Error-Class` response header paired with matching structured-log fields.

Readiness transition observability follows the shared contract from [Deployment Environments](../../infrastructure/deployment-environments.md):

- `firemud.readiness.current`
- `firemud.readiness.transitions`
- Structured logs keyed by `gameplayRoute`

## Implementation Status

The target metric split above is not live yet. Current `GameplayWebSocketObservability` emits `gateway.websocket.closes{reason,subreason,bridge_shutdown_class}` for every gameplay WebSocket close, including public WebSocket closes. Its legacy mapping keeps the top-level `logout` class for every currently recognized clean `1000` close: exact `logout;subreason=gateway_restart` is `planned_drain`, while other recognized logout forms (including `logout` with no or `none` subreason and takeover context) are `upstream_logout`; unrecognized or otherwise invalid closes use bridge shutdown class `unattributed_failure`. The target mapping removes `bridge_shutdown_class` from public `gateway.websocket.closes`, emits it only on authenticated `gateway.tcp_proxy_bridge.closes`, maps terminal logout to `logout`, displaced-controller takeover to `session_replaced` with `valid_upstream_close`, planned Gateway drain to `service_restart` with `planned_drain`, and every other valid authenticated top-level pair to `valid_upstream_close`; only missing or invalid top-level bridge metadata is `unattributed_failure` and surfaces `backend_unavailable` to Telnet. Required proof is a focused Gateway observability test for those metric shapes and mappings plus cross-service proof of Telnet parity, including malformed, duplicate, or conflicting optional subreason metadata normalizing to `none`. A temporary dashboard compatibility window is rollout policy rather than an ADR 0131 proof obligation; target convergence must not preserve `upstream_logout` as a permanent class. `bridge_shutdown_class` has no `none` or `not_applicable` value in the target contract.

Target normalization occurs before close-metric emission: validate the complete `(close code, top-level reason)` pair first, then normalize missing, unknown, malformed, duplicate, or conflicting `subreason` metadata to `none`; such metadata never changes a valid top-level class. Target `1000/logout` remains terminal `logout` regardless of subreason. Only a valid `1012/service_restart` with positive Gateway planned-drain evidence uses `bridge_shutdown_class=planned_drain`; every other valid authenticated top-level pair uses `valid_upstream_close`, while absent or invalid top-level bridge metadata uses `backend_unavailable` with `unattributed_failure`. See [Gateway Architecture canonical close matrix](../../system-architecture-gateway.md#canonical-close-translation-matrix).

## Operational Notes

- Spring Cloud Gateway runs as a Kubernetes Deployment and also supports Docker Compose for local development.
- Health probes use `/actuator/health/readiness` and `/actuator/health/liveness`.
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.
- gRPC endpoints use `LoggingInterceptor`, `MetricsInterceptor`, and `TracingInterceptor` for consistent observability.

## Dynamic Route Operational Guardrails

- Dynamic route mutation is an explicitly enabled local/dev/test capability, not an initial production control plane. The current implementation enforces default-off startup and an exclusive active `dev`/`test` profile allowlist; the remaining route-input and ingress restrictions below are target-state acceptance criteria.
- Current startup acceptance: enabled mutation under a player-facing, custom, mixed, or absent profile fails startup, regardless of persistence or convergence claims.
- Target-state acceptance: mutation endpoints remain internal-only and use the gateway-boundary authentication/authorization safeguards described in [Gateway Architecture](../../system-architecture-gateway.md#management-plane-security).
- Production emergency changes use an expedited baseline rollout or a predeclared bounded failover switch. Any generic production runtime control plane requires a separate future decision.

## Scalability

- The gateway scales horizontally to handle high concurrency.
- The gameplay-route readiness canary must remain short and operation-shaped; it is not a substitute for retry filters.
- Retry filters are resilience mechanisms, not evidence that the route is ready for fresh gameplay admission.
