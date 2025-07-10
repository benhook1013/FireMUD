# TCP Proxy Service

Refer to [design/README.md](design/README.md) for architecture details.

- **Proto definitions**: [../../protos/tcp-proxy/v1](../../protos/tcp-proxy/v1)
  include events used to notify the Game Session Service when clients disconnect
  and to push buffered commands after a reconnect.

## Running Locally

```bash
./gradlew :tcp-proxy-service:bootRun
```

To run the entire stack:

```bash
./gradlew devUp
```

## Configuration

Environment variables used by the service:

| Variable | Description | Default |
|----------|-------------|---------|
| `TCP_PROXY_PORT` | Port to accept Telnet connections | `2323` |
| `GATEWAY_WS_URL` | WebSocket endpoint for the Spring Cloud Gateway | `ws://spring-cloud-gateway:8080/ws` |

The proxy tags each connection with the player's `tenantId` during login so the
gateway can route commands to the correct game instance. See the
[Multi-Tenancy design](../../design/architecture/system-architecture-multi-tenancy.md)
for details.

### Dependencies

- **Spring Cloud Gateway** – receives proxied WebSocket traffic.
- **Game Session Service** – sessions are resumed via the `NotifyDisconnect` and
  `PushBufferedInput` events defined in the proto contracts.
