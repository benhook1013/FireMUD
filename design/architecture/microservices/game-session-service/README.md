# Game Session Service

## Overview

Orchestrates live game sessions, including tick execution, player input validation, and runtime feature toggles. Acts as the central hub for gameplay state.

## Architecture / Design Notes

- Coordinates with Redis to store volatile session state and command queues.
- Communicates with other microservices exclusively via gRPC.
- Communicates game lifecycle changes to other services via gRPC so they can react to games starting or ending.
- Provides a single point of truth for current tick and world time.
- Ensures atomic command execution using Redis transactions and Lua scripts.
- Crash recovery replays ticks stored in Redis using AOF persistence and `WAIT`
  semantics, ensuring deterministic recovery as described in
  [Tick System and Runtime Design](../system-architecture-ticks.md#crash-recovery-and-replay).
- Restores sessions after disconnects and enforces single-session control as outlined in the Reconnection Strategy.
- Certain operations such as game startup and shutdown are implemented as Sagas
  so that all dependent services remain in sync. See
  [Transaction Strategies](../system-architecture-transactions.md).

## Key Features

- **Session Lifecycle Management** — creates, resumes, and terminates player sessions.
- **Tick Orchestration** — drives the hybrid tick model for deterministic action processing.
- **Runtime Configuration** — stores runtime flag values created in the Game Design Service and activates published game versions.
- **Termination Handling** — cleans up resources and logs results when a game ends.
- **Instance Initialization** — starts new games from published templates.
- **Reconnection Handling** — resumes gameplay via Redis-backed session state as described in [Reconnection Strategy](../system-architecture-reconnection.md).
- **State Queries** — exposes gRPC methods to retrieve current game or player state for the web UI.

### Data Model

- `session` table tracks active games, associated `version_id`, and owner account.
- Redis stores volatile queues, timers, and reconnect metadata.

### Tick Execution Model

- Each session advances in fixed-length ticks controlled by a Redis-based timer.
- Commands are collected during a tick and executed in deterministic order.
- After execution, results are persisted and broadcast to connected clients.

### gRPC APIs

- `StartSession` – spins up a game instance from a published version.
- `EnqueueCommand` – adds a player action to the next tick's queue.
- `QueryState` – retrieves condensed session or player state for monitoring.

## Dependencies

- **Internal:** Entity Management Service, Game Logic Service, World Management Service.
- **External:** Redis for session state.

> See [**Gateway Architecture**](../../infrastructure/gateway-architecture.md), [**Deployment Environments**](../../infrastructure/deployment-environments.md), and [**Protocol Bridging**](../../infrastructure/protocol-bridging.md) for details on shared infrastructure components.

## Operational Notes

- Deployed in Kubernetes with multiple replicas; Redis provides the shared state needed for sticky sessions.
- Exposes `/actuator/health` for readiness and liveness probes used by the cluster.
- Metrics and traces flow to Prometheus and OpenTelemetry, and logs are shipped via Fluent Bit to Elasticsearch.
- Refer to [Deployment Environments](../../infrastructure/deployment-environments.md) for differences between Docker Compose and production setups.

## Proto Files

Service definitions reside in
[../../../../protos/game-session/v1](../../../../protos/game-session/v1). Run
`./gradlew generateProto` after modifying these files to regenerate stubs.

## 📚 Related Documentation

 - [Versioning & Runtime Configuration](../system-architecture-versioning-runtime.md) — how game instances load published versions and runtime flags.
 - [Reconnection Strategy](../system-architecture-reconnection.md)
 - [Authentication & Authorization](../system-architecture-authentication.md)
- [Tick System and Runtime Design](../system-architecture-ticks.md)
- [Redis Architecture](../system-architecture-redis.md)
- [Multi-Tenancy](../system-architecture-multi-tenancy.md)
- [gRPC API Style & Versioning Guidelines](../system-architecture-grpc.md)
- [Shared Libraries Overview](../system-architecture-shared-libraries.md)
- [Testing Strategy](../system-architecture-testing.md)
- [CI/CD Pipeline](../system-architecture-cicd.md)
- [System Architecture Overview](../system-architecture-overview.md)
- [Service Responsibility Matrix](../service-responsibility-matrix.md)

## Future Enhancements

- Cross-region sharding for massive worlds.
- Built-in analytics for player behavior.
