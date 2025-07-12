# Game Session Service

## Overview

Orchestrates live game sessions, including tick execution, player input validation, and runtime feature toggles. Acts as the central hub for gameplay state.

### Responsibilities

- Maintain session state and tick timing in Redis
- Queue player commands and dispatch them to Game Logic Service
- Broadcast lifecycle events and world updates to other services
- Support reconnection and recovery of running games

## Architecture / Design Notes

- Coordinates with Redis to store volatile session state and command queues.
- Communicates with other microservices exclusively via gRPC.
- Communicates game lifecycle changes to other services via gRPC so they can react to games starting or ending.
- Provides a single point of truth for current tick and world time.
- Ensures atomic command execution using Redis transactions and Lua scripts.
- Crash recovery replays ticks stored in Redis using AOF persistence and `WAIT`
  semantics, ensuring deterministic recovery as described in
  [Tick System and Runtime Design](../system-architecture-ticks.md#crash-recovery-and-replay).
- Every session record includes a `tenantId` identifying the game instance.
  Redis keys and database tables prefix this value so sessions from different
  games remain isolated. The platform may enforce per-game resource quotas at this level so one tenant cannot exhaust cluster capacity.
  See the [Multi-Tenancy](../system-architecture-multi-tenancy.md) document.
  Session state for reconnect recovery lives in Redis using keys of the form
  `session:{tenantId}:{sessionId}` and is purged when the session ends.
  - Restores sessions after disconnects and enforces single-session control as outlined in the Reconnection Strategy.
- Certain operations such as game startup and shutdown are implemented as Sagas
  so that all dependent services remain in sync. See
  [Transaction Strategies](../system-architecture-transactions.md).
- Saga workflows use the shared `SagaBuilder` and emit metrics with correlation
  IDs via `SagaRunner`.
- Monitors login attempts per IP and temporarily blacklists repeat offenders.
  Global spikes introduce small delays and suspicious activity triggers
  notification emails to the account holder. See
  [Security Architecture](../system-architecture-security.md#brute-force-defense-and-abuse-handling).
- Session objects are created as soon as a client connects. They remain unauthenticated until the Account Service verifies credentials and issues a token.
- Utilizes the [Shared Libraries](../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.

## Key Features

- **Session Lifecycle Management** — creates, resumes, and terminates player sessions.
- **Tick Orchestration** — drives the hybrid tick model for deterministic action processing.
- **Runtime Configuration** — stores runtime flag values created in the Game Design Service and activates published game versions.
- **Script Patch Awareness** — tracks an optional `script_patch_version` so live
  sessions can reload updated scripts without restarting.
- **Termination Handling** — cleans up resources and logs results when a game ends.
- **Instance Initialization** — starts new games from published templates.
- **Reconnection Handling** — resumes gameplay via Redis-backed session state as described in [Reconnection Strategy](../system-architecture-reconnection.md).
- **State Queries** — exposes gRPC methods to retrieve current game or player state for the web UI.

### Data Model

- `session` table tracks active games with a `runtime_version` referencing the published design version and an optional `script_patch_version` linked to that base version.
- `feature_flag` table stores runtime configuration overrides per tenant.
- Redis stores volatile queues, timers, and reconnect metadata.
- Redis session state records the active `script_patch_version` so it can be restored for replay or debugging.

### Tick Execution Model

- Each session advances in fixed-length ticks controlled by a Redis-based timer.
- Commands are collected during a tick and executed in deterministic order.
- After execution, results are persisted and broadcast to connected clients.

### gRPC APIs

- `StartSession` – spins up a game instance from a published version.
- `EnqueueCommand` – adds a player action to the next tick's queue.
- `QueryState` – retrieves condensed session or player state for monitoring.
- `ToggleFeatureFlag` – updates runtime flags for a tenant.

## Dependencies

- **Internal:**
  - Entity Management Service, Game Logic Service, World Management Service.
  - Logging & Admin Service receives session lifecycle events.
- **External:** Redis for session state.
- gRPC clients discover endpoints via `ServiceEndpointsProperties` and secure
  connections with mTLS certificates issued by cert-manager.

> See [**Gateway Architecture**](../system-architecture-gateway.md), [**Deployment Environments**](../infrastructure/deployment-environments.md), and [**Protocol Bridging**](../system-architecture-protocol-bridging.md) for details on shared infrastructure components.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health` probes. See [Deployment Environments](../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.

## Environment Variables

This service follows the configuration scheme from
[Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md).
It requires the [PostgreSQL credentials](../../infrastructure/environment-and-secrets.md#postgresql-credentials)
and [Redis connection](../../infrastructure/environment-and-secrets.md#redis-connection)
variables.
TLS certificates are supplied via [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates). Peer services can be discovered using variables prefixed `FIREMUD_SERVICES_`.

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

## Additional Details

### Configuration

Environment variables configure the PostgreSQL and Redis connections via `DatabaseAutoConfiguration` and `RedisProperties`. Refer to [Deployment Environments](../infrastructure/deployment-environments.md) for details. The `.env.sample` file contains example values.

The service enforces multi-tenant isolation. All tables include a `tenant_id` column and Redis keys are prefixed with this value as outlined in the [Multi-Tenancy design](../system-architecture-multi-tenancy.md).

### REST & gRPC Endpoints

#### REST

- `GET /ping` – basic health check returning `"pong"`.
- `POST /sessions` – create a new game session from a published version.
- `POST /sessions/{id}/stop` – stop a running session.
- `POST /sessions/{id}/restart` – restart a stopped session.

```bash
curl http://localhost:8080/ping
```

To start a session via REST:

```bash
curl -X POST http://localhost:8080/sessions \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"demo","runtimeVersion":"v42","scriptPatchVersion":"v42-script.3"}'
```

#### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`game_session_service.proto`](../../../protos/game-session/v1/game_session_service.proto).
- `StartSession(StartSessionRequest) returns (StartSessionResponse)` – creates a new game instance.
- `EnqueueCommand(EnqueueCommandRequest) returns (EnqueueCommandResponse)` – queues a player action.
- `QueryState(QueryStateRequest) returns (QueryStateResponse)` – retrieves current game or player state.

```bash
grpcurl -plaintext localhost:6565 game_session.v1.GameSessionService/Ping
```

Start a session via gRPC:

```bash
grpcurl -plaintext -d '{"tenantId":"demo","runtimeVersion":"v42","scriptPatchVersion":"v42-script.3"}' \
  localhost:6565 game_session.v1.GameSessionService/StartSession
```

### Additional Notes

- See the "Cross-Region Sharding and Session Handoff" section in the central [Game Session Service design](../microservices/game-session-service/README.md) for how sessions migrate between clusters.
- Metrics emitted by this service feed the operator [Analytics Dashboards](../microservices/logging-admin-service/analytics-dashboards.md). Prometheus scrapes metrics from `/actuator/prometheus`.
- Logs and metrics include a `script_patch_version` label so operators know which
  hotfix revision is currently active.

### Runtime Feature Flags

Feature flags are stored in the `feature_flag` table and can be toggled through the Logging & Admin Service. The Game Session Service exposes a gRPC `ToggleFeatureFlag` method so administrators can enable or disable experimental behavior without restarting a session.

### Saga Participation

Game startup and shutdown are coordinated using the shared `Saga` helpers from `firemud-common`. Each dependent service (World Management, Entity Management and Game Logic) confirms its part of the workflow before the session becomes active. Failures trigger compensating steps, ensuring consistent rollbacks. See [Transaction Strategies](../system-architecture-transactions.md) for background.

### Redis Keys

Session state needed for reconnect recovery is stored under `session:{tenantId}:{sessionId}`. Keys are removed when a session stops.

- [Logging & Monitoring](../system-architecture-logging-monitoring.md)
- [Backup & Disaster Recovery](../system-architecture-backup-recovery.md)
- [System Architecture Overview](../system-architecture-overview.md)
- [Service Responsibility Matrix](../service-responsibility-matrix.md)
- [User Journeys – Publish and Start a Game Instance](../user-journeys.md#5-publish-and-start-a-game-instance)
- [User Journeys – Player Login and Gameplay](../user-journeys.md#6-player-login-and-gameplay)

- [System Architecture Diagram](../system-architecture-diagram.md)
- [System Context Diagram](../system-context-diagram.md)

### Cross-Service Integration Test

An integration test under `src/test/java/crossservice` starts this service
alongside the Game Logic Service using **Testcontainers**. Run it manually once
the dependent Docker images are built:

```bash
./gradlew :game-session-service:test --tests "*CrossServiceIntegrationTest"
```

See [System Architecture Testing](../system-architecture-testing.md) for more
details.

## Future Enhancements

- Cross-region sharding for massive worlds.
- Built-in analytics for player behavior.

### Cross-Region Sharding and Session Handoff

Massive games may outgrow a single Kubernetes cluster. To support global player
bases, sessions can be sharded across regions using consistent hashing on the
`tenantId`. Each shard runs an independent Redis and database pair. When a
player travels to a region hosted elsewhere, the session state is serialized to
a compact protobuf and transferred via gRPC to the target cluster. The source
cluster marks the session as handed off and clients reconnect using the new
endpoint. This strategy minimizes latency while keeping per-region failure
domains isolated.

### Gameplay Analytics

The service emits Prometheus metrics for tick timing, queue lengths and command
latency. Logs include the `tenantId` and `traceId` fields so operators can build
dashboards in the Logging & Admin Service. These metrics feed the default
[Analytics Dashboards](../microservices/logging-admin-service/analytics-dashboards.md)
to monitor game health and player activity.
