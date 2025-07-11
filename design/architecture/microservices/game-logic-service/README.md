# Game Logic Service

## Overview

Executes the core gameplay rules and command parsing. It processes player actions and determines outcomes.

### Responsibilities

- Parse player commands and resolve actions
- Apply combat rules, cooldowns, and environmental effects
- Interact with entity and world services for context data
- Push results back to the Game Session Service for distribution

## Architecture / Design Notes

- Stateless service accessed over gRPC by other microservices.
- Uses a modular command parser for extensibility.
- Deterministic rule execution; random seeds come from the Game Session Service.
- Fetches contextual world and entity data on demand via gRPC.
- Gameplay rules are imported from the Game Design Service when a version is
  published; the runtime service does not query design databases.
- Integrates with the tick system described in [Tick System and Runtime Design](../system-architecture-ticks.md) to ensure deterministic command ordering.
- Cross-service combat or trade operations run within ticks and rely on Redis-based rollback, not sagas. See [Transaction Strategies](../system-architecture-transactions.md).
- All commands are scoped by `tenantId` so that rules execute only against data
  for the active game instance. The Game Session Service passes this context on
  every request. See [Multi-Tenancy](../system-architecture-multi-tenancy.md).
- gRPC requests are authenticated with JWTs issued by the Account Service and
  validated via its JWKS endpoint. Communications use mutual TLS certificates as
  outlined in the [Security Architecture](../system-architecture-security.md).
- Utilizes the [Shared Libraries](../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.

## Key Features

- Command parsing and alias system.
- Rule processing for combat and progression.
- Emote and roleplay action handling.
- Event dispatcher for triggers and world events.
- Effect stacking and cooldown calculation.
- Environmental effect resolution (weather, lighting) influencing gameplay.
- Economy logic for trading, shops, and pricing adjustments.

### Data Model

This service is largely stateless. It relies on:

- Contextual entity and world data fetched from other services via gRPC.
- Temporary command queues stored in Redis by the Game Session Service.

### Command Flow

1. Commands are queued in Redis by the Game Session Service.
2. This service fetches the next command, loads the required context, and
   resolves the action to a rule engine module.
3. Results are pushed back to the session queue for delivery to players.

### gRPC APIs

- `Ping` – basic connectivity check.
- `ExecuteCommand` – evaluates a parsed command and returns the outcome.
- All responses include a `shared.v1.ErrorDetail` field for standardized error handling.

## Dependencies

- **Internal:**
  - Entity Management Service for characters and items.
  - World Management Service for room and region data.
  - Game Session Service supplies tick context and command queues.
  - Automation & Scripting Service triggers additional effects during rule execution.

> See [**Gateway Architecture**](../../infrastructure/gateway-architecture.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../infrastructure/protocol-bridging.md) for
details on shared infrastructure components.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.

## Environment Variables

This service follows the conventions in
[Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md).
It requires the [PostgreSQL credentials](../../infrastructure/environment-and-secrets.md#postgresql-credentials)
and [Redis connection](../../infrastructure/environment-and-secrets.md#redis-connection).

## Proto Files

gRPC service definitions can be found in
[../../../../protos/game-logic/v1](../../../../protos/game-logic/v1). Rebuild
the generated code with `./gradlew generateProto` after making changes.

## 📚 Related Documentation

- [System Architecture Overview](../system-architecture-overview.md)
- [Tick System and Runtime Design](../system-architecture-ticks.md)
- [Redis Architecture](../system-architecture-redis.md)
- [Multi-Tenancy](../system-architecture-multi-tenancy.md)
- [Authentication & Authorization](../system-architecture-authentication.md)
- [Security Architecture](../system-architecture-security.md)
- [Backup & Disaster Recovery](../system-architecture-backup-recovery.md)
- [Logging & Monitoring](../system-architecture-logging-monitoring.md)
- [gRPC API Style & Versioning Guidelines](../system-architecture-grpc.md)
- [Shared Libraries Overview](../system-architecture-shared-libraries.md)
- [User Journeys – Player Login and Gameplay](../user-journeys.md#5-player-login-and-gameplay)
- [Testing Strategy](../system-architecture-testing.md)
- [CI/CD Pipeline](../system-architecture-cicd.md)

## Additional Details

### REST & gRPC Endpoints

#### REST

- `GET /ping` – basic health check returning `"pong"`.
- `POST /command` – submit a gameplay command body as plain text.

```bash
curl http://localhost:8080/ping
```

#### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`game_logic_service.proto`](../../../protos/game-logic/v1/game_logic_service.proto).
- `ExecuteCommand(ExecuteCommandRequest) returns (ExecuteCommandResponse)` – process a command and return the result.

```bash
grpcurl -plaintext localhost:6565 game_logic.v1.GameLogicService/Ping
```

Expected response:

```json
{
  "message": "pong"
}
```

- [Service Responsibility Matrix](../service-responsibility-matrix.md)

- [System Architecture Diagram](../system-architecture-diagram.md)
- [System Context Diagram](../system-context-diagram.md)

## Future Enhancements

- Scripting hooks for custom actions.
- Performance optimizations for large-scale battles.
