# TCP Proxy Service

## Overview

Bridges legacy Telnet clients into the platform by converting raw TCP traffic into WebSocket connections for the Spring Cloud Gateway.

## Architecture / Design Notes

- Lightweight custom service separate from Spring Boot.
- Buffers incoming input during brief disconnects and clears it on connection loss.
- Handles Telnet negotiation and character encoding quirks.
- Works with the Reconnection Strategy to resume sessions transparently.

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

## Dependencies

- **Internal:** Spring Cloud Gateway, Game Session Service.
- **External:** None, runs as a standalone proxy.

### Data Model

The proxy is stateless. Any buffered input lives only in memory until forwarded
to the gateway.

> See [**Gateway Architecture**](../../infrastructure/gateway-architecture.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../infrastructure/protocol-bridging.md) for
details on how Telnet connections are integrated into the platform.

## Proto Files

Even though the proxy has no public API, supporting event messages are defined
in [../../../../protos/tcp-proxy/v1](../../../../protos/tcp-proxy/v1). Stubs are
regenerated via `./gradlew generateProto` when the proto files change.

## 📚 Related Documentation

- [System Architecture Overview](../system-architecture-overview.md)
- [Reconnection Strategy](../system-architecture-reconnection.md)
- [Security Architecture](../system-architecture-security.md)
- [Service Responsibility Matrix](../service-responsibility-matrix.md)
- [Multi-Tenancy](../system-architecture-multi-tenancy.md)

## Future Enhancements

- Connection throttling and rate limits.
- Support for SSL/TLS termination if required.
