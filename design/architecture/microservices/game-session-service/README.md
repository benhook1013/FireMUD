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
  [Tick System and Runtime Design](../../system-architecture-ticks.md#crash-recovery-and-replay).
- Every session record includes a `tenantId` identifying the game instance.
  Redis keys and database tables prefix this value so sessions from different
  games remain isolated. The platform may enforce per-game resource quotas at this level so one tenant cannot exhaust cluster capacity.
  See the [Multi-Tenancy](../../system-architecture-multi-tenancy.md) document.
  Session state for reconnect recovery lives in Redis using keys of the form
  `session:{tenantId}:{sessionId}` and is purged when the session ends. All tick queues, locks and pending sets share this tenant-prefixed scheme.
  - Restores sessions after disconnects and enforces single-session control as outlined in the Reconnection Strategy.
- Certain operations such as game startup and shutdown are implemented as Sagas
  so that all dependent services remain in sync. See
  [Transaction Strategies](../../system-architecture-transactions.md).
- Saga workflows use the shared `SagaBuilder` and emit metrics with correlation
  IDs via `SagaRunner`.
- Monitors login attempts per IP and temporarily blacklists repeat
  offenders. Global spikes introduce small delays and suspicious activity
  triggers notification emails to the account holder. See
  [Security Architecture](../../system-architecture-security.md#brute-force-defense-and-abuse-handling).
- Session objects are created as soon as a client connects. They remain unauthenticated until the Account Service verifies credentials and issues a token.
- Utilizes the [Shared Libraries](../../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.

## Key Features

- **Session Lifecycle Management** — creates, resumes, and terminates player sessions.
- **Tick Orchestration** — drives the hybrid tick model for deterministic action processing.
- **Runtime Configuration** — stores runtime flag values created in the Game Design Service and activates published game versions.
- **Script Patch Awareness** — tracks an optional `script_patch_version` so live
  sessions can reload updated scripts without restarting.
- **Termination Handling** — cleans up resources and logs results when a game ends.
- **Instance Initialization** — starts new games from published templates.
- **Reconnection Handling** — resumes gameplay via Redis-backed session state as described in [Reconnection Strategy](../../system-architecture-reconnection.md).
- **State Queries** — exposes gRPC methods to retrieve current game or player state for the web UI.

### Data Model

- `game_instances` table tracks running sessions with columns `tenant_id`, `runtime_version`, optional `script_patch_version`, `owner_account_id`, `status` (`RUNNING` or `STOPPED`), and `created_at`.
- `feature_flag` table stores runtime configuration overrides per tenant.
- `game_manifest` table lists available runtime versions that can be started.
- Redis stores volatile queues, timers, and reconnect metadata.
- Redis session state records the active `script_patch_version` so it can be restored for replay or debugging.

### Tick Execution Model

- Each session advances in fixed-length ticks controlled by a Redis-based timer.
- Commands are collected during a tick and executed in deterministic order.
- The staging Lua script only moves a limited number of commands each tick
  (`GAME_TICK_MAX_COMMANDS`) so one player cannot starve others.
- Commands with `requiresSoloTick: true` are dequeued into an isolated tick so expensive operations like runtime procedural generation do not share time with normal actions.
- After execution, results are persisted and broadcast to connected clients.

### gRPC APIs

- `Ping` – basic connectivity check.
- `StartSession` – spins up a game instance from a published version.
- `StopSession` – stops a running session.
- `RestartSession` – restarts a stopped session.
- `EnqueueCommand` – adds a player action to the next tick's queue.
- `QueryState` – retrieves condensed session or player state for monitoring.
- `ToggleFeatureFlag` – updates runtime flags for a tenant.
- `PauseTicks` – temporarily halt tick execution before a backup.
- `ResumeTicks` – resume tick processing after the backup begins.
- `GetTickStatus` – returns `RUNNING` or `PAUSED` for backup orchestration.

## Dependencies

- **Internal:**
  - Entity Management Service, Game Logic Service, World Management Service.
  - Logging & Admin Service receives session lifecycle events.
- **External:** Redis for session state.
- gRPC clients discover endpoints via `ServiceEndpointsProperties` and secure
  connections with mTLS certificates issued by cert-manager.

> See [**Gateway Architecture**](../../system-architecture-gateway.md), [**Deployment Environments**](../../infrastructure/deployment-environments.md), and [**Protocol Bridging**](../../system-architecture-protocol-bridging.md) for details on shared infrastructure components.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.

## Dev-isolated Mode

- Use `./gradlew :game-session-service:bootRunDevIsolated` (or set `GAME_SESSION_DEV_ISOLATED=true`) when you need to exercise the Game Session Service without PostgreSQL, Redis, or downstream gRPC dependencies. The dev-isolated beans acknowledge commands and lifecycle requests while only recording informational logs instead of accessing external systems.
- The `DevIsolatedGameSessionSmokeTest` in `services/game-session-service/src/test/java/integration/net/firedevops/firemud/DevIsolatedGameSessionSmokeTest.java` starts the dev profile in dev-isolated mode, posts to `POST /sessions`, and asserts the request is accepted and logged, proving the fast-path smoke test that only touches in-memory components.
- The dev-isolated smoke/integration tests (`DevIsolatedGameSessionSmokeTest`, `GameSessionLoginIntegrationTest`, `GameSessionWebSocketHandlerIntegrationTest`, `SessionResumptionFlowTest`) are currently decorated with `@Disabled` so they only act as TODO reminders until the real Account/Redis/GameInstance wiring exists (see `design/project-management/task-list-login-and-session-vertical-slice.md#7-dev-mode-stubs-and-real-service-rollout`).

## Environment Variables

This service follows the configuration scheme from
[Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md).
It requires the [PostgreSQL credentials](../../infrastructure/environment-and-secrets.md#postgresql-credentials)
and [Redis connection](../../infrastructure/environment-and-secrets.md#redis-connection)
variables.
TLS certificates are supplied via [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates). Peer services can be discovered using variables prefixed `FIREMUD_SERVICES_`.
The OpenTelemetry collector endpoint can be overridden via `OTEL_ENDPOINT` (see [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md)).

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `GAME_TICK_DURATION_MS` | Length of a single game tick in milliseconds | `1000` |
| `GAME_TICK_BUDGET_MS` | Soft execution budget for a tick in milliseconds | `100` |
| `GAME_SOLO_TICK_BUDGET_MS` | Execution budget for isolated solo ticks | `500` |
| `GAME_TICK_MAX_COMMANDS` | Max commands staged from the queue each tick | `50` |
| `FIREMUD_SERVICES_GAME_LOGIC_SERVICE` | gRPC endpoint (host:port) for the Game Logic Service | *(none)* |
| `FIREMUD_SERVICES_WORLD_MANAGEMENT_SERVICE` | gRPC endpoint (host:port) for the World Management Service | `world-management-service:6565` |
| `FIREMUD_SERVICES_ENTITY_MANAGEMENT_SERVICE` | gRPC endpoint (host:port) for the Entity Management Service | `entity-management-service:6565` |
| `FIREMUD_CONFLICT_TTL_SECONDS` | TTL for conflict hotspot tracking in Redis | `300` |

## Proto Files

Service definitions reside in
[../../../../protos/game-session/v1](../../../../protos/game-session/v1). Run
`./gradlew generateProto` after modifying these files to regenerate stubs.
The generated classes appear under `net.firedevops.firemud.gamesession.v1` in `build/generated/sources/proto/main/{grpc,java}` and are wired into `services/game-session-service/src/main/java/net/firedevops/firemud/service/impl/GameSessionGrpcService.java` so the module compiles the gRPC contract directly when it is built.

## 📚 Related Documentation

- [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md) — how game instances load published versions and runtime flags.
- [Reconnection Strategy](../../system-architecture-reconnection.md)
- [Authentication & Authorization](../../system-architecture-authentication.md)
- [Tick System and Runtime Design](../../system-architecture-ticks.md)
- [Redis Architecture](../../system-architecture-redis.md)
- [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- [gRPC API Style & Versioning Guidelines](../../system-architecture-grpc.md)
- [Shared Libraries Overview](../../system-architecture-shared-libraries.md)
- [Testing Strategy](../../system-architecture-testing.md)
- [CI/CD Pipeline](../../system-architecture-cicd.md)

## Minimal Text Command Protocol

Telnet and WebSocket clients share a minimal line-based command protocol that powers the initial MVP gameplay set. Clients send ASCII lines terminated by `\n`; the first token is the command name (case-insensitive) and the rest of the line is command-specific arguments. Empty lines are ignored.

| Command | Purpose | Example |
| ------- | ------- | ------- |
| `LOGIN <username> <password> [otp]` | Authenticates a session and binds it to an account; append an OTP when two-factor auth is enabled. | `LOGIN demo@example.com swordfish 123456` |
| `LOGON <username> <password> [otp]` | Exact alias for `LOGIN`; Telnet users often prefer the shorter name when typing from prompts. | `LOGON demo@example.com swordfish` |
| `LOOK` | Requests the current room description plus exits. | `LOOK` |
| `SAY <text>` | Broadcasts chat text to everyone in the same room. | `SAY Hello travelers` |

This small command table defines the initial MVP gameplay command set delivered by the Telnet-to-gameplay vertical slice; it should stay intentionally minimal while the protocol and interpreter mature.

### Login / Logon semantics

Telnet and WebSocket clients share this line-based syntax, but Telnet sessions frequently rely on prompt-driven exchanges while WebSocket clients typically send whole commands at once. Sending `LOGIN` (or the alias `LOGON`) with no arguments is intended to start the prompt flow, whereas `LOGIN <username> <password> [otp]` (or `LOGON ...`) performs an immediate authentication attempt. OTP values are passed through verbatim to the Account Service so two-factor accounts get the same experience. The same `OK <COMMAND>` / `ERROR <CODE> <message>` response format applies to both transports so clients can react consistently, and the examples below demonstrate at least one success and one failure path per transport.

**Note:** Prompt-based exchanges are planned but not implemented in this slice. Sending bare `LOGIN` currently returns `ERROR PROMPT_LOGIN_UNSUPPORTED Prompt-based login is not implemented yet; send LOGIN <username> <password>.` so Telnet clients should use the parameterized form until the prompt flow lands.

The Account Service returns canonical `AUTH_*` error codes (`AUTH_INVALID_CREDENTIALS`, `AUTH_OTP_REQUIRED`, `AUTH_ACCOUNT_LOCKED`, `AUTH_UPSTREAM_FAILURE`), and the Game Session Service translates them into the protocol-level responses (`ERROR INVALID_CREDENTIALS`, `ERROR OTP_REQUIRED`, etc.) so Telnet and WebSocket clients can rely on stable error semantics while the human-readable message remains flexible.

Additional Game Session-specific login failures cover parsing and session-state issues before the Account Service call:

- `PROMPT_LOGIN_UNSUPPORTED` – prompt-based LOGIN/LOGON exchanges are planned but not implemented yet, so clients must send `LOGIN <username> <password>`.
- `INVALID_ACCOUNT` – the Account Service returned an account identifier that could not be parsed into a long.
- `ACCOUNT_MISMATCH` – the authenticated account does not own the requested game session.
- `SESSION_NOT_FOUND` – the supplied session identifier has no corresponding `GameInstance`.
- `INVALID_ARGUMENT` – session ID parsing or other validation failed before the handler reached gameplay state.

Telnet success (prompt-based):

```text
LOGIN
OK LOGIN Enter username:
demo@example.com
OK LOGIN Enter password:
swordfish
OK LOGIN Logged in as demo@example.com
```

The transcript above presents the planned prompt flow. In the current implementation the same exchange is represented by a single `LOGIN <username> <password>` call because the prompt-driven handler returns `ERROR PROMPT_LOGIN_UNSUPPORTED ...`.

Telnet failure (wrong password):

```text
LOGIN demo@example.com wrongpass
ERROR INVALID_CREDENTIALS Invalid username or password
```

WebSocket success (parameterized command with optional OTP omitted):

```text
LOGIN demo@example.com swordfish
OK LOGIN Logged in as demo@example.com
```

WebSocket failure (account locked):

```text
LOGIN demo@example.com swordfish
ERROR ACCOUNT_LOCKED Account locked after repeated failures
```

### Implementation status (vertical slice)

For the current Telnet-to-gameplay vertical slice, the implementation intentionally separates "system" commands (session and login related) from gameplay commands:

- `LOGIN` / `LOGON` are treated as system commands owned by the Game Session Service and will be wired into the authentication and world-selection flow described in [Authentication & Authorization](../../system-architecture-authentication.md). At this stage they are defined in the protocol and parser, but the full login flow is still being implemented under `design/project-management/task-list-game-session-service.md`.
- `LOOK` is implemented as a minimal, deterministic room description inside the Game Session Service via a `LookCommandHandler`. This is a temporary gameplay stub used to validate the end-to-end text command flow and tests; future slices will route LOOK and other gameplay commands through the world/logic/scripting stack so that room descriptions and exits are fully data-driven.
- `SAY` and additional gameplay commands will follow the same pattern: they are part of the shared text protocol, but their long-term behavior is provided by soft-coded definitions and the Game Logic/World services rather than hard-coded handlers in this service.

The `TextCommandInterpreter` currently returns a result that includes both enqueue metadata (for the tick/command queue) and optional immediate response text. This shape is intended to remain stable as the implementation shifts from hard-coded handlers to data-driven gameplay logic. Once this login slice lands, gameplay commands such as `LOOK` and `SAY` only execute for authenticated sessions (outside of explicitly documented dev/test bypasses), so the interpreter rejects untrusted text with `ERROR NOT_AUTHENTICATED` before the command queue ever sees it.

### Response format

- Every response is plain text. The first line is either `OK <COMMAND>` or `ERROR <CODE> <message>`.
- Success responses may include additional lines describing the outcome. A blank line terminates the response block so multiple responses can be streamed back-to-back without ambiguity.
- Asynchronous world events (such as other players talking) use the same rules but are prefixed with `EVENT <TYPE>` to distinguish them from direct command responses.
- Unknown commands return `ERROR UNKNOWN_COMMAND <rawLine>`.

Examples:

```text
LOGIN demo@example.com swordfish
OK LOGIN Logged in as demo@example.com

LOOK
OK LOOK
You are in a candle-lit antechamber carved into basalt.
Exits: NORTH EAST

SAY Hello travelers
OK SAY
You say: Hello travelers
EVENT SAY
A kobold says: Stay sharp.

DANCE
ERROR UNKNOWN_COMMAND DANCE
```

## Additional Details

### Configuration

Environment variables configure the PostgreSQL and Redis connections via `DatabaseAutoConfiguration` and `RedisProperties`. Refer to [Deployment Environments](../../infrastructure/deployment-environments.md) for details. The `.env.sample` file contains example values.

The service enforces multi-tenant isolation. All tables include a `tenant_id` column and Redis keys are prefixed with this value as outlined in the [Multi-Tenancy design](../../system-architecture-multi-tenancy.md).

### REST & gRPC Endpoints

#### REST

- `GET /ping` – basic health check returning `"pong"`.
- `POST /sessions` – create a new game session from a published version.
- `POST /sessions/{id}/stop` – stop a running session.
- `POST /sessions/{id}/restart` – restart a stopped session.
- `POST /sessions/{id}/refresh-roles` – refresh the player's roles for an active session.

Use `/sessions/{id}/refresh-roles` after updating an account's privileges so the
session reflects the latest role assignments.

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

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`game_session_service.proto`](../../../../protos/game-session/v1/game_session_service.proto).
- `StartSession(StartSessionRequest) returns (StartSessionResponse)` – creates a new game instance.
- `StopSession(StopSessionRequest) returns (StopSessionResponse)` – stops a running session.
- `RestartSession(RestartSessionRequest) returns (RestartSessionResponse)` – restarts a stopped session.
- `EnqueueCommand(EnqueueCommandRequest) returns (EnqueueCommandResponse)` – queues a player action.
- `QueryState(QueryStateRequest) returns (QueryStateResponse)` – retrieves current game or player state.
- `ToggleFeatureFlag(ToggleFeatureFlagRequest) returns (ToggleFeatureFlagResponse)` – updates runtime flags for a tenant.

```bash
grpcurl -plaintext localhost:6565 game_session.v1.GameSessionService/Ping
```

Start a session via gRPC:

```bash
grpcurl -plaintext -d '{"tenantId":"demo","runtimeVersion":"v42","scriptPatchVersion":"v42-script.3"}' \
  localhost:6565 game_session.v1.GameSessionService/StartSession
```

### Additional Notes

- See [Cross-Region Sharding and Session Handoff](#cross-region-sharding-and-session-handoff) for how sessions migrate between clusters.
- Metrics emitted by this service feed the operator [Analytics Dashboards](../logging-admin-service/analytics-dashboards.md). Prometheus scrapes metrics from `/actuator/prometheus`.
- Logs and metrics include a `script_patch_version` label so operators know which
  hotfix revision is active.

### Runtime Feature Flags

Feature flags are stored in the `feature_flag` table and can be toggled through the Logging & Admin Service. The Game Session Service exposes a gRPC `ToggleFeatureFlag` method so administrators can enable or disable experimental behavior without restarting a session. See [Game Design Service Feature Flags](../game-design-service/feature-flags.md) for how definitions are created and published.

### Saga Participation

Game startup and shutdown are coordinated using the shared `Saga` helpers from `firemud-common`. Each dependent service (World Management, Entity Management and Game Logic) confirms its part of the workflow before the session becomes active. Failures trigger compensating steps, ensuring consistent rollbacks. See [Transaction Strategies](../../system-architecture-transactions.md) for background.

### Redis Keys

Session state needed for reconnect recovery is stored under `session:{tenantId}:{sessionId}`. Tick queues, locks and pending sets use the same prefix. Keys are removed when a session stops.

- [Logging & Monitoring](../../system-architecture-logging-monitoring.md)
- [Backup & Disaster Recovery](../../system-architecture-backup-recovery.md)
- [System Architecture Overview](../../system-architecture-overview.md)
- [Service Responsibility Matrix](../../service-responsibility-matrix.md)
- [User Journeys – Publish and Start a Game Instance](../../user-journeys.md#5-publish-and-start-a-game-instance)
- [User Journeys – Player Login and Gameplay](../../user-journeys.md#7-player-login-and-gameplay)

- [System Architecture Diagram](../../system-architecture-diagram.md)
- [System Context Diagram](../../system-context-diagram.md)

### Cross-Service Integration Test

An integration test under `src/test/java/crossservice` starts this service
alongside the Game Logic Service using **Testcontainers**. Run it manually once
the dependent Docker images are built:

```bash
./gradlew :game-session-service:test --tests "*CrossServiceIntegrationTest"
```

See [System Architecture Testing](../../system-architecture-testing.md) for more
details.

## Additional Features

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
[Analytics Dashboards](../logging-admin-service/analytics-dashboards.md)
to monitor game health and player activity.
