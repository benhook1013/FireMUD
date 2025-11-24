# TCP Proxy Service

## Overview

Bridges legacy Telnet clients into the platform by converting raw TCP traffic into WebSocket connections for the Spring Cloud Gateway.
The OpenAPI specification for the `/ping` health endpoint lives in `services/tcp-proxy-service/src/main/resources/openapi.yaml`.

### Responsibilities

- Accept Telnet connections and perform protocol negotiation
- Proxy buffered input to Spring Cloud Gateway as WebSocket frames with
  automatic resends after reconnect
- Provide graceful disconnect and reconnection handling

## Architecture / Design Notes

- Spring Boot service hosting a lightweight Netty-based Telnet server.
- Buffers incoming input while the client remains connected and discards it if the
  TCP session drops. After reconnect, buffered commands are resent automatically.
- Handles Telnet negotiation and character encoding quirks.
- Negotiates the Mud Client Protocol (MCP) when supported. See [MCP Support](../../system-architecture-mcp-support.md).
- Works with the Reconnection Strategy to resume sessions transparently.
- Can optionally terminate Telnet-over-TLS. Forwarding to the gateway uses
  WebSocket connections and supports mutual TLS.
  See [Security Architecture](../../system-architecture-security.md).
- Runs in the network DMZ and never contacts internal services directly.
- Sanitizes incoming Telnet data and enforces a whitelist of
   **Telnet protocol commands** as described in the
   [Security Architecture](../../system-architecture-security.md#telnet-command-handling-and-controls).
- Forwards client IPs via `X-Client-IP` so central throttling occurs in the Game Session Service. Optional TLS termination is controlled by `TCP_PROXY_TLS_ENABLED`.
- Performs basic sanitization but defers connection and rate limits to downstream services.
- Utilizes the [Shared Libraries](../../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.

## Key Features

- **Telnet Compatibility** — accepts standard MUD clients over TCP.
- **WebSocket Bridging** — forwards all traffic to the gateway via WebSocket.
- **Connection Buffering** — temporarily queues input to handle latency.
- **Graceful Disconnects** — informs the Game Session Service when a client drops.

### Data Flow

- TCP connections are accepted on a dedicated port and proxied to the gateway
  using a lightweight WebSocket bridge.
- Incoming bytes are queued and forwarded to the gateway in order.
- If the connection is lost, the queue is flushed, and reconnection hooks
  automatically restore buffered input.

### Service Interactions

The proxy does not expose a public client API. Instead it emits two gRPC events
for internal coordination:

- **NotifyDisconnect** – informs the Game Session Service when a Telnet client
    drops so the session may be suspended.
- **PushBufferedInput** – forwards any queued commands after a reconnect event.

These events let the Game Session Service resume suspended sessions and deliver
buffered commands. Their definitions live in
[`tcp_proxy_service.proto`](../../../../protos/tcp-proxy/v1/tcp_proxy_service.proto).

### Telnet Command Handling

The proxy sanitizes incoming bytes and allows only a safe subset of
**Telnet protocol commands** as described in the
[Security Architecture](../../system-architecture-security.md#telnet-command-handling-and-controls).
This avoids implementing the full Telnet specification while still protecting
against malformed negotiation sequences and other legacy edge cases.

Example filtering logic from `TelnetServerHandler`:

```java
private static final byte IAC = (byte) 255;
private static final Set<Byte> ALLOWED_COMMANDS =
    Set.of((byte) 240, (byte) 241, (byte) 249, (byte) 251, (byte) 252, (byte) 253, (byte) 254);
```

Commands outside this list are discarded and only sanitized printable characters are forwarded to the gateway.

## Dependencies

- **Internal:** Spring Cloud Gateway, Game Session Service.
- **External:** None, runs as a standalone proxy.

### Data Model

The proxy is stateless. Any buffered input lives only in memory until forwarded
to the Spring Cloud Gateway.

> See [**Gateway Architecture**](../../system-architecture-gateway.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../system-architecture-protocol-bridging.md) for
details on how Telnet connections are integrated into the platform.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.
- A simple `smoke-test.sh` script in the service directory checks the REST and gRPC endpoints.

### Local development and echo loop

Use the bundled `/dev/echo` WebSocket endpoint under the `dev` profile to validate the Telnet -> WebSocket bridge without running the full gateway stack:

1. Start the service: `./gradlew :services:tcp-proxy-service:bootRun`. The task defaults to the `dev` profile for local runs. Override with `SPRING_PROFILES_ACTIVE=<profile>` or `-Dspring.profiles.active=<profile>` when needed.
2. The dev profile disables gRPC TLS by default. Enable it with `GRPC_SERVER_TLS_ENABLED=true` to use the sample certificates in `src/main/resources/certs`.
3. For non-dev profiles (including production), TLS and mutual auth remain enabled unless explicitly disabled, and the `/dev/echo` WebSocket is not exposed.
4. Point the bridge at the local echo when running with the dev profile: `GATEWAY_WS_URL=ws://localhost:8080/dev/echo`.
5. Connect from a Telnet/MUD client: `telnet localhost 2323`.
6. Type any text. The proxy logs the input at INFO and echoes the same text back over the Telnet session.

When pointing at a real gateway, override `GATEWAY_WS_URL` with its WebSocket endpoint. Outside of the `dev` profile the default remains `ws://spring-cloud-gateway:8080/ws` so production pods continue to forward to the cluster gateway when the variable is unset.

## Environment Variables

The proxy uses minimal configuration. It still follows the scheme in
[Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md)
so the standard `FIREMUD_POSTGRES_*` and `FIREMUD_REDIS_*` variables may be present
but are ignored.
TLS certificates are supplied via [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates).

Additional variables control the proxy runtime behaviour:

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `TCP_PROXY_PORT` | TCP port the proxy listens on | `2323` |
| `GATEWAY_WS_URL` | WebSocket URL for forwarding to the gateway | `ws://spring-cloud-gateway:8080/ws` |
| `TCP_PROXY_TLS_ENABLED` | Enable Telnet-over-TLS termination | `false` |
| `TCP_PROXY_TLS_CERT` | Path to the TLS certificate | *(empty)* |
| `TCP_PROXY_TLS_KEY` | Path to the TLS private key | *(empty)* |

The gRPC server listens on port `6565` by default as configured in `src/main/resources/application.yml`.

### Metrics & Tracing

Metrics are exposed at `/actuator/prometheus` and scraped by Prometheus. The
service exports OpenTelemetry spans to the collector defined by
`OTEL_ENDPOINT` (see [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md)) so traces appear in Jaeger.

## Proto Files

Even though the proxy has no public API, supporting event messages are defined
in [../../../../protos/tcp-proxy/v1](../../../../protos/tcp-proxy/v1). Stubs are
regenerated via `./gradlew generateProto` when the proto files change.

## 📚 Related Documentation

- [System Architecture Overview](../../system-architecture-overview.md)
- [Reconnection Strategy](../../system-architecture-reconnection.md)
- [Security Architecture](../../system-architecture-security.md)
- [Service Responsibility Matrix](../../service-responsibility-matrix.md)
- [User Journeys – Player Login and Gameplay](../../user-journeys.md#7-player-login-and-gameplay)
- [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- [gRPC API Style & Versioning Guidelines](../../system-architecture-grpc.md)
- [Shared Libraries Overview](../../system-architecture-shared-libraries.md)
- [Testing Strategy](../../system-architecture-testing.md)
- [CI/CD Pipeline](../../system-architecture-cicd.md)

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

All RPC definitions live in [`tcp_proxy_service.proto`](../../../../protos/tcp-proxy/v1/tcp_proxy_service.proto).

```bash
grpcurl -plaintext localhost:6565 tcp_proxy.v1.TcpProxyService/Ping
```

Prometheus scrapes metrics from `/actuator/prometheus`. OpenTelemetry spans are exported to the collector service so traces can be viewed in Jaeger.

- [Logging & Monitoring](../../system-architecture-logging-monitoring.md)
- [Backup & Disaster Recovery](../../system-architecture-backup-recovery.md)

- [System Architecture Diagram](../../system-architecture-diagram.md)
- [System Context Diagram](../../system-context-diagram.md)

### Cross-Service Integration Test

The `src/test/java/crossservice` directory contains an integration test that
launches this service alongside Spring Cloud Gateway with **Testcontainers**.
Run it after the Gateway image is built:

This test requires the Spring Cloud Gateway Docker image to be available. Build it with `./gradlew :spring-cloud-gateway:bootBuildImage` or pull from the registry.

```bash
./gradlew :tcp-proxy-service:test --tests "*CrossServiceIntegrationTest"
```

See [System Architecture Testing](../../system-architecture-testing.md) for more
information.
