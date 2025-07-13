# TCP Proxy Service

## Overview

Bridges legacy Telnet clients into the platform by converting raw TCP traffic into WebSocket connections for the Spring Cloud Gateway.

### Responsibilities

- Accept Telnet connections and perform protocol negotiation
- Proxy buffered input to Spring Cloud Gateway as WebSocket frames
- Tag connections with tenant information for accurate routing
- Provide graceful disconnect and reconnection handling

## Architecture / Design Notes

- Lightweight custom service separate from Spring Boot.
- Buffers incoming input during brief disconnects and clears it on connection loss.
- Handles Telnet negotiation and character encoding quirks.
- Works with the Reconnection Strategy to resume sessions transparently.
- Can optionally terminate Telnet-over-TLS and then forward traffic to the
  gateway using mutual TLS. See
  [Security Architecture](../system-architecture-security.md).
- During the login handshake the proxy tags the connection with the selected
  `tenantId` so the gateway can route commands to the proper game. See
  [Multi-Tenancy](../system-architecture-multi-tenancy.md).
- Runs in the network DMZ and never contacts internal services directly.
- Sanitizes incoming Telnet data and enforces a whitelist of
   **Telnet protocol commands** as described in the
   [Security Architecture](../system-architecture-security.md#telnet-command-handling-and-future-controls).
- Applies connection throttling and optional TLS termination.
- Utilizes the [Shared Libraries](../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.

## Key Features

- **Telnet Compatibility** — accepts standard MUD clients over TCP.
- **WebSocket Bridging** — forwards all traffic to the gateway via WebSocket.
- **Connection Buffering** — temporarily queues input to handle latency.
- **Graceful Disconnects** — informs the Game Session Service when a client drops.

### Data Flow

- TCP connections are accepted on a dedicated port and upgraded to WebSocket
  using a lightweight frame protocol.
- Incoming bytes are queued and forwarded to the gateway in order.
- If the connection is lost, the queue is flushed and the session is marked for
  possible reconnection.

### Service Interactions

The proxy does not expose a public client API. Instead it defines two gRPC
events used internally when communicating with other microservices:

- **NotifyDisconnect** – informs the Game Session Service when a Telnet client
    drops so the session may be suspended.
- **PushBufferedInput** – forwards any queued commands after a reconnect
    event.
These messages live in [`tcp_proxy_service.proto`](../../../protos/tcp-proxy/v1/tcp_proxy_service.proto).

### Telnet Command Handling

The proxy sanitizes incoming bytes and allows only a safe subset of
**Telnet protocol commands** as described in the
[Security Architecture](../system-architecture-security.md#telnet-command-handling-and-future-controls).
This avoids implementing the full Telnet specification while still protecting
against malformed negotiation sequences and other legacy edge cases.

## Dependencies

- **Internal:** Spring Cloud Gateway, Game Session Service.
- **External:** None, runs as a standalone proxy.

### Data Model

The proxy is stateless. Any buffered input lives only in memory until forwarded
to the Spring Cloud Gateway.

> See [**Gateway Architecture**](../system-architecture-gateway.md),
[**Deployment Environments**](../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../system-architecture-protocol-bridging.md) for
details on how Telnet connections are integrated into the platform.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health` probes. See [Deployment Environments](../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.

## Environment Variables

The proxy uses minimal configuration. It still follows the scheme in
[Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md)
so the standard `FIREMUD_POSTGRES_*` and `FIREMUD_REDIS_*` variables may be present
but are ignored.
TLS certificates are supplied via [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates). Peer services can be discovered using variables prefixed `FIREMUD_SERVICES_`.

Additional variables control the proxy runtime behaviour:

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `TCP_PROXY_PORT` | TCP port the proxy listens on | `2323` |
| `GATEWAY_WS_URL` | WebSocket URL for forwarding to the gateway | `ws://spring-cloud-gateway:8080/ws` |
| `TCP_PROXY_TLS_ENABLED` | Enable Telnet-over-TLS termination | `false` |
| `TCP_PROXY_TLS_CERT` | Path to the TLS certificate | *(empty)* |
| `TCP_PROXY_TLS_KEY` | Path to the TLS private key | *(empty)* |
| `TCP_PROXY_MAX_CONNECTIONS_PER_IP` | Maximum concurrent connections per client IP | `5` |
| `TCP_PROXY_MAX_MSGS_PER_SEC` | Allowed messages per second per client | `5` |

## Metrics & Tracing

Metrics are exposed at `/actuator/prometheus` and scraped by Prometheus. The
service exports OpenTelemetry spans to the collector defined by
`OTEL_ENDPOINT` (see [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md)) so traces appear in Jaeger.

## Proto Files

Even though the proxy has no public API, supporting event messages are defined
in [../../../../protos/tcp-proxy/v1](../../../../protos/tcp-proxy/v1). Stubs are
regenerated via `./gradlew generateProto` when the proto files change.

## 📚 Related Documentation

- [System Architecture Overview](../system-architecture-overview.md)
- [Reconnection Strategy](../system-architecture-reconnection.md)
- [Security Architecture](../system-architecture-security.md)
- [Service Responsibility Matrix](../service-responsibility-matrix.md)
- [User Journeys – Player Login and Gameplay](../user-journeys.md#7-player-login-and-gameplay)
- [Multi-Tenancy](../system-architecture-multi-tenancy.md)
- [gRPC API Style & Versioning Guidelines](../system-architecture-grpc.md)
- [Shared Libraries Overview](../system-architecture-shared-libraries.md)
- [Testing Strategy](../system-architecture-testing.md)
- [CI/CD Pipeline](../system-architecture-cicd.md)

## Additional Details

### REST & gRPC Endpoints

#### REST

- `GET /ping` – basic health check returning `"pong"`.

```bash
curl http://localhost:8080/ping
```

#### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check.
- `NotifyDisconnect(NotifyDisconnectRequest) returns (NotifyDisconnectResponse)` – informs the Game Session Service a Telnet client disconnected.
- `PushBufferedInput(PushBufferedInputRequest) returns (PushBufferedInputResponse)` – delivers queued commands after a reconnect.

All RPC definitions live in [`tcp_proxy_service.proto`](../../../protos/tcp-proxy/v1/tcp_proxy_service.proto).

```bash
grpcurl -plaintext localhost:6565 tcp_proxy.v1.TcpProxyService/Ping
```

Prometheus scrapes metrics from `/actuator/prometheus`. OpenTelemetry spans are exported to the collector service so traces can be viewed in Jaeger.

- [Logging & Monitoring](../system-architecture-logging-monitoring.md)
- [Backup & Disaster Recovery](../system-architecture-backup-recovery.md)

- [System Architecture Diagram](../system-architecture-diagram.md)
- [System Context Diagram](../system-context-diagram.md)

### Cross-Service Integration Test

The `src/test/java/crossservice` directory contains an integration test that
launches this service alongside Spring Cloud Gateway with **Testcontainers**.
Run it after the Gateway image is built:

```bash
./gradlew :tcp-proxy-service:test --tests "*CrossServiceIntegrationTest"
```

See [System Architecture Testing](../system-architecture-testing.md) for more
information.

## Future Enhancements

- Additional abuse heuristics and advanced command filtering.
- Auto-scaling policies for heavy traffic bursts.
