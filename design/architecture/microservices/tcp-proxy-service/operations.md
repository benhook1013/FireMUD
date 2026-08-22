# TCP Proxy Service Operations

The target operational contract covers safe admission and readiness for new Telnet sessions, plus exact lifecycle and close attribution for established bridges. TCP Proxy remains the transport-edge operator surface; implementation status below records current convergence against those target concerns.

## Implementation Status

The current `TelnetServerHandler` preserves close reasons only for status `1000` reasons beginning with `logout`, exact `1001`/`idle_timeout`, status `1008` reasons beginning with `policy_violation`, and exact `1011`/`internal_error`; all other outcomes fall back to `backend_unavailable`. The two prefix checks are current implementation drift from the canonical delimiter-bound grammar: they incorrectly accept and forward arbitrary undelimited suffixes such as `logoutgarbage` and `policy_violationgarbage`, instead of requiring the exact top-level token optionally followed by one `;subreason=<bounded-value>` suffix and validating the complete code/reason pair. Bridge shutdown classification is `planned_drain` only for exact `logout;subreason=gateway_restart`, `upstream_logout` for every other currently prefix-accepted logout reason, and `unattributed_failure` otherwise. Broader canonical tokens such as standalone `session_replaced` and `service_restart`, complete token/subreason validation, and invalid-prefix fallback remain target behavior where they are not yet recognized.

## Operational Notes

- Runs as a Kubernetes Deployment, with Docker Compose for local development, and exposes `/actuator/health/readiness` and `/actuator/health/liveness`.
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.
- A simple `smoke-test.sh` script in the service directory checks the REST and gRPC endpoints.

## Readiness and Liveness Contract

- `liveness` is process-local only: the Spring Boot process is alive, the Netty event loops are not wedged, and the service can continue running.
- `readiness` is traffic-admission safety for new Telnet sessions. The service is ready only when:
  - the Telnet listener is bound;
  - the proxy can reach Spring Cloud Gateway’s readiness surface for the gameplay route; and
  - the current downstream gameplay admission path is safe for `connect -> LOGIN -> PLAY -> first LOOK`.
- While unready, the proxy must reject new Telnet sessions immediately with an explicit startup or unavailable message and close the connection. It must not silently accept the socket and let the first gameplay command discover startup races later.
- Loss of downstream readiness after a session is already established blocks new sessions but does not by itself imply that the proxy process is dead.
- Readiness transition observability uses the shared contract from [Deployment Environments](../../infrastructure/deployment-environments.md): `firemud.readiness.current`, `firemud.readiness.transitions`, and structured logs keyed by the curated dependency names `telnetListener` and `gatewayGameplayPath`.

## Metrics Summary

TCP Proxy metrics follow the global Micrometer/OpenTelemetry conventions described in [Logging & Monitoring](../../system-architecture-logging-monitoring.md). Key meters include:

- `tcpproxy.connections.total`, `tcpproxy.connections.active`, and `tcpproxy.buffer.depth`
- `tcpproxy.connections.limit.exceeded`
- `tcpproxy.connection.events{type="connect"|"disconnect"}` and `tcpproxy.connection.duration`
- `tcpproxy.command`, `tcpproxy.heartbeat`, `tcpproxy.idleClose`, `tcpproxy.websocket.reconnect.delay`, and `tcpproxy.websocket.reconnects`
- `tcpproxy.websocket.reconnects` covers initial bridge-establishment retries and breaker probe or recovery attempts only. It must not be interpreted as hidden recovery for already-established Telnet sessions, which fail-close when their gameplay bridge is lost.
- `tcpproxy.tls.misconfig` and `tcpproxy.gateway.handshake.failures{reason="..."}`
- `tcpproxy.telnet.discarded`
- `tcpproxy.disconnect.notify.transport_failure{status="<grpc_status>"}`
- `tcpproxy.disconnect.notify.app_error{code="<code>"}`
- `grpc_app_error_total{code="<code>"}`
- `mcp.greeting.mode_conflict` when duplicate MCP greeting ownership is detected
- Target `bridge_shutdown_class=planned_drain|valid_upstream_close|unattributed_failure` is bridge-only operational metadata, never lifecycle authority. Current close recognition and its legacy `upstream_logout` classification are defined in [Implementation Status](#implementation-status) above; standalone `session_replaced`, `1012/service_restart`, and the neutral valid-close class remain target-only until that boundary converges. See [Gateway Architecture](../../system-architecture-gateway.md) and [Protocol Bridging](../../system-architecture-protocol-bridging.md#telnet-disconnect-reasons).

Bounded labels and naming rules remain canonical. Detailed identifiers such as client IP, `gameInstanceId`, and error detail stay in structured logs and tracing spans rather than in high-cardinality metric labels.

For `tcpproxy.gateway.handshake.failures{reason="..."}`, the canonical bounded `reason` enum is:

- `bad_url`
- `dns`
- `connect_refused`
- `timeout`
- `cert_validation`
- `client_cert_missing`
- `client_cert_invalid`
- `handshake_protocol`
- `unexpected_close`
- `unknown`

Per-value meanings:

- `bad_url` – invalid `GATEWAY_WS_URL` configuration
- `dns` – host resolution failure
- `connect_refused` – target actively refused the TCP connection
- `timeout` – connect or handshake timed out
- `cert_validation` – server certificate validation or hostname verification failure
- `client_cert_missing` – server requested client auth but no client certificate was configured
- `client_cert_invalid` – client certificate present but rejected or invalid
- `handshake_protocol` – TLS or WebSocket handshake protocol error, such as unsupported versions, ciphers, or HTTP upgrade rejection
- `unexpected_close` – connection closed during handshake or reconnect
- `unknown` – fallback bucket for unexpected failures

`NotifyDisconnect` interpretation remains canonical for triage:

- transport failures are retried within `TCP_PROXY_NOTIFY_DISCONNECT_MAX_RETRY_MS`
- application errors are generally permanent outcomes
- `RESOURCE_EXHAUSTED` is the only application-error exception that may still be retried within the configured retry window

## Operational Runbook Hooks

When wiring alerts and runbooks for the TCP Proxy Service, focus on a small set of edge-centric indicators and interpret them alongside Gateway and Game Session signals:

- **Capacity and churn**
  - Alert when `tcpproxy.connections.limit.exceeded` sustains non-zero values.
  - Watch `tcpproxy.connections.active` and `tcpproxy.connection.duration` distributions for sudden drops in median lifetime or spikes in very short-lived connections.
- **Abuse and discard behavior**
  - Track `tcpproxy.telnet.discarded` and its low-cardinality `reason` label, especially bounded buckets such as `line_size`, `malformed_envelope`, `mcp_budget`, `gateway_buffer_full`, and `proxy_protocol`.
  - Combine discard alerts with application-layer metrics to decide whether to add capacity, tighten limits, or block specific sources.
- **Gateway and TLS health**
  - Alert on sustained `tcpproxy.gateway.handshake.failures{reason!="timeout"}` and on long tails in `tcpproxy.websocket.reconnect.delay`.
  - Cross-check these alerts with Gateway health and TLS/mTLS metrics so incidents are triaged at the correct layer.
- **NotifyDisconnect health**
  - Monitor `tcpproxy_disconnect_notify_transport_failure_total{status="<grpc_status>"}` for transport statuses such as `UNAVAILABLE` and `DEADLINE_EXCEEDED`.
  - Monitor canonical response/application errors in `grpc_app_error_total{service="tcp-proxy-service",code="<code>"}`. When present, `tcpproxy_disconnect_notify_app_error_total{code="<code>"}` is only a supplementary caller-side/local breakdown, not the canonical producer application-error meter.
  - Treat sustained increases in permanent response/application error codes as configuration or contract issues rather than transient incidents.
  - Treat `RESOURCE_EXHAUSTED` spikes as consumer-side overload and check Game Session saturation before changing retry settings.

## Metrics and Tracing

Metrics are exposed at `/actuator/prometheus` and scraped by Prometheus. The service exports OpenTelemetry spans to the collector defined by `OTEL_ENDPOINT` so traces appear in Jaeger.

In Prometheus these Micrometer meters appear with the expected naming translation, for example:

- `tcpproxy.connections.active` -> `tcpproxy_connections_active`
- `tcpproxy.connections.limit.exceeded` -> `tcpproxy_connections_limit_exceeded_total`
- `tcpproxy.telnet.discarded` -> `tcpproxy_telnet_discarded_total`
- `tcpproxy.websocket.reconnects` -> `tcpproxy_websocket_reconnects_total`

The example PromQL and Alertmanager rules in `design/observability/grafana/tcp-proxy-alerts-snippets.md` use these Prometheus-style names; treat this document as the canonical meter owner and the Grafana snippets as reference queries over them.

For operator interpretation, `bridge_shutdown_class` is bridge-only correlation metadata and never replaces Gateway’s external close taxonomy. Use the current close recognition and fallback mapping in [Implementation Status](#implementation-status); standalone `session_replaced` and `1012/service_restart` are target-only until that boundary converges. These operational classes and optional subreasons never establish command commit or change lifecycle/retry behavior. See [Gateway Architecture](../../system-architecture-gateway.md) and [Protocol Bridging](../../system-architecture-protocol-bridging.md#telnet-disconnect-reasons).

## Manual Endpoint Verification

- REST: `GET /ping` returns `"pong"`.

```bash
curl http://localhost:8080/ping
```

- Internal gRPC: `Ping(PingRequest) returns (PingResponse)` is the connectivity check.
- `NotifyDisconnect` is not an inbound TCP Proxy RPC to probe; it is an outbound call from TCP Proxy into the Game Session Service’s internal event sink.
- The proxy gRPC server listens on port `6565` by default as configured in `src/main/resources/application.yml`.

```bash
grpcurl -cacert "$FIREMUD_GRPC_CA_CERT_PATH" \
  -cert "$FIREMUD_GRPC_CERT_CHAIN_PATH" \
  -key "$FIREMUD_GRPC_PRIVATE_KEY_PATH" \
  localhost:6565 tcp_proxy.v1.TcpProxyService/Ping
```

For dev-only plaintext verification:

```bash
grpcurl -plaintext localhost:6565 tcp_proxy.v1.TcpProxyService/Ping
```

Even though the proxy has no public API, the supporting event messages are defined in [`../../../../protos/tcp-proxy/v1`](../../../../protos/tcp-proxy/v1). Regenerate stubs with `./gradlew generateProto` when the proto files change.

## Local Development

Avoid logging raw Telnet input during local debugging, especially `LOGIN` lines. If deep payload logging is enabled for debugging, it must be explicitly opt-in and redact credentials.

### Local Telnet Proof

For local operations work, use the canonical source-built or image-based smoke stack rather than a proxy-only echo shortcut. The maintained Telnet proof path is the same Gateway -> Game Session gameplay chain exercised by the repo smoke scripts.

## Cross-Service Integration Test

The `src/test/java/crossservice` directory contains an integration test that launches this service alongside Spring Cloud Gateway with Testcontainers.

This test requires the Spring Cloud Gateway Docker image to be available. Build it with `./gradlew :spring-cloud-gateway:bootBuildImage` or pull from the registry.

```bash
./gradlew :tcp-proxy-service:test --tests "*CrossServiceIntegrationTest"
```

See [System Architecture Testing](../../system-architecture-testing.md) for more information.
