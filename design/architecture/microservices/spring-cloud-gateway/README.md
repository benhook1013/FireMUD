# Spring Cloud Gateway

This service exposes WebSocket and HTTP endpoints for all clients. It routes requests to backend services and integrates with the TCP Proxy Service for Telnet clients.

## Architecture / Design Notes

- Maintains persistent WebSocket sessions and supports raw TCP through a proxy.
- Event-driven updates synchronize game state across connected players.
- Includes a fallback mechanism so players with unstable connections can rejoin seamlessly.

## Key Features

- Central API gateway and authentication point.
- Real-time state synchronization for multiplayer actions.
- Reconnection support for dropped clients.

## Dependencies

- **Internal:** Game Session Service and other microservices over gRPC.
- **External:** Spring Cloud Gateway infrastructure.

> See [**Gateway Architecture**](../../infrastructure/gateway-architecture.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../infrastructure/protocol-bridging.md) for
details on shared infrastructure components.

## 📚 Related Documentation

- [System Architecture Overview](../system-architecture-overview.md)

## Future Enhancements

- Connection metrics and throttling.
- Horizontal scaling for high concurrency.
