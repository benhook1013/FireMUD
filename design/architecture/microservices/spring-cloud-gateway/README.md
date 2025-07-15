# Spring Cloud Gateway

## Overview

This service exposes WebSocket and HTTP endpoints for all clients. It routes requests to backend services and integrates with the TCP Proxy Service for Telnet clients.

An OpenAPI specification for these REST endpoints lives in `services/spring-cloud-gateway/src/main/resources/openapi.yaml`.

### Responsibilities

- Terminate TLS and enforce authentication for admin routes
- Upgrade WebSocket connections and forward them to backend services
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
- Utilizes the [Shared Libraries](../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.
- gRPC endpoints use `LoggingInterceptor`, `MetricsInterceptor`, and `TracingInterceptor` for consistent observability.

## Key Features

- Central API gateway and authentication point.
- Real-time state synchronization for multiplayer actions.
- Reconnection support for dropped clients.
- Routes REST and gRPC traffic to appropriate backend services.
- Supports dynamic route management via the `GatewayManagementService` gRPC API.

### Data Model

The gateway is stateless and sits in the DMZ alongside the TCP Proxy Service.
Route configurations live in `routes-dev.yml` and `routes-prod.yml`, which are
imported by `application.yml` based on the active profile and reloaded on
startup. No persistent database is required.
The default configuration defines routes for the core services so Docker Compose
environments work out of the box.

### Filter Chain

- Authentication, rate limiting, and logging filters run before routing.
- `JwtAuthFilter` requires an `Authorization` header on admin routes and forwards the JWT unmodified. Validation occurs in the consuming service.
- WebSocket upgrades are handled with heartbeat and idle timeout logic.

### Key Routes

- `/api/session/**` → Game Session Service (WebSocket and REST endpoints).
- `/api/admin/**` → Logging & Admin Service (tokens are verified by the service).
- `/api/design/**` → Game Design Service for content management.
- `/api/account/**` → Account Service for user profiles.
- `/api/automation/**` → Automation Scripting Service.
- `/api/entity/**` → Entity Management Service.
- `/api/logic/**` → Game Logic Service.
- `/api/social/**` → Social Groups Service.
- `/api/world/**` → World Management Service.

## Dependencies

- **Internal:**
  - Game Session Service and other microservices over gRPC.
  - TCP Proxy Service forwards Telnet traffic into the gateway.
- **External:** Spring Cloud Gateway infrastructure.

> See [**Gateway Architecture**](../system-architecture-gateway.md),
[**Deployment Environments**](../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../system-architecture-protocol-bridging.md) for
details on shared infrastructure components.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health` probes. See [Deployment Environments](../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.

## Environment Variables

The gateway reads configuration from environment variables so both Docker Compose
and Kubernetes deployments behave consistently. It follows
[Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md).
The database variables
([PostgreSQL credentials](../../infrastructure/environment-and-secrets.md#postgresql-credentials)
and [Redis connection](../../infrastructure/environment-and-secrets.md#redis-connection))
may be present for consistency. PostgreSQL variables are unused, but Redis
connection variables are required for the `RequestRateLimiter` filter.
TLS certificates are supplied via [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates). Peer services can be discovered using variables prefixed `FIREMUD_SERVICES_`.
The OpenTelemetry collector endpoint can be overridden via `OTEL_ENDPOINT` (see [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md)).

Important variables include:

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `SERVER_PORT` | HTTP port exposed by the service | `8080` |

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
- [User Journeys – Player Login and Gameplay](../user-journeys.md#7-player-login-and-gameplay)
- [gRPC API Style & Versioning Guidelines](../system-architecture-grpc.md)
- [Shared Libraries Overview](../system-architecture-shared-libraries.md)
- [Testing Strategy](../system-architecture-testing.md)
- [CI/CD Pipeline](../system-architecture-cicd.md)

## Additional Details

### REST & gRPC Endpoints

#### REST

- `GET /ping` – basic health check returning `"pong"`.
- `POST /routes` – add or update a custom gateway route.
- `DELETE /routes/{routeId}` – remove a gateway route.

```bash
curl http://localhost:8080/ping
```

Add a route via REST:

```bash
curl -X POST http://localhost:8080/routes \
  -H 'Content-Type: application/json' \
  -d '{"routeId":"demo","uri":"http://example.com","predicates":[],"filters":[]}'
```

Remove it:

```bash
curl -X DELETE http://localhost:8080/routes/demo
```

#### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`gateway_management_service.proto`](../../../../protos/spring-cloud-gateway/v1/gateway_management_service.proto).
- `UpsertRoute(RouteDefinition) returns (RouteResponse)` – adds or updates a gateway route.
- `RemoveRoute(RouteRequest) returns (RouteResponse)` – deletes a route.

```bash
grpcurl -plaintext localhost:6565 spring_cloud_gateway.v1.GatewayManagementService/Ping
```

- [Logging & Monitoring](../system-architecture-logging-monitoring.md)
- [Backup & Disaster Recovery](../system-architecture-backup-recovery.md)

- [System Architecture Diagram](../system-architecture-diagram.md)
- [System Context Diagram](../system-context-diagram.md)

## Future Enhancements

- Horizontal scaling for high concurrency.
