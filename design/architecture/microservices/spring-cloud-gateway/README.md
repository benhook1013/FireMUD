# Spring Cloud Gateway

## Overview

This service exposes WebSocket and HTTP endpoints for all clients. It routes requests to backend services and integrates with the TCP Proxy Service for Telnet clients.

## Architecture / Design Notes

- Maintains persistent WebSocket sessions and supports raw TCP through a proxy.
- Event-driven updates synchronize game state across connected players.
- Includes a fallback mechanism so players with unstable connections can rejoin seamlessly.
- Applies rate limiting and authentication filters for admin endpoints.
- Relies on the Game Session Service for gameplay login and session management.
- Terminates external TLS and forwards traffic to backend services using mutual
  TLS, as described in the [Security Architecture](../system-architecture-security.md).
- Hostnames or path prefixes map incoming connections to a `tenantId` so the
  gateway can route players to the correct game instance. See
  [Multi-Tenancy](../system-architecture-multi-tenancy.md).

## Key Features

- Central API gateway and authentication point.
- Real-time state synchronization for multiplayer actions.
- Reconnection support for dropped clients.
- Routes REST and gRPC traffic to appropriate backend services.

### Data Model

The gateway is stateless. Route configurations are stored in
`application-*.yml` and reloaded on startup. No persistent database is required.
The default configuration defines routes for the core services so Docker Compose
environments work out of the box.

### Filter Chain

- Authentication, rate limiting, and logging filters run before routing.
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

- Runs as a stateless gateway Deployment in Kubernetes, typically exposed via a
  load balancer service.
- `/actuator/health` endpoints are used for readiness and liveness probes.
- Prometheus scrapes metrics such as connection counts while Fluent Bit forwards
  structured logs to Elasticsearch; tracing integrates with OpenTelemetry.
- [Deployment Environments](../../infrastructure/deployment-environments.md)
  explains how routes and certificates differ between Docker Compose and
  production clusters.

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
- [User Journeys – Player Login and Gameplay](../user-journeys.md#5-player-login-and-gameplay)
- [gRPC API Style & Versioning Guidelines](../system-architecture-grpc.md)
- [Shared Libraries Overview](../system-architecture-shared-libraries.md)
- [Testing Strategy](../system-architecture-testing.md)
- [CI/CD Pipeline](../system-architecture-cicd.md)
- [Logging & Monitoring](../system-architecture-logging-monitoring.md)
- [Backup & Disaster Recovery](../system-architecture-backup-recovery.md)

## Future Enhancements

- Connection metrics and throttling.
- Horizontal scaling for high concurrency.
