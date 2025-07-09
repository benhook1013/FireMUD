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
- Future hardening includes whitelisting Telnet commands and sanitizing input as
  described in the [Security Architecture](../system-architecture-security.md#telnet-command-handling-and-future-controls).
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

The proxy does not expose its own public gRPC API. Instead it performs two
internal operations when communicating with other microservices:

- **NotifyDisconnect** – informs the Game Session Service when a Telnet client
    drops so the session may be suspended.
- **PushBufferedInput** – forwards any queued commands after a reconnect
    event.

### Telnet Command Handling

The proxy currently forwards Telnet input verbatim so that classic MUD clients
remain compatible. According to the [Security Architecture](../system-architecture-security.md#%F0%9F%94%8C-telnet-command-handling-and-future-controls),
future iterations will enforce a whitelisted subset of commands and sanitize
incoming bytes. These controls are planned to mitigate malformed command
injection and other legacy protocol edge cases.

## Dependencies

- **Internal:** Spring Cloud Gateway, Game Session Service.
- **External:** None, runs as a standalone proxy.

### Data Model

The proxy is stateless. Any buffered input lives only in memory until forwarded
to the Spring Cloud Gateway.

> See [**Gateway Architecture**](../../infrastructure/gateway-architecture.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../infrastructure/protocol-bridging.md) for
details on how Telnet connections are integrated into the platform.

## Operational Notes

- Deployed alongside the gateway in the DMZ as a lightweight container.
- Health is checked using a custom TCP probe defined in the Kubernetes manifest.
- Logs are forwarded via Fluent Bit and metrics are exported for Prometheus via
  a minimal collector endpoint.
- Configuration for local Docker Compose versus production clusters is described
  in [Deployment Environments](../../infrastructure/deployment-environments.md).

## Proto Files

Even though the proxy has no public API, supporting event messages are defined
in [../../../../protos/tcp-proxy/v1](../../../../protos/tcp-proxy/v1). Stubs are
regenerated via `./gradlew generateProto` when the proto files change.

## 📚 Related Documentation

- [System Architecture Overview](../system-architecture-overview.md)
- [Reconnection Strategy](../system-architecture-reconnection.md)
- [Security Architecture](../system-architecture-security.md)
- [Service Responsibility Matrix](../service-responsibility-matrix.md)
- [User Journeys – Player Login and Gameplay](../user-journeys.md#5-player-login-and-gameplay)
- [Multi-Tenancy](../system-architecture-multi-tenancy.md)
- [gRPC API Style & Versioning Guidelines](../system-architecture-grpc.md)
- [Shared Libraries Overview](../system-architecture-shared-libraries.md)
- [Testing Strategy](../system-architecture-testing.md)
- [CI/CD Pipeline](../system-architecture-cicd.md)
- [Logging & Monitoring](../system-architecture-logging-monitoring.md)
- [Backup & Disaster Recovery](../system-architecture-backup-recovery.md)

- [System Architecture Diagram](../system-architecture-diagram.md)
- [System Context Diagram](../system-context-diagram.md)

## Future Enhancements

- Connection throttling and rate limits.
- Support for SSL/TLS termination if required.
