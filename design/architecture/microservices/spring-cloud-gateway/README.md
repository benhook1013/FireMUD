# Spring Cloud Gateway

This service exposes WebSocket and HTTP endpoints for all clients. It routes requests to backend services and integrates with the TCP Proxy Service for Telnet clients.

## Architecture / Design Notes

- Maintains persistent WebSocket sessions and supports raw TCP through a proxy.
- Event-driven updates synchronize game state across connected players.
- Includes a fallback mechanism so players with unstable connections can rejoin seamlessly.
- Applies rate limiting and authentication filters for admin endpoints.
- Relies on the Game Session Service for gameplay login and session management.

## Key Features

- Central API gateway and authentication point.
- Real-time state synchronization for multiplayer actions.
- Reconnection support for dropped clients.
- Routes REST and gRPC traffic to appropriate backend services.

### Filter Chain

- Authentication, rate limiting, and logging filters run before routing.
- WebSocket upgrades are handled with heartbeat and idle timeout logic.

### Key Routes

- `/api/session/**` → Game Session Service (WebSocket and REST endpoints).
- `/api/admin/**` → Logging & Admin Service with JWT authentication.
- `/api/design/**` → Game Design Service for content management.

## Dependencies

- **Internal:** Game Session Service and other microservices over gRPC.
- **External:** Spring Cloud Gateway infrastructure.

> See [**Gateway Architecture**](../../infrastructure/gateway-architecture.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../infrastructure/protocol-bridging.md) for
details on shared infrastructure components.

## Proto Files

Gateway-related proto definitions are stored in
[../../../../protos/spring-cloud-gateway/v1](../../../../protos/spring-cloud-gateway/v1).
After edits, run `./gradlew generateProto` to regenerate gateway stubs.

## 📚 Related Documentation

- [System Architecture Overview](../system-architecture-overview.md)
- [Reconnection Strategy](../system-architecture-reconnection.md)
- [Security Architecture](../system-architecture-security.md)
- [Multi-Tenancy](../system-architecture-multi-tenancy.md)
- [Service Responsibility Matrix](../service-responsibility-matrix.md)

## Future Enhancements

- Connection metrics and throttling.
- Horizontal scaling for high concurrency.
