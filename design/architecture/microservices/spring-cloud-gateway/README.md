# Spring Cloud Gateway

## Overview

This service exposes WebSocket and HTTP endpoints for all clients. It routes requests to backend services and integrates with the TCP Proxy Service for Telnet clients.

### Responsibilities

- Terminate TLS and enforce authentication for admin routes
- Upgrade WebSocket connections and route to the correct tenant
- Apply rate limits and basic abuse protections
- Relay traffic to the Game Session Service and other backends

## Architecture / Design Notes

- Maintains persistent WebSocket sessions and supports raw TCP through a proxy.
- Event-driven updates synchronize game state across connected players.
- Includes a fallback mechanism so players with unstable connections can rejoin seamlessly.
- Gateway restarts are transparent thanks to the layered reconnection model
  outlined in [Reconnection Strategy](../system-architecture-reconnection.md).
- Applies rate limiting and authentication filters for admin endpoints.
- Relies on the Game Session Service for gameplay login and session management.
- Terminates external TLS and forwards traffic to backend services using mutual
  TLS, as described in the [Security Architecture](../system-architecture-security.md).
- Hostnames or path prefixes map incoming connections to a `tenantId` so the
  gateway can route players to the correct game instance. See
  [Multi-Tenancy](../system-architecture-multi-tenancy.md).
- Utilizes the [Shared Libraries](../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.

## Key Features

- Central API gateway and authentication point.
- Real-time state synchronization for multiplayer actions.
- Reconnection support for dropped clients.
- Routes REST and gRPC traffic to appropriate backend services.
- Supports dynamic route management via the `GatewayManagementService` gRPC API.

### Data Model

The gateway is stateless and sits in the DMZ alongside the TCP Proxy Service.
Route configurations are stored in `application-*.yml` and reloaded on startup.
No persistent database is required.
The default configuration defines routes for the core services so Docker Compose
environments work out of the box.

### Filter Chain

- Authentication, rate limiting, and logging filters run before routing.
- `JwtAuthFilter` validates admin JWTs using `JwtUtil` from the common library.
- WebSocket upgrades are handled with heartbeat and idle timeout logic.

### Key Routes

- `/api/session/**` → Game Session Service (WebSocket and REST endpoints).
- `/api/admin/**` → Logging & Admin Service with JWT authentication.
- `/api/design/**` → Game Design Service for content management.

## Dependencies

- **Internal:**
  - Game Session Service and other microservices over gRPC.
  - TCP Proxy Service forwards Telnet traffic into the gateway.
- **External:** Spring Cloud Gateway infrastructure.

> See [**Gateway Architecture**](../../infrastructure/gateway-architecture.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../infrastructure/protocol-bridging.md) for
details on shared infrastructure components.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.

## Environment Variables

The gateway reads configuration from environment variables so both Docker Compose
and Kubernetes deployments behave consistently. Important variables include:

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `SPRING_PROFILES_ACTIVE` | Spring profile to load (`dev` or `prod`) | `dev` |
| `SERVER_PORT` | HTTP port exposed by the service | `8080` |

The database variables (`FIREMUD_POSTGRES_*` and `FIREMUD_REDIS_*`) are included
for consistency across services but are not used by the gateway.

## Proto Files

Gateway-related proto definitions are stored in
[../../../../protos/spring-cloud-gateway/v1](../../../../protos/spring-cloud-gateway/v1).
After edits, run `./gradlew generateProto` to regenerate gateway stubs.
The `gateway_management_service.proto` file defines gRPC endpoints for remotely
adding or removing routes at runtime.

## 📚 Related Documentation

- [System Architecture Overview](../system-architecture-overview.md)
- [Reconnection Strategy](../system-architecture-reconnection.md)
- [Security Architecture](../system-architecture-security.md)
- [Multi-Tenancy](../system-architecture-multi-tenancy.md)
- [Service Responsibility Matrix](../service-responsibility-matrix.md)
- [User Journeys – Player Login and Gameplay](../user-journeys.md#5-player-login-and-gameplay)
- [gRPC API Style & Versioning Guidelines](../system-architecture-grpc.md)
- [Shared Libraries Overview](../system-architecture-shared-libraries.md)
- [Testing Strategy](../system-architecture-testing.md)
- [CI/CD Pipeline](../system-architecture-cicd.md)
- [Logging & Monitoring](../system-architecture-logging-monitoring.md)
- [Backup & Disaster Recovery](../system-architecture-backup-recovery.md)

- [System Architecture Diagram](../system-architecture-diagram.md)
- [System Context Diagram](../system-context-diagram.md)

## Future Enhancements

- Horizontal scaling for high concurrency.
